package com.bosideload.android

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate

/** Inspects PKCS#12 locally; never persists passwords or exports private keys. */
internal object AppleP12Reader {
    data class EntryInfo(
        val alias: String,
        val hasPrivateKey: Boolean,
        val subject: String,
        val issuer: String,
        val notAfter: java.util.Date,
        val sha256Fingerprint: String
    )

    fun read(bytes: ByteArray, password: CharArray): List<EntryInfo> {
        require(bytes.isNotEmpty()) { "Tệp P12 rỗng." }
        require(bytes.size <= 10 * 1024 * 1024) { "Tệp P12 vượt giới hạn 10 MB." }
        val store = KeyStore.getInstance("PKCS12")
        ByteArrayInputStream(bytes).use { store.load(it, password) }
        val aliases = store.aliases()
        val entries = mutableListOf<EntryInfo>()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            val cert = store.getCertificate(alias) as? X509Certificate ?: continue
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(cert.encoded)
                .joinToString(":") { "%02X".format(it) }
            entries += EntryInfo(
                alias = alias,
                hasPrivateKey = store.isKeyEntry(alias),
                subject = cert.subjectX500Principal.name,
                issuer = cert.issuerX500Principal.name,
                notAfter = cert.notAfter,
                sha256Fingerprint = fingerprint
            )
        }
        require(entries.isNotEmpty()) { "P12 không chứa certificate X.509 có thể đọc." }
        return entries
    }
}
