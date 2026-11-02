package com.bosideload.android

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Reads public X.509 certificates only. No private keys or P12 passwords are handled here. */
internal object AppleCertificateReader {
    data class CertificateInfo(
        val subject: String,
        val issuer: String,
        val serialNumber: String,
        val notBefore: Date,
        val notAfter: Date,
        val sha256Fingerprint: String,
        val expired: Boolean,
        val currentlyValid: Boolean
    ) {
        fun summary(): String = buildString {
            appendLine("Subject: $subject")
            appendLine("Issuer: $issuer")
            appendLine("Serial: $serialNumber")
            appendLine("Có hiệu lực từ: ${format(notBefore)}")
            appendLine("Hết hạn: ${format(notAfter)}")
            appendLine("SHA-256 fingerprint: $sha256Fingerprint")
            appendLine(if (expired) "CẢNH BÁO: Certificate đã hết hạn." else "Certificate chưa quá ngày hết hạn.")
            append(if (currentlyValid) "Thời gian hiện tại nằm trong khoảng hiệu lực." else "Thời gian hiện tại nằm ngoài khoảng hiệu lực.")
        }
    }

    fun read(input: ByteArray): CertificateInfo {
        require(input.isNotEmpty()) { "Tệp certificate rỗng." }
        require(input.size <= MAX_CERTIFICATE_BYTES) { "Certificate vượt giới hạn 2 MB." }
        val normalized = if (input.toString(Charsets.US_ASCII).contains("-----BEGIN CERTIFICATE-----")) {
            val pem = input.toString(Charsets.US_ASCII)
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .filterNot { it.isWhitespace() }
            android.util.Base64.decode(pem, android.util.Base64.DEFAULT)
        } else input

        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(normalized)) as? X509Certificate
            ?: throw IllegalArgumentException("Không nhận diện được X.509 certificate.")

        val now = Date()
        val expired = certificate.notAfter.before(now)
        val valid = try {
            certificate.checkValidity(now)
            true
        } catch (_: Exception) {
            false
        }
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it) }

        return CertificateInfo(
            subject = certificate.subjectX500Principal.name,
            issuer = certificate.issuerX500Principal.name,
            serialNumber = certificate.serialNumber.toString(16).uppercase(Locale.ROOT),
            notBefore = certificate.notBefore,
            notAfter = certificate.notAfter,
            sha256Fingerprint = fingerprint,
            expired = expired,
            currentlyValid = valid
        )
    }

    private fun format(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(date)

    private const val MAX_CERTIFICATE_BYTES = 2 * 1024 * 1024
}
