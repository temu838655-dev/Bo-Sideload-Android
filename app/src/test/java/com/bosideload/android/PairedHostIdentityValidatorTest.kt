package com.bosideload.android

import java.io.IOException
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

        assertThrows(IOException::class.java) {
            verifyPairedHostPrivateKeyMatchesCertificate(unrelated.privateKey, host.certificate)
        }
    }

    @Test
    fun acceptsHostAndDeviceCertificatesSignedBySavedRoot() {
        val root = createIdentity()
        val host = createIdentity(root)
        val device = createIdentity(root)

        validatePairedServiceCertificates(root.certificate, host.certificate, device.certificate)
    }

    @Test
    fun rejectsCertificatesSignedByAnotherRoot() {
        val savedRoot = createIdentity()
        val otherRoot = createIdentity()
        val host = createIdentity(otherRoot)
        val device = createIdentity(savedRoot)

        assertThrows(IOException::class.java) {
            validatePairedServiceCertificates(savedRoot.certificate, host.certificate, device.certificate)
        }
    }

    @Test
    fun rejectsExpiredDeviceCertificate() {
        val root = createIdentity()
        val host = createIdentity(root)
        val expiredDevice = createIdentity(
            issuer = root,
            notBefore = Date(System.currentTimeMillis() - 172_800_000),
            notAfter = Date(System.currentTimeMillis() - 86_400_000)
        )

        assertThrows(IOException::class.java) {
            validatePairedServiceCertificates(root.certificate, host.certificate, expiredDevice.certificate)
        }
    }

    private data class TestIdentity(
        val privateKey: PrivateKey,
        val certificate: X509Certificate
    )

    private fun createIdentity(
        issuer: TestIdentity? = null,
        notBefore: Date = Date(System.currentTimeMillis() - 60_000),
        notAfter: Date = Date(System.currentTimeMillis() + 86_400_000)
    ): TestIdentity {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val pair: KeyPair = generator.generateKeyPair()
        val issuerIdentity = issuer
        val subject = X500Name("CN=Bo Sideload TLS Test ${System.nanoTime()}")
        val issuerName = issuerIdentity?.certificate?.subjectX500Principal
            ?.let { X500Name.getInstance(it.encoded) } ?: subject
        val signingKey = issuerIdentity?.privateKey ?: pair.private
        val builder = JcaX509v3CertificateBuilder(
            issuerName,
            BigInteger.valueOf(System.nanoTime()).abs(),
            notBefore,
            notAfter,
            subject,
            pair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(signingKey)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        certificate.verify(issuerIdentity?.certificate?.publicKey ?: pair.public)
        return TestIdentity(pair.private, certificate)
    }
}
