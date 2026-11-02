package com.bosideload.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class RecentIpa(
    val uri: String,
    val name: String,
    val sizeBytes: Long
)

internal class RecentIpaStore(context: Context) {
    private val preferences = context.getSharedPreferences("bo_sideload_library", Context.MODE_PRIVATE)

    fun all(): List<RecentIpa> {
        val result = mutableListOf<RecentIpa>()
        return try {
            val array = JSONArray(preferences.getString("recent_files", "[]"))
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                result.add(
                    RecentIpa(
                        item.optString("uri"),
                        item.optString("name", "IPA"),
                        item.optLong("sizeBytes", -1L)
                    )
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(file: RecentIpa) {
        val items = all().filterNot { it.uri == file.uri }.toMutableList()
        items.add(0, file)
        save(items.take(20))
    }

    fun remove(uri: String) {
        save(all().filterNot { it.uri == uri })
    }

    private fun save(items: List<RecentIpa>) {
        val array = JSONArray()
        items.forEach { file ->
            array.put(JSONObject().apply {
                put("uri", file.uri)
                put("name", file.name)
                put("sizeBytes", file.sizeBytes)
            })
        }
        preferences.edit().putString("recent_files", array.toString()).apply()
    }
}
