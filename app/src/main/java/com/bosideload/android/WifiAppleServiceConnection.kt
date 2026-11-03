package com.bosideload.android

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Opens an Apple service TCP endpoint advertised by Lockdown StartService.
 * The endpoint host is the already-resolved Bonjour host; its port is the
 * service port returned by the authenticated Lockdown session.
 */
internal class WifiAppleServiceConnection private constructor(
    private val socket: Socket,
    private val tls: PairedServiceTlsSocket?
) : Closeable {
    val inputStream get() = tls?.inputStream ?: socket.getInputStream()
    val outputStream get() = tls?.outputStream ?: socket.getOutputStream()

    override fun close() {
        try {
            tls?.close()
        } finally {
            if (tls == null) runCatching { socket.close() }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 120_000

        @Throws(IOException::class)
        fun open(
            hostAddress: String,
            descriptor: LockdownServiceStarter.ServiceDescriptor,
            record: PairRecord
        ): WifiAppleServiceConnection {
            if (hostAddress.isBlank()) throw IOException("Wi-Fi device address is missing")
            if (descriptor.port !in 1..65535) throw IOException("Apple service port is invalid")
            record.validate()

            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(
                    InetSocketAddress(hostAddress, descriptor.port),
                    CONNECT_TIMEOUT_MS
                )
                socket.soTimeout = READ_TIMEOUT_MS
                val tls = if (descriptor.enableServiceSsl) {
                    PairedServiceTlsSocket.upgrade(socket, record)
                } else null
                return WifiAppleServiceConnection(socket, tls)
            } catch (error: Exception) {
                runCatching { socket.close() }
                if (error is IOException) throw error
                throw IOException("Could not open Apple service over Wi-Fi", error)
            }
        }
    }
}

/**
 * Wi-Fi equivalent of the existing USB sideload coordinator for legacy
 * Lockdown Wi-Fi Sync. The caller must supply an authenticated TLS Lockdown
 * session obtained only after Bonjour authTag candidate matching.
 *
 * This deliberately does not implement CoreDevice RemotePairing/RSD.
 */
internal class WifiSideloadInstallCoordinator(
    private val lockdown: LockdownTlsSession,
    private val hostAddress: String,
    private val pairRecord: PairRecord
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
        val bytesUploaded: Long,
        val stagingCleanupSucceeded: Boolean
    )

    private fun startService(identifier: String): LockdownServiceStarter.ServiceDescriptor =
        LockdownServiceStarter.start(
            channel = lockdown.channel,
            identifier = identifier,
            escrowBag = pairRecord.escrowBag
        )

    private fun removeStagedIpa(path: String) {
        val descriptor = startService(AfcServiceConnection.AFC_SERVICE_IDENTIFIER)
        WifiAppleServiceConnection.open(hostAddress, descriptor, pairRecord).use { service ->
            AfcClient(service.inputStream, service.outputStream).use { afc ->
                afc.removeStagedIpa(path)
            }
        }
    }

    @Throws(IOException::class)
    fun install(
        ipaInput: java.io.InputStream,
        ipaSizeBytes: Long,
        clientOptions: Map<String, Any?> = emptyMap(),
        onProgress: ((Progress) -> Unit)? = null
    ): Result {
        pairRecord.validate()
        if (ipaSizeBytes <= 0) throw IOException("IPA file is empty or has an invalid size")
        val remotePath = "/PublicStaging/BoSideload-${java.util.UUID.randomUUID()}.ipa"

        val upload: AfcClient.UploadResult
        try {
            val afcDescriptor = startService(AfcServiceConnection.AFC_SERVICE_IDENTIFIER)
            WifiAppleServiceConnection.open(hostAddress, afcDescriptor, pairRecord).use { service ->
                AfcClient(service.inputStream, service.outputStream).use { afc ->
                    upload = afc.uploadFile(remotePath, ipaInput, ipaSizeBytes) { written, total ->
                        onProgress?.invoke(
                            Progress(
                                Stage.UPLOADING_IPA,
                                bytesTransferred = written,
                                totalBytes = total,
                                message = "Đang truyền IPA sang iPhone qua Wi-Fi"
                            )
                        )
                    }
                }
            }

            val installDescriptor = startService(
                InstallationProxyServiceConnection.SERVICE_IDENTIFIER
            )
            WifiAppleServiceConnection.open(hostAddress, installDescriptor, pairRecord).use { service ->
                InstallationProxyClient(service.inputStream, service.outputStream).use { installer ->
                    installer.install(remotePath, clientOptions) { status ->
                        onProgress?.invoke(
                            Progress(
                                Stage.INSTALLING_APP,
                                installPercent = status.percentComplete,
                                message = status.status ?: status.error ?: "Đang cài ứng dụng trên iPhone"
                            )
                        )
                    }
                }
            }
        } catch (failure: Exception) {
            try {
                removeStagedIpa(remotePath)
            } catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
            }
            if (failure is IOException) throw failure
            throw IOException("Wi-Fi IPA installation failed", failure)
        }

        val cleanupSucceeded = try {
            removeStagedIpa(remotePath)
            true
        } catch (_: Exception) {
            false
        }
        onProgress?.invoke(
            Progress(
                Stage.COMPLETE,
                bytesTransferred = upload.bytesWritten,
                totalBytes = ipaSizeBytes,
                installPercent = 100,
                message = if (cleanupSucceeded) {
                    "iPhone báo cài đặt hoàn tất; IPA tạm đã được xóa"
                } else {
                    "iPhone báo cài đặt hoàn tất; không xóa được IPA tạm"
                }
            )
        )
        return Result(remotePath, upload.bytesWritten, cleanupSucceeded)
    }
}
