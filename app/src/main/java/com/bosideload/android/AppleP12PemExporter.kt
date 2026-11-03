package com.bosideload.android

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID

/**
 * Exports a single P12 identity to short-lived app-private PEM files for zsign.
 *
 * Upstream zsign v1.1.3 accepts the P12 password only through -p, which exposes
 * it in process arguments. Its supported -k PEM + -c certificate path avoids
 * putting the password in argv. The unencrypted private key exists only in
 * this app's private cache directory for the duration of one signing operation.
 */
internal object AppleP12PemExporter {
    data class ExportedIdentity(
        val directory: File,
        val privateKeyFile: File,
        val certificateFile: File
    ) {
        fun cleanup() {
            directory.deleteRecursively()
        }
    }

    fun export(context: Context, p12File: File, password: String): ExportedIdentity {
        require(p12File.isFile && p12File.length() in 1..MAX_P12_BYTES) {
            "P12 không tồn tại, rỗng hoặc vượt giới hạn 10 MB."
        }
        val passwordChars = password.toCharArray()
        try {
            val store = KeyStore.getInstance("PKCS12")
            p12File.inputStream().use { store.load(it, passwordChars) }
            val aliases = store.aliases()
            val keyAliases = mutableListOf<String>()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                if (store.isKeyEntry(alias)) keyAliases += alias
            }
            require(keyAliases.size == 1) {
                "P12 phải chứa đúng một danh tính private key; không hỗ trợ P12 có nhiều khóa."
            }

            val alias = keyAliases.single()
            val privateKey = store.getKey(alias, passwordChars) as? PrivateKey
                ?: throw IllegalArgumentException("Không đọc được private key trong P12.")
            val certificate = store.getCertificate(alias) as? X509Certificate
                ?: throw IllegalArgumentException("P12 không có certificate X.509 tương ứng.")
            certificate.checkValidity()
            verifyKeyPair(privateKey, certificate)

            val directory = File(context.cacheDir, "zsign-identity-${UUID.randomUUID()}")
            require(directory.mkdirs()) { "Không tạo được thư mục riêng cho thao tác ký." }
            directory.setReadable(false, false)
            directory.setWritable(false, false)
            directory.setExecutable(false, false)
            require(directory.setReadable(true, true) &&
                directory.setWritable(true, true) &&
                directory.setExecutable(true, true)) {
                directory.deleteRecursively()
                "Không thể khóa quyền truy cập thư mục private key."
            }

            val keyFile = File(directory, "identity-key.pem")
            val certFile = File(directory, "identity-cert.pem")
            try {
                writePem(keyFile, "PRIVATE KEY", privateKey.encoded)
                writePem(certFile, "CERTIFICATE", certificate.encoded)
                return ExportedIdentity(directory, keyFile, certFile)
            } catch (error: Exception) {
                directory.deleteRecursively()
                throw error
            }
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private fun verifyKeyPair(privateKey: PrivateKey, certificate: X509Certificate) {
        val algorithm = when (privateKey.algorithm.uppercase()) {
            "RSA" -> "SHA256withRSA"
            "EC", "ECDSA" -> "SHA256withECDSA"
            else -> throw IllegalArgumentException("Loại private key không được hỗ trợ: ${privateKey.algorithm}")
        }
        val challenge = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val signer = Signature.getInstance(algorithm)
        signer.initSign(privateKey)
        signer.update(challenge)
        val signed = signer.sign()
        val verifier = Signature.getInstance(algorithm)
        verifier.initVerify(certificate.publicKey)
        verifier.update(challenge)
        require(verifier.verify(signed)) {
            "Private key trong P12 không khớp certificate."
        }
        challenge.fill(0)
        signed.fill(0)
    }

    private fun writePem(file: File, label: String, bytes: ByteArray) {
        try {
            FileOutputStream(file).use { output ->
                output.write("-----BEGIN $label-----\n".toByteArray(Charsets.US_ASCII))
                val encoded = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
                    .encode(bytes)
                output.write(encoded)
                output.write("\n-----END $label-----\n".toByteArray(Charsets.US_ASCII))
                output.fd.sync()
            }
            file.setReadable(false, false)
            file.setWritable(false, false)
            require(file.setReadable(true, true) && file.setWritable(true, true)) {
                "Không thể giới hạn quyền đọc private key PEM."
            }
        } finally {
            bytes.fill(0)
        }
    }

    private const val MAX_P12_BYTES = 10L * 1024L * 1024L
}
