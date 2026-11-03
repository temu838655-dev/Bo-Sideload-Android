package com.bosideload.android

import android.content.Context
import java.util.Locale
import java.util.UUID

/**
 * Stores the host's stable SystemBUID. It is an identifier, not a private key.
 */
internal class PairingIdentityStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun getOrCreateSystemBuid(): String {
        val stored = preferences.getString(KEY_SYSTEM_BUID, null)
        if (stored != null && runCatching { UUID.fromString(stored) }.isSuccess) {
            return stored
        }
        val generated = UUID.randomUUID().toString().uppercase(Locale.ROOT)
        check(preferences.edit().putString(KEY_SYSTEM_BUID, generated).commit()) {
            "could not persist SystemBUID"
        }
        return generated
    }

    private companion object {
        const val PREFS_NAME = "bo_sideload_host_identity"
        const val KEY_SYSTEM_BUID = "system_buid"
    }
}
