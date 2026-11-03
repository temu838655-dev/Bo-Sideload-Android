package com.bosideload.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Codec for the 16-byte usbmuxd binary packet header.
 *
 * This only validates/encodes the transport header. It does not open USB,
 * perform plist serialization, pair with Lockdown, or install applications.
 */
internal object UsbMuxPacketHeader {
    const val HEADER_SIZE = 16
    const val VERSION = 1L
    const val MESSAGE_PLIST = 8L
    const val DEFAULT_MAX_PACKET_SIZE = 16L * 1024L * 1024L

    data class Header(
        val length: Long,
        val version: Long,
        val message: Long,
        val tag: Long
    )

    fun encode(message: Long, tag: Long, payloadSize: Int): ByteArray {
        require(message in 0L..0xFFFF_FFFFL) { "message out of range" }
        require(tag in 0L..0xFFFF_FFFFL) { "tag out of range" }
        require(payloadSize >= 0) { "negative payload size" }
        val total = HEADER_SIZE.toLong() + payloadSize.toLong()
        require(total <= DEFAULT_MAX_PACKET_SIZE) { "packet too large" }
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(total.toInt())
            .putInt(VERSION.toInt())
            .putInt(message.toInt())
            .putInt(tag.toInt())
            .array()
    }

    fun decode(
        bytes: ByteArray,
        maxPacketSize: Long = DEFAULT_MAX_PACKET_SIZE
    ): Header {
        require(bytes.size >= HEADER_SIZE) { "incomplete usbmux header" }
        require(maxPacketSize >= HEADER_SIZE) { "invalid maximum packet size" }
        val buffer = ByteBuffer.wrap(bytes, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val length = buffer.int.toLong() and 0xFFFF_FFFFL
        val version = buffer.int.toLong() and 0xFFFF_FFFFL
        val message = buffer.int.toLong() and 0xFFFF_FFFFL
        val tag = buffer.int.toLong() and 0xFFFF_FFFFL
        require(length >= HEADER_SIZE) { "invalid packet length" }
        require(length <= maxPacketSize) { "packet exceeds configured limit" }
        require(version == VERSION) { "unsupported usbmux protocol version" }
        return Header(length, version, message, tag)
    }
}
