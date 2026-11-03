package com.bosideload.android

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Date

class ApplePairingMaterialGeneratorTest {
    @Test
    fun generatesRootHostAndDeviceCertificatesFromDevicePublicKey() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val devicePair = generator.generateKeyPair()
        val publicKeyPem = StringWriter().also { writer ->
            org.bouncycastle.openssl.jcajce.JcaPEMWriter(writer).use { it.writeObject(devicePair.public) }
        }.toString().toByteArray(StandardCharsets.US_ASCII)

        val material = ApplePairingMaterialGenerator.generate(publicKeyPem, Date(1_800_000_000_000L))
        val root = parseCertificate(material.rootCertificatePem)
        val host = parseCertificate(material.hostCertificatePem)
        val device = parseCertificate(material.deviceCertificatePem)

        assertTrue(root.basicConstraints >= 0)
        assertEquals(-1, host.basicConstraints)
        assertEquals(-1, device.basicConstraints)
        root.verify(root.publicKey)
        host.verify(root.publicKey)
        device.verify(root.publicKey)
        assertArrayEquals(devicePair.public.encoded, device.publicKey.encoded)

        assertPrivateKeyMatches(material.rootPrivateKeyPem, root.publicKey)
        assertPrivateKeyMatches(material.hostPrivateKeyPem, host.publicKey)
    }

    @Test
    fun createsPairRecordOnlyFromSuccessfulPairReplyAndPreservesEscrowBag() {
        val deviceGenerator = KeyPairGenerator.getInstance("RSA")
        deviceGenerator.initialize(2048)
        val devicePair = deviceGenerator.generateKeyPair()
        val publicKeyPem = StringWriter().also { writer ->
            org.bouncycastle.openssl.jcajce.JcaPEMWriter(writer).use { it.writeObject(devicePair.public) }
        }.toString().toByteArray(StandardCharsets.US_ASCII)
        val material = ApplePairingMaterialGenerator.generate(publicKeyPem)
        val escrow = byteArrayOf(8, 6, 7, 5, 3, 0, 9)
        val response = LockdownResponseParser.parse(
            LockdownPlistCodec.encode(mapOf("Request" to "Pair", "EscrowBag" to escrow))
        )

        val record = material.toPairRecordAfterSuccessfulPair(
            "DEVICE-UDID", "HOST-ID", "SYSTEM-BUID", response
        )
        assertEquals("DEVICE-UDID", record.deviceId)
        assertArrayEquals(escrow, record.escrowBag)
        assertArrayEquals(material.hostPrivateKeyPem, record.hostKeyMaterial)
        assertArrayEquals(material.rootPrivateKeyPem, record.rootKeyMaterial)

        val failed = LockdownResponseParser.parse(
            LockdownPlistCodec.encode(mapOf("Request" to "Pair", "Error" to "PairingProhibited"))
        )
        assertThrows(IllegalArgumentException::class.java) {
            material.toPairRecordAfterSuccessfulPair("D", "H", "B", failed)
        }
    }

    @Test
    fun rejectsEmptyAndNonPemDevicePublicKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            ApplePairingMaterialGenerator.generate(byteArrayOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ApplePairingMaterialGenerator.generate("not a public key".toByteArray())
        }
    }

    private fun parseCertificate(bytes: ByteArray): java.security.cert.X509Certificate {
        val holder = PEMParser(StringReader(String(bytes, StandardCharsets.US_ASCII))).use {
            it.readObject() as X509CertificateHolder
        }
        return JcaX509CertificateConverter().setProvider("BC").getCertificate(holder)
    }

    private fun assertPrivateKeyMatches(
        privateKeyPem: ByteArray,
        publicKey: java.security.PublicKey
    ) {
        val parsed = PEMParser(StringReader(String(privateKeyPem, StandardCharsets.US_ASCII))).use {
            it.readObject()
        }
        val converter = JcaPEMKeyConverter().setProvider("BC")
        val privateKey = when (parsed) {
            is PrivateKeyInfo -> converter.getPrivateKey(parsed)
            is org.bouncycastle.openssl.PEMKeyPair -> converter.getKeyPair(parsed).private
            else -> throw AssertionError("unexpected private key PEM object: ${parsed?.javaClass?.name}")
        }
        val challenge = "Bo Sideload pairing key check".toByteArray(StandardCharsets.UTF_8)
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(challenge)
        val signed = signer.sign()
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(challenge)
        assertTrue(verifier.verify(signed))
    }
}
