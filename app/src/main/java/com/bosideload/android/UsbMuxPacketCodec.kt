package com.bosideload.android

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Reads and writes complete usbmuxd packets over a byte stream.
 *
 * The packet length is validated before allocating the payload buffer. This
 * helper is transport-neutral and does not itself establish a USB connection
 * or perform Apple Lockdown pairing.
 */
internal object UsbMuxPacketCodec {
    data class Packet(
        val header: UsbMuxPacketHeader.Header,
        val payload: ByteArray
    )

    fun encode(message: Long, tag: Long, payload: ByteArray): ByteArray {
        val header = UsbMuxPacketHeader.encode(message, tag, payload.size)
        return header + payload
    }

    fun write(output: OutputStream, message: Long, tag: Long, payload: ByteArray) {
        output.write(UsbMuxPacketHeader.encode(message, tag, payload.size))
        output.write(payload)
        output.flush()
    }

    fun read(
        input: InputStream,
        maxPacketSize: Long = UsbMuxPacketHeader.DEFAULT_MAX_PACKET_SIZE
    ): Packet {
        require(maxPacketSize in UsbMuxPacketHeader.HEADER_SIZE.toLong()..Int.MAX_VALUE.toLong()) {
            "invalid maximum packet size"
        }
        val headerBytes = ByteArray(UsbMuxPacketHeader.HEADER_SIZE)
        readFully(input, headerBytes)
        val header = UsbMuxPacketHeader.decode(headerBytes, maxPacketSize)
        val payloadLength = (header.length - UsbMuxPacketHeader.HEADER_SIZE).toInt()
        val payload = ByteArray(payloadLength)
        readFully(input, payload)
        return Packet(header, payload)
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw EOFException("incomplete usbmux packet")
            if (count == 0) {
                val single = input.read()
                if (single < 0) throw EOFException("incomplete usbmux packet")
                target[offset++] = single.toByte()
            } else {
                offset += count
            }
        }
    }
}
