package com.bosideload.android

import android.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Matches a privacy-preserving _apple-mobdev2._tcp advertisement to a
 * previously trusted Lockdown pair record. This is a local candidate match,
 * not a substitute for authenticating the subsequent TLS session.
 */
internal object AppleWifiPairingMatcher {
    data class Match(val deviceId: String, val hostId: String)

    fun findMatch(
        txtRecord: Map<String, String>,
        records: List<PairRecord>
    ): Match? {
        val identifier = txtRecord["identifier"]?.takeIf { it.isNotBlank() } ?: return null
        val advertisedTags = txtRecord
            .filterKeys { it == "authTag" || it.matches(Regex("authTag#[0-9]+")) }
            .values
            .mapNotNull(::decodeTag)
        if (advertisedTags.isEmpty()) return null

        for (record in records) {
            val expected = runCatching { deriveAuthTag(record.hostId, identifier) }.getOrNull()
                ?: continue
            if (advertisedTags.any { constantTimeEquals(expected, it) }) {
                return Match(record.deviceId, record.hostId)
            }
        }
        return null
    }

    internal fun deriveAuthTag(hostId: String, identifier: String): ByteArray {
        require(hostId.isNotBlank() && identifier.isNotBlank())
        val ikm = hostId.toByteArray(StandardCharsets.UTF_8)
        val salt = ByteArray(64) // RFC 5869: HashLen zero bytes when salt is omitted.
        val prk = hmac("HmacSHA512", salt, ikm)
        val okm = hmac("HmacSHA512", prk, byteArrayOf(0x01))
        val hmacKey = okm.copyOfRange(0, 32)
        return hmac("HmacSHA256", hmacKey, identifier.toByteArray(StandardCharsets.UTF_8))
            .copyOfRange(0, 8)
    }

    private fun decodeTag(value: String): ByteArray? =
        runCatching { Base64.decode(value, Base64.DEFAULT) }
            .getOrNull()
            ?.takeIf { it.size >= 8 }
            ?.copyOfRange(0, 8)

    private fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(data)
    }

    private fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
        if (left.size != right.size) return false
        var difference = 0
        for (index in left.indices) difference = difference or (left[index].toInt() xor right[index].toInt())
        return difference == 0
    }
}
