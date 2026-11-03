package com.bosideload.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

class LockdownPacketCodecTest {
    @Test
    fun encodesLengthAsBigEndian() {
        val encoded = LockdownPacketCodec.encode(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(0, 0, 0, 3, 1, 2, 3), encoded)
    }

    @Test
    fun readsAndWritesPayload() {
        val payload = "lockdown-plist".toByteArray()
        val output = ByteArrayOutputStream()
        LockdownPacketCodec.write(output, payload)
        assertArrayEquals(payload, LockdownPacketCodec.read(ByteArrayInputStream(output.toByteArray())))
    }

    @Test
    fun supportsEmptyPayload() {
        val encoded = LockdownPacketCodec.encode(byteArrayOf())
        assertEquals(4, encoded.size)
        assertArrayEquals(byteArrayOf(), LockdownPacketCodec.read(ByteArrayInputStream(encoded)))
    }

    @Test
    fun rejectsPayloadOverConfiguredLimitBeforeAllocation() {
        val header = byteArrayOf(0, 0, 0, 65)
        assertThrows(IllegalArgumentException::class.java) {
            LockdownPacketCodec.read(ByteArrayInputStream(header), maxPayloadSize = 64)
        }
    }

    @Test
    fun rejectsNegativeLength() {
        val header = byteArrayOf(-1, -1, -1, -1)
        assertThrows(IllegalArgumentException::class.java) {
            LockdownPacketCodec.read(ByteArrayInputStream(header))
        }
    }

    @Test
    fun rejectsIncompleteHeaderAndPayload() {
        assertThrows(EOFException::class.java) {
            LockdownPacketCodec.read(ByteArrayInputStream(byteArrayOf(0, 0)))
        }
        assertThrows(EOFException::class.java) {
            LockdownPacketCodec.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 2, 9)))
        }
    }

    @Test
    fun supportsStreamsReturningSmallChunks() {
        val payload = ByteArray(64) { it.toByte() }
        val encoded = LockdownPacketCodec.encode(payload)
        val chunked = object : InputStream() {
            private val source = ByteArrayInputStream(encoded)
            override fun read(): Int = source.read()
            override fun read(target: ByteArray, offset: Int, length: Int): Int =
                source.read(target, offset, minOf(length, 2))
        }
        assertArrayEquals(payload, LockdownPacketCodec.read(chunked))
    }
}
