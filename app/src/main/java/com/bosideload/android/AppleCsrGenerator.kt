package com.bosideload.android

import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import java.io.StringWriter
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.spec.ECGenParameterSpec

/**
 * Generates a P-256 keypair and PKCS#10 CSR locally.
 *
 * Note: Apple certificate type compatibility must be validated before using
 * this CSR with a production certificate-creation request.
 */
internal object AppleCsrGenerator {
    data class Generated(val material: AppleConnectCredentialVault.SigningMaterial)

    fun generate(commonName: String = "Bo Sideload iOS Signing"): Generated {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        val generator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair: KeyPair = generator.generateKeyPair()

        val subject = X500Name("CN=" + commonName.replace(",", " ").take(64))
        val csrBuilder = JcaPKCS10CertificationRequestBuilder(subject, pair.public)
        val signer = JcaContentSignerBuilder("SHA256withECDSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(pair.private)
        val csr = csrBuilder.build(signer)
        val output = StringWriter()
        JcaPEMWriter(output).use { it.writeObject(csr) }

        return Generated(
            AppleConnectCredentialVault.SigningMaterial(
                privateKeyPkcs8Base64 = Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP),
                publicKeyX509Base64 = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP),
                csrPem = output.toString()
            )
        )
    }
}
