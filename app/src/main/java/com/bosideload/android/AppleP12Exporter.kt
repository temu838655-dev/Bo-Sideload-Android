package com.bosideload.android

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** Builds a local PKCS#12 file from the locally-held CSR key and Apple-issued certificate. */
internal object AppleP12Exporter {
    fun export(
        material: AppleConnectCredentialVault.SigningMaterial,
        issued: AppleConnectCredentialVault.IssuedCertificate,
        password: CharArray
    ): ByteArray {
        require(password.size >= 12) { "Mật khẩu P12 cần ít nhất 12 ký tự." }
        val privateBytes = Base64.getDecoder().decode(material.privateKeyPkcs8Base64)
        val publicBytes = Base64.getDecoder().decode(material.publicKeyX509Base64)
        val certificateBytes = Base64.getDecoder().decode(issued.certificateDerBase64)
        try {
            val privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(privateBytes))
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(certificateBytes.inputStream()) as X509Certificate
            certificate.checkValidity()
            require(MessageDigest.isEqual(certificate.publicKey.encoded, publicBytes)) {
                "Chứng chỉ không khớp với khóa ký được tạo trên thiết bị."
            }

            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
            val store = KeyStore.getInstance("PKCS12", BouncyCastleProvider.PROVIDER_NAME)
            store.load(null, password)
            store.setKeyEntry(
                "bo-sideload-ios-signing",
                privateKey,
                password,
                arrayOf(certificate)
            )
            return ByteArrayOutputStream().use { output ->
                store.store(output, password)
                output.toByteArray()
            }
        } finally {
            privateBytes.fill(0)
            publicBytes.fill(0)
            certificateBytes.fill(0)
        }
    }
}
