package com.bosideload.android

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire codec for Apple's AFC service protocol (libimobiledevice AFCPacket).
 *
 * AFC is NOT Lockdown's 4-byte-length-prefixed plist protocol. Its fixed
 * header is 40 bytes: 8-byte magic followed by four little-endian uint64s.
 */
internal object AfcPacketCodec {
    const val HEADER_SIZE = 40
    const val MAGIC = "CFA6LPAA"
    const val MAX_PACKET_SIZE = 8 * 1024 * 1024

    data class Header(
        val entireLength: Long,
        val thisLength: Long,
        val packetNumber: Long,
        val operation: Long
    )

    data class Packet(val header: Header, val body: ByteArray)

    fun encode(
        packetNumber: Long,
        operation: Long,
        data: ByteArray = byteArrayOf(),
        payload: ByteArray = byteArrayOf()
    ): ByteArray {
        require(packetNumber in 0..Long.MAX_VALUE)
        require(operation in 0..Long.MAX_VALUE)
        val entireLength = HEADER_SIZE.toLong() + data.size + payload.size
        require(entireLength <= MAX_PACKET_SIZE) { "AFC packet exceeds maximum size" }
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put(MAGIC.toByteArray(Charsets.US_ASCII))
        header.putLong(entireLength)
        header.putLong((HEADER_SIZE + data.size).toLong())
        header.putLong(packetNumber)
        header.putLong(operation)
        return ByteArray(entireLength.toInt()).also { packet ->
            System.arraycopy(header.array(), 0, packet, 0, HEADER_SIZE)
            System.arraycopy(data, 0, packet, HEADER_SIZE, data.size)
            System.arraycopy(payload, 0, packet, HEADER_SIZE + data.size, payload.size)
        }
    }

    fun decodeHeader(bytes: ByteArray): Header {
        require(bytes.size == HEADER_SIZE) { "AFC header must be exactly 40 bytes" }
        val magic = String(bytes, 0, 8, Charsets.US_ASCII)
        require(magic == MAGIC) { "Invalid AFC packet magic" }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        input.position(8)
        val entire = input.long
        val current = input.long
        val number = input.long
        val operation = input.long
        require(entire >= HEADER_SIZE && entire <= MAX_PACKET_SIZE) {
            "Invalid AFC entire_length: $entire"
        }
        require(current >= HEADER_SIZE && current <= entire) {
            "Invalid AFC this_length: $current"
        }
        require(number >= 0 && operation >= 0) { "Invalid AFC packet number or operation" }
        return Header(entire, current, number, operation)
    }

    @Throws(IOException::class)
    fun readPacket(input: java.io.InputStream, expectedPacketNumber: Long): Packet {
        val rawHeader = ByteArray(HEADER_SIZE)
        readFully(input, rawHeader)
        val header = try {
            decodeHeader(rawHeader)
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid AFC response header", error)
        }
        if (header.packetNumber != expectedPacketNumber) {
            throw IOException(
                "AFC response packet number mismatch: expected $expectedPacketNumber, got ${header.packetNumber}"
            )
        }
        val body = ByteArray((header.entireLength - HEADER_SIZE).toInt())
        readFully(input, body)
        return Packet(header, body)
    }

    @Throws(IOException::class)
    fun readFully(input: java.io.InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw EOFException("Unexpected EOF in AFC packet")
            if (count == 0) {
                val one = input.read()
                if (one < 0) throw EOFException("Unexpected EOF in AFC packet")
                target[offset++] = one.toByte()
            } else {
                offset += count
            }
        }
    }
}
