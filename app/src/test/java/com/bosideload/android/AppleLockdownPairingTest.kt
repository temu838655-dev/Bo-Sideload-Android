package com.bosideload.android

import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator

class AppleLockdownPairingTest {
    private fun devicePublicKeyPem(): ByteArray {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        val writer = StringWriter()
        JcaPEMWriter(writer).use { it.writeObject(pair.public) }
        return writer.toString().toByteArray(StandardCharsets.US_ASCII)
    }

    @Test
    fun prepareBuildsPairRequestForTheSelectedDevice() {
        val pending = AppleLockdownPairing.prepare(
            deviceId = "USB-DEVICE-7",
            devicePublicKeyPem = devicePublicKeyPem(),
            systemBuid = "SYSTEM-BUID",
            hostId = "HOST-ID"
        )
        val request = LockdownPlistCodec.decode(pending.requestPayload)
        assertEquals("Pair", request["Request"])
        val pairRecord = request["PairRecord"] as Map<*, *>
        assertEquals("HOST-ID", pairRecord["HostID"])
        assertEquals("SYSTEM-BUID", pairRecord["SystemBUID"])
        assertTrue((pairRecord["DeviceCertificate"] as ByteArray).isNotEmpty())
        assertTrue((pairRecord["HostCertificate"] as ByteArray).isNotEmpty())
        assertTrue((pairRecord["RootCertificate"] as ByteArray).isNotEmpty())
    }

    @Test
    fun prepareRequiresAnExplicitStableHostId() {
        assertTrue(runCatching {
            AppleLockdownPairing.prepare(
                deviceId = "USB-DEVICE",
                devicePublicKeyPem = byteArrayOf(1),
                systemBuid = "SYSTEM-BUID",
                hostId = " "
            )
        }.isFailure)
    }

    @Test
    fun prepareRejectsBlankDeviceId() {
        assertTrue(runCatching {
            AppleLockdownPairing.prepare(
                deviceId = " ",
                devicePublicKeyPem = byteArrayOf(1),
                systemBuid = "SYSTEM-BUID",
                hostId = "HOST-ID"
            )
        }.isFailure)
    }

    @Test
    fun prepareRejectsBlankSystemBuid() {
        assertTrue(runCatching {
            AppleLockdownPairing.prepare(
                deviceId = "USB-DEVICE",
                devicePublicKeyPem = byteArrayOf(1),
                systemBuid = " ",
                hostId = "HOST-ID"
            )
        }.isFailure)
    }

    @Test
    fun completePersistsOnlyAfterSuccessfulPairResponse() {
        val pending = AppleLockdownPairing.prepare(
            "USB-DEVICE-8",
            devicePublicKeyPem(),
            "SYSTEM-BUID",
            "HOST-ID"
        )
        val escrow = byteArrayOf(8, 6, 7, 5, 3, 0, 9)
        val response = LockdownResponseParser.parse(
            LockdownPlistCodec.encode(mapOf("Request" to "Pair", "EscrowBag" to escrow))
        )
        var persisted: PairRecord? = null

        val record = AppleLockdownPairing.complete(pending, response) { persisted = it }

        assertNotNull(persisted)
        assertEquals("USB-DEVICE-8", record.deviceId)
        assertEquals("HOST-ID", record.hostId)
        assertEquals("SYSTEM-BUID", record.systemBuid)
        assertArrayEquals(escrow, record.escrowBag)
        assertArrayEquals(
            pending.material.hostPrivateKeyPem,
            record.hostKeyMaterial
        )
        assertArrayEquals(
            pending.material.rootPrivateKeyPem,
            record.rootKeyMaterial
        )
        assertArrayEquals(record.hostKeyMaterial, persisted!!.hostKeyMaterial)
    }

    @Test
    fun completeNeverPersistsLockdownFailure() {
        val pending = AppleLockdownPairing.prepare(
            "USB-DEVICE-9",
            devicePublicKeyPem(),
            "SYSTEM-BUID",
            "HOST-ID"
        )
        val response = LockdownResponseParser.parse(
            LockdownPlistCodec.encode(mapOf(
                "Request" to "Pair",
                "Error" to "PairingProhibited"
            ))
        )
        var persisted = false

        assertTrue(runCatching {
            AppleLockdownPairing.complete(pending, response) { persisted = true }
        }.isFailure)
        assertFalse(persisted)
    }

    @Test
    fun completeRejectsAResponseForAnotherRequest() {
        val pending = AppleLockdownPairing.prepare(
            "USB-DEVICE-10",
            devicePublicKeyPem(),
            "SYSTEM-BUID",
            "HOST-ID"
        )
        val response = LockdownResponseParser.parse(
            LockdownPlistCodec.encode(mapOf("Request" to "QueryType", "Value" to "lockdown"))
        )
        assertTrue(runCatching {
            AppleLockdownPairing.complete(pending, response) { fail("must not persist") }
        }.isFailure)
    }
}
