package com.bosideload.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket

class LockdownTlsSessionTest {
    private fun reply(vararg fields: Pair<String, Any?>): ByteArray =
        LockdownPacketCodec.encode(
            LockdownPlistCodec.encode(linkedMapOf(*fields))
        )

    private fun record() = PairRecord(
        deviceId = "device",
        hostId = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE",
        systemBuid = "11111111-2222-3333-4444-555555555555",
        hostCertificate = byteArrayOf(1),
        hostKeyMaterial = byteArrayOf(2),
        deviceCertificate = byteArrayOf(3),
        rootCertificate = byteArrayOf(4),
        rootKeyMaterial = byteArrayOf(5)
    )

    @Test
    fun refusesTlsUpgradeWhenStartSessionDoesNotEnableSsl() {
        val input = ByteArrayInputStream(
            reply(
                "Request" to "StartSession",
                "SessionID" to "session-1",
                "EnableSessionSSL" to false
            )
        )
        val channel = LockdownSessionChannel(input, ByteArrayOutputStream())
        assertThrows(IOException::class.java) {
            LockdownTlsSession.start(channel, Socket(), record())
        }
    }

    @Test
    fun refusesTlsUpgradeWhenSessionIdIsMissing() {
        val input = ByteArrayInputStream(
            reply(
                "Request" to "StartSession",
                "EnableSessionSSL" to true
            )
        )
        val channel = LockdownSessionChannel(input, ByteArrayOutputStream())
        assertThrows(IOException::class.java) {
            LockdownTlsSession.start(channel, Socket(), record())
        }
    }
}
