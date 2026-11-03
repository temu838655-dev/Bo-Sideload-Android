package com.bosideload.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

class UsbMuxPacketCodecTest {
    @Test
    fun roundTripsPacketWithPayload() {
        val payload = "plist-payload".toByteArray()
        val encoded = UsbMuxPacketCodec.encode(UsbMuxPacketHeader.MESSAGE_PLIST, 77, payload)
        val packet = UsbMuxPacketCodec.read(ByteArrayInputStream(encoded))

        assertEquals(UsbMuxPacketHeader.MESSAGE_PLIST, packet.header.message)
        assertEquals(77L, packet.header.tag)
        assertArrayEquals(payload, packet.payload)
    }

    @Test
    fun readsPacketsFromAnInputStreamThatReturnsSmallChunks() {
        val payload = ByteArray(128) { it.toByte() }
        val encoded = UsbMuxPacketCodec.encode(8, 5, payload)
        val chunked = object : InputStream() {
            private val source = ByteArrayInputStream(encoded)
            override fun read(): Int = source.read()
            override fun read(target: ByteArray, offset: Int, length: Int): Int =
                source.read(target, offset, minOf(length, 3))
        }

        assertArrayEquals(payload, UsbMuxPacketCodec.read(chunked).payload)
    }

    @Test
    fun rejectsIncompleteHeaderAndPayload() {
        assertThrows(EOFException::class.java) {
            UsbMuxPacketCodec.read(ByteArrayInputStream(ByteArray(8)))
        }
        val packet = UsbMuxPacketCodec.encode(8, 1, byteArrayOf(1, 2, 3))
        assertThrows(EOFException::class.java) {
            UsbMuxPacketCodec.read(ByteArrayInputStream(packet.copyOf(packet.size - 1)))
        }
    }

    @Test
    fun rejectsOversizedPacketBeforeReadingPayload() {
        val encoded = UsbMuxPacketCodec.encode(8, 1, ByteArray(100))
        assertThrows(IllegalArgumentException::class.java) {
            UsbMuxPacketCodec.read(ByteArrayInputStream(encoded), maxPacketSize = 64)
        }
    }

    @Test
    fun writesHeaderAndPayloadAsOnePacket() {
        val output = ByteArrayOutputStream()
        val payload = byteArrayOf(4, 5, 6)
        UsbMuxPacketCodec.write(output, 8, 9, payload)

        val packet = UsbMuxPacketCodec.read(ByteArrayInputStream(output.toByteArray()))
        assertEquals(9L, packet.header.tag)
        assertArrayEquals(payload, packet.payload)
    }
}
