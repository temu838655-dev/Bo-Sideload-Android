package com.bosideload.android

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AppleCsrGeneratorTest {
    @Test
    fun generatesVerifiableRsa2048CsrAndMatchingPrivateKey() {
        val material = AppleCsrGenerator.generate().material
        val privateBytes = Base64.getDecoder().decode(material.privateKeyPkcs8Base64)
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateBytes))
        val request = PEMParser(material.csrPem.reader()).use { parser ->
            parser.readObject() as PKCS10CertificationRequest
        }

        assertEquals("RSA", privateKey.algorithm)
        assertEquals(2048, (privateKey as java.security.interfaces.RSAPrivateKey).modulus.bitLength())
        assertNotNull(request.subject)
        val publicKey = java.security.KeyFactory.getInstance("RSA").generatePublic(
            java.security.spec.X509EncodedKeySpec(request.subjectPublicKeyInfo.encoded)
        )
        val verifier = JcaContentVerifierProviderBuilder().build(publicKey)
        assertEquals(true, request.isSignatureValid(verifier))
        val challenge = "Bo Sideload CSR private-key match".toByteArray()
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(challenge)
        val signature = signer.sign()
        val check = Signature.getInstance("SHA256withRSA")
        check.initVerify(publicKey)
        check.update(challenge)
        assertEquals(true, check.verify(signature))
    }
}
