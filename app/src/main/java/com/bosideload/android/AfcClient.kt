package com.bosideload.android

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Minimal synchronous AFC client for safely uploading a single file.
 *
 * The supplied streams must already represent the AFC service byte stream.
 * If Lockdown StartService returns EnableServiceSSL=true, the caller MUST
 * complete the paired service TLS handshake before constructing this client.
 */
internal class AfcClient(
    private val input: InputStream,
    private val output: OutputStream,
    private val closeAction: (() -> Unit)? = null,
    private val maxWriteChunkBytes: Int = DEFAULT_WRITE_CHUNK_BYTES
) : Closeable {
    private var packetNumber = 0L
    private var closed = false

    init {
        require(maxWriteChunkBytes in 1..MAX_WRITE_CHUNK_BYTES) {
            "AFC write chunk must be between 1 and $MAX_WRITE_CHUNK_BYTES bytes"
        }
    }

    data class UploadResult(
        val remotePath: String,
        val bytesWritten: Long
    )

    /**
     * Uploads to an absolute AFC path. The caller chooses a safe staging path;
     * this method does not install, delete, or overwrite any other device file.
     */
    @Synchronized
    @Throws(IOException::class)
    fun uploadFile(
        remotePath: String,
        source: InputStream,
        expectedSize: Long,
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): UploadResult {
        checkOpen()
        validateRemotePath(remotePath)
        require(expectedSize >= 0) { "expectedSize must not be negative" }

        val pathBytes = remotePath.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0)
        val openData = ByteBuffer.allocate(8 + pathBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(AFC_FOPEN_WRONLY.toLong())
            .put(pathBytes)
            .array()
        val handle = openForWrite(openData)
        var written = 0L
        var failure: IOException? = null
        try {
            val buffer = ByteArray(maxWriteChunkBytes)
            while (written < expectedSize) {
                val wanted = minOf(buffer.size.toLong(), expectedSize - written).toInt()
                val count = readSourceChunk(source, buffer, wanted)
                if (count < 0) throw IOException("IPA source ended before expected size")
                if (count == 0) continue
                writeFileChunk(handle, buffer, count)
                written += count
                onProgress?.invoke(written, expectedSize)
            }
            if (source.read() != -1) {
                throw IOException("IPA source contains more bytes than expected size")
            }
        } catch (error: IOException) {
            failure = error
            throw error
        } finally {
            try {
                closeFile(handle)
            } catch (closeError: IOException) {
                if (failure == null) throw closeError
                failure.addSuppressed(closeError)
            }
        }
        return UploadResult(remotePath, written)
    }

    private fun openForWrite(data: ByteArray): Long {
        val response = exchange(AFC_OP_FILE_OPEN, data)
        if (response.header.operation != AFC_OP_FILE_OPEN_RES || response.body.size != 8) {
            throw IOException("AFC FileOpen returned an invalid file-handle response")
        }
        val handle = ByteBuffer.wrap(response.body).order(ByteOrder.LITTLE_ENDIAN).long
        if (handle == 0L) throw IOException("AFC FileOpen returned an invalid zero handle")
        return handle
    }

    private fun writeFileChunk(handle: Long, bytes: ByteArray, length: Int) {
        val handleBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(handle).array()
        val response = exchange(AFC_OP_FILE_WRITE, handleBytes, bytes.copyOf(length))
        requireSuccessStatus(response, "FileWrite")
    }

    private fun closeFile(handle: Long) {
        val data = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(handle).array()
        val response = exchange(AFC_OP_FILE_CLOSE, data)
        requireSuccessStatus(response, "FileClose")
    }

    private fun exchange(
        operation: Long,
        data: ByteArray = byteArrayOf(),
        payload: ByteArray = byteArrayOf()
    ): AfcPacketCodec.Packet {
        checkOpen()
        if (packetNumber == Long.MAX_VALUE) throw IOException("AFC packet counter exhausted")
        packetNumber += 1
        val encoded = try {
            AfcPacketCodec.encode(packetNumber, operation, data, payload)
        } catch (error: IllegalArgumentException) {
            throw IOException("Could not encode AFC request", error)
        }
        // libimobiledevice sends the 40-byte header plus this_length data first,
        // then sends any separate payload bytes on the same service stream.
        val headerAndDataLength = AfcPacketCodec.HEADER_SIZE + data.size
        output.write(encoded, 0, headerAndDataLength)
        if (payload.isNotEmpty()) {
            output.write(encoded, headerAndDataLength, payload.size)
        }
        output.flush()
        return AfcPacketCodec.readPacket(input, packetNumber)
    }

    private fun requireSuccessStatus(packet: AfcPacketCodec.Packet, operation: String) {
        if (packet.header.operation != AFC_OP_STATUS || packet.body.size != 8) {
            throw IOException("AFC $operation returned an invalid status response")
        }
        val status = ByteBuffer.wrap(packet.body).order(ByteOrder.LITTLE_ENDIAN).long
        if (status != AFC_E_SUCCESS) {
            throw IOException("AFC $operation failed with status $status")
        }
    }

    private fun readSourceChunk(source: InputStream, buffer: ByteArray, wanted: Int): Int {
        var total = 0
        while (total < wanted) {
            val count = source.read(buffer, total, wanted - total)
            if (count < 0) return if (total == 0) -1 else total
            if (count == 0) {
                val one = source.read()
                if (one < 0) return if (total == 0) -1 else total
                buffer[total++] = one.toByte()
            } else {
                total += count
            }
        }
        return total
    }

    private fun validateRemotePath(path: String) {
        require(path.startsWith("/") && path.length > 1) { "AFC path must be absolute" }
        require('\u0000' !in path) { "AFC path contains a NUL character" }
        require(!path.split('/').any { it == "." || it == ".." }) {
            "AFC path must not contain traversal segments"
        }
    }

    private fun checkOpen() {
        if (closed) throw IOException("AFC client is closed")
    }

    override fun close() {
        if (closed) return
        closed = true
        var error: IOException? = null
        try {
            input.close()
        } catch (e: IOException) {
            error = e
        }
        try {
            output.close()
        } catch (e: IOException) {
            if (error == null) error = e else error.addSuppressed(e)
        }
        runCatching { closeAction?.invoke() }
        if (error != null) throw error
    }

    companion object {
        const val DEFAULT_WRITE_CHUNK_BYTES = 64 * 1024
        const val MAX_WRITE_CHUNK_BYTES = 1024 * 1024

        private const val AFC_FOPEN_WRONLY = 0x03L
        private const val AFC_OP_STATUS = 0x01L
        private const val AFC_OP_FILE_OPEN = 0x0DL
        private const val AFC_OP_FILE_OPEN_RES = 0x0EL
        private const val AFC_OP_FILE_WRITE = 0x10L
        private const val AFC_OP_FILE_CLOSE = 0x14L
        private const val AFC_E_SUCCESS = 0L
    }
}
