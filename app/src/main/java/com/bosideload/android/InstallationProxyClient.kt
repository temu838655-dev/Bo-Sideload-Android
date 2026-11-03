package com.bosideload.android

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * XML-plist property-list service framing used by installation_proxy.
 * The 4-byte length is big-endian; payloads are XML plists for commands.
 */
internal object PropertyListServiceCodec {
    const val HEADER_BYTES = 4
    const val DEFAULT_MAX_PAYLOAD_BYTES = 8 * 1024 * 1024

    fun encode(payload: ByteArray, maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES): ByteArray {
        require(payload.isNotEmpty() && payload.size <= maxPayloadBytes) {
            "invalid property-list service payload size"
        }
        return ByteBuffer.allocate(HEADER_BYTES + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(payload.size)
            .put(payload)
            .array()
    }

    fun write(output: OutputStream, payload: ByteArray, maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES) {
        val packet = encode(payload, maxPayloadBytes)
        output.write(packet)
        output.flush()
    }

    @Throws(IOException::class)
    fun read(input: InputStream, maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES): ByteArray {
        val header = ByteArray(HEADER_BYTES)
        readFully(input, header)
        val length = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
        if (length <= 0 || length > maxPayloadBytes) {
            throw IOException("invalid property-list service packet length: $length")
        }
        return ByteArray(length).also { readFully(input, it) }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw EOFException("incomplete property-list service packet")
            if (count == 0) {
                val one = input.read()
                if (one < 0) throw EOFException("incomplete property-list service packet")
                target[offset++] = one.toByte()
            } else {
                offset += count
            }
        }
    }
}

/** Status reported by Apple's installation_proxy during an install operation. */
internal data class InstallationProgress(
    val status: String?,
    val percentComplete: Int?,
    val error: String?,
    val errorDescription: String?
)

/**
 * Synchronous installation_proxy client. It sends the documented Install
 * command and consumes status plists until Status=Complete or a device error.
 * The IPA must already have been uploaded to the device (usually via AFC
 * into /PublicStaging) before calling install().
 */
internal class InstallationProxyClient(
    private val input: InputStream,
    private val output: OutputStream,
    private val maxPayloadBytes: Int = PropertyListServiceCodec.DEFAULT_MAX_PAYLOAD_BYTES
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val operationInProgress = AtomicBoolean(false)

    init {
        require(maxPayloadBytes > 0)
    }

    @Throws(IOException::class)
    fun install(
        remotePackagePath: String,
        clientOptions: Map<String, Any?> = emptyMap(),
        onProgress: ((InstallationProgress) -> Unit)? = null
    ) {
        checkOpen()
        check(operationInProgress.compareAndSet(false, true)) {
            "an installation_proxy operation is already running"
        }
        require(remotePackagePath.startsWith("/") && remotePackagePath.length > 1) {
            "PackagePath must be an absolute device path"
        }
        require('\u0000' !in remotePackagePath) { "PackagePath contains NUL" }
        require(!remotePackagePath.split('/').any { it == "." || it == ".." }) {
            "PackagePath contains traversal segments"
        }
        try {
            val command = linkedMapOf<String, Any?>(
                "Command" to "Install",
                "PackagePath" to remotePackagePath
            )
            if (clientOptions.isNotEmpty()) command["ClientOptions"] = clientOptions
            PropertyListServiceCodec.write(output, LockdownPlistCodec.encode(command), maxPayloadBytes)

            while (true) {
                checkOpen()
                val response = try {
                    LockdownPlistCodec.decode(PropertyListServiceCodec.read(input, maxPayloadBytes))
                } catch (error: Exception) {
                    if (error is IOException) throw error
                    throw IOException("installation_proxy returned an invalid plist", error)
                }

                val status = response["Status"] as? String
                val errorName = response["Error"] as? String
                if (status.isNullOrBlank() && errorName.isNullOrBlank()) {
                    throw IOException("installation_proxy returned a status without Status or Error")
                }
                val errorDescription = response["ErrorDescription"] as? String
                val percent = (response["PercentComplete"] as? Number)?.toInt()
                val progress = InstallationProgress(
                    status = status,
                    percentComplete = percent?.takeIf { it in 0..100 },
                    error = errorName,
                    errorDescription = errorDescription
                )
                onProgress?.invoke(progress)

                if (!errorName.isNullOrBlank()) {
                    throw IOException(
                        buildString {
                            append("iPhone installation failed: ")
                            append(errorName)
                            if (!errorDescription.isNullOrBlank()) {
                                append(" — ")
                                append(errorDescription)
                            }
                        }
                    )
                }
                if (status == "Complete") return
            }
        } finally {
            operationInProgress.set(false)
        }
    }

    private fun checkOpen() {
        if (closed.get()) throw IOException("installation_proxy client is closed")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { input.close() }
        runCatching { output.close() }
    }
}

/**
 * Opens a fresh usbmux connection to com.apple.mobile.installation_proxy.
 * A service TLS flag is honored using the paired Root identity.
 */
internal class InstallationProxyServiceConnection private constructor(
    private val connectSession: UsbMuxConnectSession,
    private val tlsSession: PairedServiceTlsSocket?,
    val client: InstallationProxyClient
) : Closeable {
    override fun close() {
        try {
            client.close()
        } finally {
            try {
                tlsSession?.close()
            } finally {
                if (tlsSession == null) connectSession.close()
            }
        }
    }

    companion object {
        const val SERVICE_IDENTIFIER = "com.apple.mobile.installation_proxy"

        @Throws(IOException::class)
        fun open(
            transport: UsbMuxTransport,
            deviceId: Long,
            descriptor: LockdownServiceStarter.ServiceDescriptor,
            record: PairRecord
        ): InstallationProxyServiceConnection {
            if (descriptor.identifier != SERVICE_IDENTIFIER) {
                runCatching { transport.close() }
                throw IOException("service descriptor is not installation_proxy")
            }
            val connect = UsbMuxConnectSession.connect(
                transport = transport,
                deviceId = deviceId,
                devicePort = descriptor.port
            )
            try {
                val tls = if (descriptor.enableServiceSsl) {
                    PairedServiceTlsSocket.upgrade(connect, record)
                } else null
                val input = tls?.inputStream ?: connect.inputStream
                val output = tls?.outputStream ?: connect.outputStream
                return InstallationProxyServiceConnection(
                    connect,
                    tls,
                    InstallationProxyClient(input, output)
                )
            } catch (error: Exception) {
                runCatching { connect.close() }
                if (error is IOException) throw error
                throw IOException("Could not initialize installation_proxy connection", error)
            }
        }
    }
}
