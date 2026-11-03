package com.bosideload.android

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertThrows
import org.junit.Test

class PairedHostIdentityValidatorTest {
    @Test
    fun acceptsPrivateKeyThatMatchesHostCertificate() {
        val host = createIdentity()
        verifyPairedHostPrivateKeyMatchesCertificate(host.privateKey, host.certificate)
    }

    @Test
    fun rejectsPrivateKeyThatDoesNotMatchHostCertificate() {
        val host = createIdentity()
        val unrelated = createIdentity()

        assertThrows(java.io.IOException::class.java) {
            verifyPairedHostPrivateKeyMatchesCertificate(unrelated.privateKey, host.certificate)
        }
    }

    private data class TestIdentity(
        val privateKey: PrivateKey,
        val certificate: X509Certificate
    )

    private fun createIdentity(): TestIdentity {
        val generator = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }
        val pair: KeyPair = generator.generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=Bo Sideload TLS Test")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now).abs(),
            Date(now - 60_000),
            Date(now + 86_400_000),
            subject,
            pair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        certificate.verify(pair.public)
        return TestIdentity(pair.private, certificate)
    }
}
