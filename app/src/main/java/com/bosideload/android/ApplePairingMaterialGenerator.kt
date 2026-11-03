package com.bosideload.android

import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.RSAPublicKey
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.StringReader
import java.io.StringWriter
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.util.Date

/**
 * Creates the certificate material used by Apple's normal Lockdown Pair flow.
 *
 * The device public key must be read from the connected device's Lockdown
 * DevicePublicKey value. This class does not send Pair or bypass the trust UI.
 * Private keys are returned only so the caller can immediately encrypt them
 * inside EncryptedPairRecordStore after a successful Pair exchange.
 */
internal object ApplePairingMaterialGenerator {
    data class Material(
        val rootCertificatePem: ByteArray,
        val rootPrivateKeyPem: ByteArray,
        val hostCertificatePem: ByteArray,
        val hostPrivateKeyPem: ByteArray,
        val deviceCertificatePem: ByteArray
    )

    fun generate(devicePublicKeyPem: ByteArray, now: Date = Date()): Material {
        require(devicePublicKeyPem.isNotEmpty()) { "DevicePublicKey must not be empty" }
        require(devicePublicKeyPem.size <= MAX_DEVICE_PUBLIC_KEY_BYTES) { "DevicePublicKey is too large" }
        ensureProvider()

        val devicePublicKey = parseDevicePublicKey(devicePublicKeyPem)
        val generator = KeyPairGenerator.getInstance("RSA", PROVIDER)
        generator.initialize(RSA_BITS, SecureRandom())
        val rootPair = generator.generateKeyPair()
        val hostPair = generator.generateKeyPair()

        val notBefore = Date(now.time - CLOCK_SKEW_MS)
        val notAfter = Date(now.time + CERT_VALIDITY_MS)
        val rootName = X500Name("CN=Bo Sideload Pairing Root")
        val hostName = X500Name("CN=Bo Sideload Host")
        val deviceName = X500Name("CN=Apple Device")

        val rootHolder = certificate(
            subject = rootName,
            issuer = rootName,
            publicKey = rootPair,
            issuerKey = rootPair,
            notBefore = notBefore,
            notAfter = notAfter,
            isCa = true,
            includeKeyUsage = false
        )
        val hostHolder = certificate(
            subject = hostName,
            issuer = rootName,
            publicKey = hostPair,
            issuerKey = rootPair,
            notBefore = notBefore,
            notAfter = notAfter,
            isCa = false,
            includeKeyUsage = true
        )
        val deviceHolder = certificate(
            subject = deviceName,
            issuer = rootName,
            publicKey = devicePublicKey,
            issuerKey = rootPair,
            notBefore = notBefore,
            notAfter = notAfter,
            isCa = false,
            includeKeyUsage = true
        )

        val converter = JcaX509CertificateConverter().setProvider(PROVIDER)
        val rootCert = converter.getCertificate(rootHolder)
        val hostCert = converter.getCertificate(hostHolder)
        val deviceCert = converter.getCertificate(deviceHolder)
        rootCert.verify(rootPair.public)
        hostCert.verify(rootCert.publicKey)
        deviceCert.verify(rootCert.publicKey)

        return Material(
            rootCertificatePem = pem(rootCert),
            rootPrivateKeyPem = pem(rootPair.private),
            hostCertificatePem = pem(hostCert),
            hostPrivateKeyPem = pem(hostPair.private),
            deviceCertificatePem = pem(deviceCert)
        )
    }

    private fun certificate(
        subject: X500Name,
        issuer: X500Name,
        publicKey: PublicKey,
        issuerKey: KeyPair,
        notBefore: Date,
        notAfter: Date,
        isCa: Boolean,
        includeKeyUsage: Boolean
    ): X509CertificateHolder {
        val builder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger(159, SecureRandom()).abs().add(BigInteger.ONE),
            notBefore,
            notAfter,
            subject,
            publicKey
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(isCa))
        if (includeKeyUsage) {
            builder.addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
            )
        }
        return builder.build(
            JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                .setProvider(PROVIDER)
                .build(issuerKey.private)
        )
    }

    private fun parseDevicePublicKey(pemBytes: ByteArray): PublicKey {
        val text = String(pemBytes, StandardCharsets.US_ASCII)
        PEMParser(StringReader(text)).use { parser ->
            val parsed = parser.readObject()
                ?: throw IllegalArgumentException("DevicePublicKey is not a PEM public key")
            val converter = JcaPEMKeyConverter().setProvider(PROVIDER)
            return when (parsed) {
                is org.bouncycastle.asn1.x509.SubjectPublicKeyInfo -> converter.getPublicKey(parsed)
                is org.bouncycastle.openssl.PEMKeyPair -> converter.getKeyPair(parsed).public
                is RSAPublicKey -> converter.getPublicKey(
                    org.bouncycastle.asn1.x509.SubjectPublicKeyInfo(
                        AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE),
                        parsed
                    )
                )
                else -> throw IllegalArgumentException("unsupported DevicePublicKey PEM object")
            }
        }
    }

    private fun pem(value: Any): ByteArray {
        val writer = StringWriter()
        JcaPEMWriter(writer).use { it.writeObject(value) }
        return writer.toString().toByteArray(StandardCharsets.US_ASCII)
    }

    private fun ensureProvider() {
        if (Security.getProvider(PROVIDER) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private const val PROVIDER = "BC"
    private const val RSA_BITS = 2048
    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    private const val MAX_DEVICE_PUBLIC_KEY_BYTES = 64 * 1024
    private const val CLOCK_SKEW_MS = 5 * 60 * 1000L
    private const val CERT_VALIDITY_MS = 10L * 365 * 24 * 60 * 60 * 1000
}
