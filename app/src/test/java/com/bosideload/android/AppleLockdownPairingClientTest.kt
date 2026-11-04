package com.bosideload.android

import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringWriter
import java.security.KeyPairGenerator

class AppleLockdownPairingClientTest {
    private fun response(values: Map<String, Any?>): ByteArray =
        LockdownPacketCodec.encode(LockdownPlistCodec.encode(values))

    private fun publicKeyPem(): ByteArray {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        val writer = StringWriter()
        JcaPEMWriter(writer).use { it.writeObject(pair.public) }
        return writer.toString().toByteArray(Charsets.US_ASCII)
    }

    @Test
    fun readsDevicePublicKeyPairsAndPersistsOnlyAfterPairSuccess() {
        val escrow = byteArrayOf(4, 5, 6)
        val input = ByteArrayInputStream(
            response(mapOf("Request" to "QueryType", "Type" to "com.apple.mobile.lockdown")) +
            response(mapOf("Request" to "GetValue", "Value" to "00008030-001C195E0E91802E")) +
                response(mapOf("Request" to "GetValue", "Value" to publicKeyPem())) +
                response(mapOf("Request" to "Pair", "EscrowBag" to escrow))
        )
        val output = ByteArrayOutputStream()
        var persisted: PairRecord? = null
        val client = AppleLockdownPairingClient { persisted = it }

        val record = client.pair(
            LockdownSessionChannel(input, output),
            deviceId = "00008030-001C195E0E91802E",
            systemBuid = "11111111-2222-3333-4444-555555555555",
            hostId = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
        )

        assertNotNull(persisted)
        assertEquals("00008030-001C195E0E91802E", record.deviceId)
        assertEquals("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", record.hostId)
        assertArrayEquals(escrow, record.escrowBag)
        assertArrayEquals(record.hostKeyMaterial, persisted!!.hostKeyMaterial)
        assertEquals(4, countLockdownRequests(output.toByteArray()))
    }

    @Test
    fun doesNotPersistWhenDeviceRejectsDevicePublicKeyRequest() {
        val input = ByteArrayInputStream(
            response(mapOf("Request" to "QueryType", "Type" to "com.apple.mobile.lockdown")) +
            response(mapOf("Request" to "GetValue", "Error" to "InvalidRequest"))
        )
        var persisted = false
        val client = AppleLockdownPairingClient { persisted = true }

        assertThrows(IOException::class.java) {
            client.pair(
                LockdownSessionChannel(input, ByteArrayOutputStream()),
                "00008030-001C195E0E91802E",
                "11111111-2222-3333-4444-555555555555",
                "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
            )
        }
        assertEquals(false, persisted)
    }

    @Test
    fun doesNotPersistWhenDeviceRejectsPairRequest() {
        val input = ByteArrayInputStream(
            response(mapOf("Request" to "QueryType", "Type" to "com.apple.mobile.lockdown")) +
            response(mapOf("Request" to "GetValue", "Value" to "00008030-001C195E0E91802E")) +
                response(mapOf("Request" to "GetValue", "Value" to publicKeyPem())) +
                response(mapOf("Request" to "Pair", "Error" to "PairingProhibited"))
        )
        var persisted = false
        val client = AppleLockdownPairingClient { persisted = true }

        assertThrows(IOException::class.java) {
            client.pair(
                LockdownSessionChannel(input, ByteArrayOutputStream()),
                "00008030-001C195E0E91802E",
                "11111111-2222-3333-4444-555555555555",
                "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
            )
        }
        assertEquals(false, persisted)
    }


    @Test
    fun rejectsUsbSerialThatDoesNotMatchLockdownUdid() {
        val input = ByteArrayInputStream(
            response(mapOf("Request" to "QueryType", "Type" to "com.apple.mobile.lockdown")) +
            response(mapOf("Request" to "GetValue", "Value" to "DIFFERENT-UDID-000"))
        )
        var persisted = false
        val output = ByteArrayOutputStream()
        val client = AppleLockdownPairingClient { persisted = true }

        assertThrows(IOException::class.java) {
            client.pair(
                LockdownSessionChannel(input, output),
                "USB-SERIAL-UDID-000",
                "11111111-2222-3333-4444-555555555555",
                "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
            )
        }
        assertEquals(false, persisted)
        assertEquals(2, countLockdownRequests(output.toByteArray()))
    }

    @Test
    fun rejectsNonLockdownServiceBeforeReadingDeviceIdentity() {
        val input = ByteArrayInputStream(
            response(mapOf("Request" to "QueryType", "Type" to "com.apple.mobile.installation_proxy"))
        )
        val output = ByteArrayOutputStream()
        val client = AppleLockdownPairingClient { }

        assertThrows(IOException::class.java) {
            client.pair(
                LockdownSessionChannel(input, output),
                "00008030-001C195E0E91802E",
                "11111111-2222-3333-4444-555555555555",
                "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
            )
        }
        assertEquals(1, countLockdownRequests(output.toByteArray()))
    }

    @Test
    fun validatesUdidFormatBeforePairing() {
        assertEquals(true, AppleLockdownPairingClient.isPlausibleUdid("00008030-001C195E0E91802E"))
        assertEquals(false, AppleLockdownPairingClient.isPlausibleUdid(" "))
        assertEquals(false, AppleLockdownPairingClient.isPlausibleUdid("bad/value"))
        assertEquals(false, AppleLockdownPairingClient.isPlausibleUdid("short"))
    }

    private fun countLockdownRequests(bytes: ByteArray): Int {
        val input = ByteArrayInputStream(bytes)
        var count = 0
        while (input.available() > 0) {
            val payload = LockdownPacketCodec.read(input)
            assertNotNull(LockdownPlistCodec.decode(payload)["Request"])
            count++
        }
        return count
    }
}
