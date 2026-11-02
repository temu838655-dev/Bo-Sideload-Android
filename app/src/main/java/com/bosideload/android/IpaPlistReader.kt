package com.bosideload.android

import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser

/**
 * Reads common metadata from XML, binary, or ASCII Apple property lists.
 * Input is capped before parsing to limit memory use.
 */
internal object IpaPlistReader {
    data class Metadata(
        val appName: String?,
        val bundleId: String?,
        val shortVersion: String?,
        val buildVersion: String?,
        val executable: String?
    )

    fun read(bytes: ByteArray): Metadata? {
        if (bytes.isEmpty() || bytes.size > MAX_PLIST_BYTES) return null
        return try {
            val root = PropertyListParser.parse(bytes) as? NSDictionary ?: return null
            fun value(key: String): String? =
                root.objectForKey(key)?.toString()?.takeIf { it.isNotBlank() }

            Metadata(
                appName = value("CFBundleDisplayName") ?: value("CFBundleName"),
                bundleId = value("CFBundleIdentifier"),
                shortVersion = value("CFBundleShortVersionString"),
                buildVersion = value("CFBundleVersion"),
                executable = value("CFBundleExecutable")
            )
        } catch (_: Exception) {
            null
        }
    }

    private const val MAX_PLIST_BYTES = 1024 * 1024
}
