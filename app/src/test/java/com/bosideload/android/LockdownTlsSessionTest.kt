package com.bosideload.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator

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
    fun rejectsMismatchedHostPrivateKeyBeforeStartSessionTlsHandshake() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val deviceKey = generator.generateKeyPair()
        val publicKeyPem = StringWriter().also { writer ->
            org.bouncycastle.openssl.jcajce.JcaPEMWriter(writer).use {
                it.writeObject(deviceKey.public)
            }
        }.toString().toByteArray(StandardCharsets.US_ASCII)

        val validMaterial = ApplePairingMaterialGenerator.generate(publicKeyPem)
        val unrelatedMaterial = ApplePairingMaterialGenerator.generate(publicKeyPem)
        val mismatchedRecord = PairRecord(
            deviceId = "device",
            hostId = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE",
            systemBuid = "11111111-2222-3333-4444-555555555555",
            hostCertificate = validMaterial.hostCertificatePem,
            hostKeyMaterial = unrelatedMaterial.hostPrivateKeyPem,
            deviceCertificate = validMaterial.deviceCertificatePem,
            rootCertificate = validMaterial.rootCertificatePem,
            rootKeyMaterial = validMaterial.rootPrivateKeyPem
        )
        val input = ByteArrayInputStream(
            reply(
                "Request" to "StartSession",
                "SessionID" to "session-1",
                "EnableSessionSSL" to true
            )
        )
        val channel = LockdownSessionChannel(input, ByteArrayOutputStream())

        val error = assertThrows(IOException::class.java) {
            LockdownTlsSession.start(channel, Socket(), mismatchedRecord)
        }
        val causeMessages = generateSequence<Throwable>(error) { it.cause }
            .mapNotNull { it.message }
            .toList()
        assertEquals(
            true,
            causeMessages.contains("Paired host private key does not match HostCertificate")
        )
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
