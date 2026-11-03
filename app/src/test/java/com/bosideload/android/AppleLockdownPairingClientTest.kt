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
            response(mapOf("Request" to "GetValue", "Value" to publicKeyPem())) +
                response(mapOf("Request" to "Pair", "EscrowBag" to escrow))
        )
        val output = ByteArrayOutputStream()
        var persisted: PairRecord? = null
        val client = AppleLockdownPairingClient { persisted = it }

        val record = client.pair(
            LockdownSessionChannel(input, output),
            deviceId = "TEST-UDID",
            systemBuid = "11111111-2222-3333-4444-555555555555",
            hostId = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
        )

        assertNotNull(persisted)
        assertEquals("TEST-UDID", record.deviceId)
        assertEquals("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", record.hostId)
        assertArrayEquals(escrow, record.escrowBag)
        assertArrayEquals(record.hostKeyMaterial, persisted!!.hostKeyMaterial)
        assertEquals(2, countLockdownRequests(output.toByteArray()))
    }

    @Test
    fun doesNotPersistWhenDeviceRejectsDevicePublicKeyRequest() {
        val input = ByteArrayInputStream(
            response(mapOf("Request" to "GetValue", "Error" to "InvalidRequest"))
        )
        var persisted = false
        val client = AppleLockdownPairingClient { persisted = true }

        assertThrows(IOException::class.java) {
            client.pair(
                LockdownSessionChannel(input, ByteArrayOutputStream()),
                "TEST-UDID",
                "11111111-2222-3333-4444-555555555555",
                "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
            )
        }
        assertEquals(false, persisted)
    }

    @Test
    fun doesNotPersistWhenDeviceRejectsPairRequest() {
        val input = ByteArrayInputStream(
            response(mapOf("Request" to "GetValue", "Value" to publicKeyPem())) +
                response(mapOf("Request" to "Pair", "Error" to "PairingProhibited"))
        )
        var persisted = false
        val client = AppleLockdownPairingClient { persisted = true }

        assertThrows(IOException::class.java) {
            client.pair(
                LockdownSessionChannel(input, ByteArrayOutputStream()),
                "TEST-UDID",
                "11111111-2222-3333-4444-555555555555",
                "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
            )
        }
        assertEquals(false, persisted)
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
