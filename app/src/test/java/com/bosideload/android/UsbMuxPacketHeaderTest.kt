package com.bosideload.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UsbMuxPacketHeaderTest {
    @Test
    fun encodesAndDecodesLittleEndianHeader() {
        val bytes = UsbMuxPacketHeader.encode(
            message = UsbMuxPacketHeader.MESSAGE_PLIST,
            tag = 0x1234,
            payloadSize = 20
        )
        val decoded = UsbMuxPacketHeader.decode(bytes)
        assertEquals(36L, decoded.length)
        assertEquals(1L, decoded.version)
        assertEquals(8L, decoded.message)
        assertEquals(0x1234L, decoded.tag)
    }

    @Test
    fun rejectsShortHeader() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbMuxPacketHeader.decode(ByteArray(15))
        }
    }

    @Test
    fun rejectsLengthSmallerThanHeader() {
        val bytes = UsbMuxPacketHeader.encode(8, 1, 0).also {
            it[0] = 15
        }
        assertThrows(IllegalArgumentException::class.java) {
            UsbMuxPacketHeader.decode(bytes)
        }
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val bytes = UsbMuxPacketHeader.encode(8, 1, 0).also {
            it[4] = 2
        }
        assertThrows(IllegalArgumentException::class.java) {
            UsbMuxPacketHeader.decode(bytes)
        }
    }

    @Test
    fun rejectsPacketOverConfiguredLimit() {
        val bytes = UsbMuxPacketHeader.encode(8, 1, 100)
        assertThrows(IllegalArgumentException::class.java) {
            UsbMuxPacketHeader.decode(bytes, maxPacketSize = 64)
        }
    }
}
