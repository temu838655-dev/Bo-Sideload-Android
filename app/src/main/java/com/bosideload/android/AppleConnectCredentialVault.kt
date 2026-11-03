package com.bosideload.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

/** Local encrypted storage and read-only App Store Connect Team API validation. */
internal class AppleConnectCredentialVault(context: Context) {
    private val prefs = context.getSharedPreferences("apple_connect_credentials", Context.MODE_PRIVATE)
    data class Credentials(val keyId: String, val issuerId: String, val privateKeyPem: String)

    fun save(c: Credentials) {
        require(c.keyId.matches(Regex("[A-Za-z0-9]{8,20}"))) { "Key ID không hợp lệ." }
        require(c.issuerId.matches(Regex("[0-9A-Fa-f-]{30,40}"))) { "Issuer ID không hợp lệ." }
        require(c.privateKeyPem.contains("-----BEGIN PRIVATE KEY-----")) { "P8 phải là PKCS#8." }
        AppleConnectJwt.token(c)
        val clear = JSONObject().put("kid", c.keyId).put("iss", c.issuerId)
            .put("p8", c.privateKeyPem).toString().toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(clear)
        prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
        clear.fill(0)
    }

    fun load(): Credentials? {
        val iv = prefs.getString("iv", null) ?: return null
        val data = prefs.getString("data", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        val clear = cipher.doFinal(Base64.decode(data, Base64.NO_WRAP))
        return try {
            val j = JSONObject(String(clear, StandardCharsets.UTF_8))
            Credentials(j.getString("kid"), j.getString("iss"), j.getString("p8"))
        } finally { clear.fill(0) }
    }

    fun clear() {
        prefs.edit().clear().apply()
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return gen.generateKey()
    }
    companion object { private const val ALIAS = "bo_sideload_apple_connect_aes" }
}

internal object AppleConnectJwt {
    private val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    private fun b64(b: ByteArray) = Base64.encodeToString(b, flags)

    fun token(c: AppleConnectCredentialVault.Credentials): String {
        val now = System.currentTimeMillis() / 1000
        val header = JSONObject().put("alg", "ES256").put("kid", c.keyId).put("typ", "JWT").toString()
        val payload = JSONObject().put("iss", c.issuerId).put("iat", now)
            .put("exp", now + 300).put("aud", "appstoreconnect-v1").toString()
        val input = b64(header.toByteArray(Charsets.UTF_8)) + "." + b64(payload.toByteArray(Charsets.UTF_8))
        val body = c.privateKeyPem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "").filterNot { it.isWhitespace() }
        val keyBytes = Base64.decode(body, Base64.DEFAULT)
        val privateKey = try {
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        } finally { keyBytes.fill(0) }
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey); update(input.toByteArray(Charsets.US_ASCII)); sign()
        }
        return input + "." + b64(derToJose(der))
    }

    private fun derToJose(der: ByteArray): ByteArray {
        var p = 0
        require(der.size > 8 && (der[p++].toInt() and 255) == 0x30) { "Chữ ký ECDSA không hợp lệ." }
        val seqLen = der[p++].toInt() and 255
        require(seqLen == der.size - p && (der[p++].toInt() and 255) == 2) { "DER ECDSA sai định dạng." }
        val rLen = der[p++].toInt() and 255
        require(rLen in 1..33 && p + rLen < der.size) { "Thành phần r không hợp lệ." }
        val r = der.copyOfRange(p, p + rLen); p += rLen
        require((der[p++].toInt() and 255) == 2) { "Thiếu thành phần s." }
        val sLen = der[p++].toInt() and 255
        require(sLen in 1..33 && p + sLen == der.size) { "Thành phần s không hợp lệ." }
        val s = der.copyOfRange(p, p + sLen)
        val out = ByteArray(64)
        put32(r, out, 0); put32(s, out, 32)
        return out
    }
    private fun put32(src: ByteArray, dst: ByteArray, off: Int) {
        var start = 0
        while (start < src.size - 32 && src[start] == 0.toByte()) start++
        val n = src.size - start
        require(n <= 32) { "Khóa không phải P-256." }
        System.arraycopy(src, start, dst, off + 32 - n, n)
    }
}

internal object AppleConnectApi {
    data class Result(val ok: Boolean, val message: String)
    fun validate(c: AppleConnectCredentialVault.Credentials): Result {
        val path = "/v1/certificates?limit=1"
        val conn = URL("https://api.appstoreconnect.apple.com" + path).openConnection() as HttpsURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("Authorization", "Bearer " + AppleConnectJwt.token(c))
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            when (code) {
                in 200..299 -> Result(true, "Apple chấp nhận Team API Key. Kết nối thành công; chưa tạo hoặc sửa tài nguyên nào.")
                401 -> Result(false, "HTTP 401: kiểm tra Key ID, Issuer ID và tệp .p8 có thuộc cùng một key.")
                403 -> Result(false, "HTTP 403: key thiếu quyền hoặc là Individual API Key. Provisioning endpoints cần Team API Key.")
                429 -> Result(false, "HTTP 429: Apple đang giới hạn tần suất. Hãy thử lại sau.")
                else -> Result(false, "Apple API trả HTTP " + code + ". " + body.take(400))
            }
        } finally { conn.disconnect() }
    }
}
