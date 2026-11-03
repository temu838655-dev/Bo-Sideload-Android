package com.bosideload.android

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Length-prefixed plist framing used by the Apple Lockdown service.
 *
 * The payload is the serialized plist bytes. This codec deliberately does not
 * parse plist content, authenticate a host, or claim a trusted pairing.
 */
internal object LockdownPacketCodec {
    const val HEADER_SIZE = 4
    const val DEFAULT_MAX_PAYLOAD_SIZE = 16 * 1024 * 1024

    fun encode(payload: ByteArray, maxPayloadSize: Int = DEFAULT_MAX_PAYLOAD_SIZE): ByteArray {
        require(maxPayloadSize >= 0) { "invalid maximum payload size" }
        require(payload.size <= maxPayloadSize) { "Lockdown payload exceeds configured limit" }
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(payload.size)
            .array()
        return header + payload
    }

    fun write(
        output: OutputStream,
        payload: ByteArray,
        maxPayloadSize: Int = DEFAULT_MAX_PAYLOAD_SIZE
    ) {
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(payload.size.also {
                require(maxPayloadSize >= 0) { "invalid maximum payload size" }
                require(it <= maxPayloadSize) { "Lockdown payload exceeds configured limit" }
            })
            .array()
        output.write(header)
        output.write(payload)
        output.flush()
    }

    fun read(
        input: InputStream,
        maxPayloadSize: Int = DEFAULT_MAX_PAYLOAD_SIZE
    ): ByteArray {
        require(maxPayloadSize >= 0) { "invalid maximum payload size" }
        val header = ByteArray(HEADER_SIZE)
        readFully(input, header)
        val length = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
        require(length >= 0) { "negative Lockdown payload length" }
        require(length <= maxPayloadSize) { "Lockdown payload exceeds configured limit" }
        return ByteArray(length).also { readFully(input, it) }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw EOFException("incomplete Lockdown packet")
            if (count == 0) {
                val value = input.read()
                if (value < 0) throw EOFException("incomplete Lockdown packet")
                target[offset++] = value.toByte()
            } else {
                offset += count
            }
        }
    }
}
