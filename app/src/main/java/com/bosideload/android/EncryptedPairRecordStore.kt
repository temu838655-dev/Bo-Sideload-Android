package com.bosideload.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts each device's complete pairing record with a non-exportable
 * Android Keystore AES-GCM key. No pairing secrets are written in plaintext.
 */
internal class EncryptedPairRecordStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    @Synchronized
    fun save(record: PairRecord) {
        record.validate()
        val alias = keyAlias(record.deviceId)
        val key = getOrCreateKey(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(record.deviceId.toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(PairRecordCodec.encode(record))
        val blob = ByteBuffer.allocate(cipher.iv.size + encrypted.size)
            .put(cipher.iv)
            .put(encrypted)
            .array()
        check(preferences.edit().putString(prefKey(record.deviceId), Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) {
            "could not persist encrypted pairing record"
        }
    }

    @Synchronized
    fun load(deviceId: String): PairRecord? {
        require(deviceId.isNotBlank()) { "device ID must not be blank" }
        val encoded = preferences.getString(prefKey(deviceId), null) ?: return null
        val blob = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("stored pairing record is corrupt", error)
        }
        require(blob.size > GCM_IV_BYTES + GCM_TAG_BYTES) { "stored pairing record is truncated" }
        val buffer = ByteBuffer.wrap(blob)
        val iv = ByteArray(GCM_IV_BYTES).also { buffer.get(it) }
        val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getExistingKey(keyAlias(deviceId)), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(deviceId.toByteArray(StandardCharsets.UTF_8))
        val record = PairRecordCodec.decode(cipher.doFinal(ciphertext))
        require(record.deviceId == deviceId) { "pairing record device mismatch" }
        return record
    }

    @Synchronized
    fun delete(deviceId: String) {
        require(deviceId.isNotBlank()) { "device ID must not be blank" }
        preferences.edit().remove(prefKey(deviceId)).commit()
        val store = keyStore()
        store.deleteEntry(keyAlias(deviceId))
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        val store = keyStore()
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun getExistingKey(alias: String): SecretKey =
        (keyStore().getKey(alias, null) as? SecretKey)
            ?: throw IllegalStateException("Android Keystore key is missing; pairing must be repeated")

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun keyAlias(deviceId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(deviceId.toByteArray(StandardCharsets.UTF_8))
        return KEY_PREFIX + digest.joinToString("") { "%02x".format(it) }
    }

    private fun prefKey(deviceId: String) = RECORD_PREFIX + keyAlias(deviceId).removePrefix(KEY_PREFIX)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFS_NAME = "bo_sideload_encrypted_pair_records"
        const val KEY_PREFIX = "bo_pair_"
        const val RECORD_PREFIX = "record_"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    }
}
