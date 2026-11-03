package com.bosideload.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AfcPacketCodecTest {
    @Test fun encodesLittleEndianAfcHeaderAndSeparatedPayload() {
        val packet = AfcPacketCodec.encode(
            packetNumber = 7,
            operation = 0x10,
            data = byteArrayOf(1, 2, 3),
            payload = byteArrayOf(4, 5)
        )
        assertEquals(45, ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getLong(8).toInt())
        assertEquals(43, ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getLong(16).toInt())
        assertEquals(7L, ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getLong(24))
        assertEquals(0x10L, ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getLong(32))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), packet.copyOfRange(40, 45))
    }

    @Test fun rejectsWrongMagicAndInvalidLengths() {
        val packet = AfcPacketCodec.encode(1, 1)
        packet[0] = 0
        assertThrows(IllegalArgumentException::class.java) {
            AfcPacketCodec.decodeHeader(packet.copyOfRange(0, 40))
        }
        val invalid = AfcPacketCodec.encode(1, 1)
        ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putLong(8, 39)
        assertThrows(IllegalArgumentException::class.java) {
            AfcPacketCodec.decodeHeader(invalid.copyOfRange(0, 40))
        }
    }

    @Test fun decodesHeaderFields() {
        val packet = AfcPacketCodec.encode(9, 0x0E, byteArrayOf(8))
        val header = AfcPacketCodec.decodeHeader(packet.copyOfRange(0, 40))
        assertEquals(49L, header.entireLength)
        assertEquals(41L, header.thisLength)
        assertEquals(9L, header.packetNumber)
        assertEquals(0x0EL, header.operation)
    }
}
