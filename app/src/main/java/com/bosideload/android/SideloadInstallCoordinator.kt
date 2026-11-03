package com.bosideload.android

import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Coordinates the protocol sequence after a trusted Lockdown TLS session exists:
 * StartService(AFC) -> upload IPA -> StartService(installation_proxy) -> Install.
 *
 * Each StartService request runs in a short-lived Lockdown session which is
 * closed before opening that service's USBMux Connect stream. Android USB Host
 * exposes one claimed USBMux interface; overlapping Lockdown and service
 * transports can contend for the same endpoints.
 *
 * The caller supplies factories for fresh Lockdown and service transports.
 * This class does not perform code signing or create a pairing record.
 */
internal class SideloadInstallCoordinator(
    private val openLockdownSession: () -> LockdownServiceSession,
    private val deviceId: Long,
    private val pairRecord: PairRecord,
    private val newTransport: () -> UsbMuxTransport
) {
    enum class Stage { UPLOADING_IPA, INSTALLING_APP, COMPLETE }

    data class Progress(
        val stage: Stage,
        val bytesTransferred: Long? = null,
        val totalBytes: Long? = null,
        val installPercent: Int? = null,
        val message: String
    )

    data class Result(
        val remotePackagePath: String,
        val bytesUploaded: Long
    )

    /**
     * Uploads the supplied IPA into the iPhone public staging directory, then
     * asks installation_proxy to install that exact remote path.
     *
     * This operation is synchronous. Run it on a worker thread, not Android's
     * main/UI thread. The input stream remains owned by the caller.
     */
    @Throws(IOException::class)
    fun install(
        ipaInput: InputStream,
        ipaSizeBytes: Long,
        clientOptions: Map<String, Any?> = emptyMap(),
        onProgress: ((Progress) -> Unit)? = null
    ): Result {
        pairRecord.validate()
        if (deviceId !in 1L..0xFFFF_FFFFL) throw IOException("invalid usbmux device ID")
        if (ipaSizeBytes <= 0) throw IOException("IPA file is empty or has an invalid size")

        val remotePath = "/PublicStaging/BoSideload-${UUID.randomUUID()}.ipa"

        val afcDescriptor = openLockdownSession().use { lockdown ->
            LockdownServiceStarter.start(
                channel = lockdown.channel,
                identifier = AfcServiceConnection.AFC_SERVICE_IDENTIFIER
            )
        }
        val upload = AfcServiceConnection.open(
            transport = newTransport(),
            deviceId = deviceId,
            descriptor = afcDescriptor,
            record = pairRecord
        ).use { connection ->
            connection.client.uploadFile(
                remotePath = remotePath,
                source = ipaInput,
                expectedSize = ipaSizeBytes
            ) { written, total ->
                onProgress?.invoke(
                    Progress(
                        stage = Stage.UPLOADING_IPA,
                        bytesTransferred = written,
                        totalBytes = total,
                        message = "Đang truyền IPA sang iPhone"
                    )
                )
            }
        }

        val installationDescriptor = openLockdownSession().use { lockdown ->
            LockdownServiceStarter.start(
                channel = lockdown.channel,
                identifier = InstallationProxyServiceConnection.SERVICE_IDENTIFIER
            )
        }
        InstallationProxyServiceConnection.open(
            transport = newTransport(),
            deviceId = deviceId,
            descriptor = installationDescriptor,
            record = pairRecord
        ).use { connection ->
            connection.client.install(
                remotePackagePath = remotePath,
                clientOptions = clientOptions
            ) { status ->
                onProgress?.invoke(
                    Progress(
                        stage = Stage.INSTALLING_APP,
                        installPercent = status.percentComplete,
                        message = status.status ?: status.error ?: "Đang cài ứng dụng trên iPhone"
                    )
                )
            }
        }

        onProgress?.invoke(
            Progress(
                stage = Stage.COMPLETE,
                bytesTransferred = upload.bytesWritten,
                totalBytes = ipaSizeBytes,
                installPercent = 100,
                message = "iPhone đã báo cài đặt hoàn tất"
            )
        )
        return Result(remotePath, upload.bytesWritten)
    }
}
