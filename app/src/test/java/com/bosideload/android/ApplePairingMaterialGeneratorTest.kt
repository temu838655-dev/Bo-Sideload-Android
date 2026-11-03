package com.bosideload.android

import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Security
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

        assertEquals(true, root.basicConstraints >= 0)
        assertEquals(-1, host.basicConstraints)
        assertEquals(-1, device.basicConstraints)
        root.verify(root.publicKey)
        host.verify(root.publicKey)
        device.verify(root.publicKey)
        assertArrayEquals(devicePair.public.encoded, device.publicKey.encoded)

        val hostPrivate = parsePrivateKey(material.hostPrivateKeyPem)
        val rootPrivate = parsePrivateKey(material.rootPrivateKeyPem)
        assertArrayEquals(host.publicKey.encoded, derivePublicKey(hostPrivate, "host").encoded)
        assertArrayEquals(root.publicKey.encoded, derivePublicKey(rootPrivate, "root").encoded)
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

    private fun parsePrivateKey(bytes: ByteArray): java.security.PrivateKey {
        val parsed = PEMParser(StringReader(String(bytes, StandardCharsets.US_ASCII))).use {
            it.readObject()
        }
        return JcaPEMKeyConverter().setProvider("BC").getPrivateKey(parsed as org.bouncycastle.asn1.pkcs.PrivateKeyInfo)
    }

    private fun derivePublicKey(privateKey: java.security.PrivateKey, label: String): java.security.PublicKey {
        // Verify the matching public/private key pair by signing and verifying a challenge.
        val signature = java.security.Signature.getInstance("SHA256withRSA")
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val unrelated = keyPairGenerator.generateKeyPair()
        signature.initSign(privateKey)
        signature.update(label.toByteArray())
        val signed = signature.sign()
        signature.initVerify(if (label == "host") parseCertificate(lastHostCertificate).publicKey else parseCertificate(lastRootCertificate).publicKey)
        signature.update(label.toByteArray())
        assertTrue(signature.verify(signed))
        return if (label == "host") parseCertificate(lastHostCertificate).publicKey else parseCertificate(lastRootCertificate).publicKey
    }

    private var lastHostCertificate: ByteArray = byteArrayOf()
    private var lastRootCertificate: ByteArray = byteArrayOf()
}
