package com.bosideload.android

import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class PairedServiceTlsPinningTest {
    private fun certificate(pem: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(pem.inputStream()) as X509Certificate

    private fun pairingMaterial(): ApplePairingMaterialGenerator.Material {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val deviceKey = generator.generateKeyPair()
        val writer = StringWriter()
        JcaPEMWriter(writer).use { it.writeObject(deviceKey.public) }
        return ApplePairingMaterialGenerator.generate(
            writer.toString().toByteArray(StandardCharsets.US_ASCII)
        )
    }

    @Test
    fun acceptsPinnedDeviceCertificateAsThePeerLeaf() {
        val material = pairingMaterial()
        val device = certificate(material.deviceCertificatePem)
        val root = certificate(material.rootCertificatePem)

        verifyPinnedServicePeerCertificate(arrayOf(device, root), device)
    }

    @Test
    fun rejectsPinnedDeviceCertificateWhenItAppearsOnlyAfterAnUntrustedLeaf() {
        val material = pairingMaterial()
        val device = certificate(material.deviceCertificatePem)
        val host = certificate(material.hostCertificatePem)

        assertThrows(java.security.cert.CertificateException::class.java) {
            verifyPinnedServicePeerCertificate(arrayOf(host, device), device)
        }
    }

    @Test
    fun rejectsEmptyPeerChain() {
        val material = pairingMaterial()
        val device = certificate(material.deviceCertificatePem)

        assertThrows(java.security.cert.CertificateException::class.java) {
            verifyPinnedServicePeerCertificate(emptyArray(), device)
        }
    }
}
