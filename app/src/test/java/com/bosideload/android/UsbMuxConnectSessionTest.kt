package com.bosideload.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class UsbMuxConnectSessionTest {
    private class FakeTransport(
        private val reply: UsbMuxPacketCodec.Packet
    ) : UsbMuxTransport {
        var writtenMessage: Long? = null
        var writtenTag: Long? = null
        var writtenPayload: ByteArray? = null
        var rawInput = byteArrayOf(9, 8, 7)
        val rawOutput = ArrayList<Byte>()
        var closed = false

        override fun writePacket(message: Long, tag: Long, payload: ByteArray) {
            writtenMessage = message
            writtenTag = tag
            writtenPayload = payload
        }

        override fun readPacket(maxPacketSize: Long): UsbMuxPacketCodec.Packet = reply

        override fun writeRaw(bytes: ByteArray, offset: Int, length: Int) {
            for (i in offset until offset + length) rawOutput += bytes[i]
        }

        override fun readRaw(target: ByteArray, offset: Int, length: Int): Int {
            if (rawInput.isEmpty()) return -1
            val count = minOf(length, rawInput.size)
            rawInput.copyInto(target, offset, 0, count)
            rawInput = rawInput.copyOfRange(count, rawInput.size)
            return count
        }

        override fun close() { closed = true }
    }

    private fun reply(tag: Long = 41L, number: Long = 0L) =
        UsbMuxPacketCodec.read(
            UsbMuxPacketCodec.encode(
                UsbMuxPacketHeader.MESSAGE_PLIST,
                tag,
                LockdownPlistCodec.encode(mapOf("MessageType" to "Result", "Number" to number))
            ).inputStream()
        )

    @Test
    fun successfulConnectSwitchesToRawByteStreams() {
        val transport = FakeTransport(reply())
        val session = UsbMuxConnectSession.connect(transport, 12L, tag = 41L)

        assertEquals(UsbMuxPacketHeader.MESSAGE_PLIST, transport.writtenMessage)
        assertEquals(41L, transport.writtenTag)
        val request = LockdownPlistCodec.decode(transport.writtenPayload!!)
        assertEquals("Connect", request["MessageType"])
        assertEquals(12L, request["DeviceID"])
        assertEquals(UsbMuxPlistRequests.portToNetworkOrder(62078).toLong(), request["PortNumber"])

        val read = ByteArray(2)
        assertEquals(2, session.inputStream.read(read))
        assertArrayEquals(byteArrayOf(9, 8), read)
        session.outputStream.write(byteArrayOf(1, 2, 3))
        assertEquals(listOf<Byte>(1, 2, 3), transport.rawOutput)
        session.close()
        assertEquals(true, transport.closed)
    }

    @Test
    fun rejectsNonZeroConnectResultAndClosesTransport() {
        val transport = FakeTransport(reply(number = 3L))
        assertThrows(IOException::class.java) {
            UsbMuxConnectSession.connect(transport, 12L, tag = 41L)
        }
        assertEquals(true, transport.closed)
    }

    @Test
    fun rejectsMismatchedTagAndClosesTransport() {
        val transport = FakeTransport(reply(tag = 42L))
        assertThrows(IOException::class.java) {
            UsbMuxConnectSession.connect(transport, 12L, tag = 41L)
        }
        assertEquals(true, transport.closed)
    }
}
