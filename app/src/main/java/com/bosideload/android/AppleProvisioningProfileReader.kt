package com.bosideload.android

import com.dd.plist.NSArray
import com.dd.plist.NSDate
import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import org.bouncycastle.cms.CMSSignedData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Extracts the plist payload from an Apple CMS provisioning profile.
 *
 * This parses profile contents only. It deliberately does NOT claim to validate
 * the CMS signature chain, Apple trust, certificate revocation, or device eligibility.
 */
internal object AppleProvisioningProfileReader {
    data class ProfileInfo(
        val name: String?,
        val uuid: String?,
        val teamIdentifiers: List<String>,
        val applicationIdentifierPrefixes: List<String>,
        val bundleIdentifier: String?,
        val teamBundleIdentifier: String?,
        val platforms: List<String>,
        val provisionedDevices: List<String>,
        val provisionsAllDevices: Boolean,
        val creationDate: Date?,
        val expirationDate: Date?,
        val developerCertificateCount: Int
    ) {
        fun summary(): String = buildString {
            appendLine("Tên profile: ${name ?: "Không có"}")
            appendLine("UUID: ${uuid ?: "Không có"}")
            appendLine("Team ID: ${teamIdentifiers.joinToString().ifBlank { "Không có" }}")
            appendLine("Bundle ID: ${bundleIdentifier ?: "Không có"}")
            appendLine("Application ID: ${teamBundleIdentifier ?: "Không có"}")
            appendLine("Nền tảng: ${platforms.joinToString().ifBlank { "Không ghi rõ" }}")
            appendLine("Thiết bị UDID: ${provisionedDevices.size}")
            appendLine("All devices: ${if (provisionsAllDevices) "Có" else "Không"}")
            appendLine("Ngày tạo: ${formatDate(creationDate)}")
            appendLine("Hết hạn: ${formatDate(expirationDate)}")
            append("Developer certificate trong profile: $developerCertificateCount")
        }
    }

    fun read(encodedProfile: ByteArray): ProfileInfo {
        require(encodedProfile.isNotEmpty()) { "Tệp profile rỗng." }
        require(encodedProfile.size <= MAX_PROFILE_BYTES) { "Profile vượt giới hạn 10 MB." }

        val plistBytes = extractPlist(encodedProfile)
        val root = PropertyListParser.parse(plistBytes) as? NSDictionary
            ?: throw IllegalArgumentException("Nội dung profile không phải property list hợp lệ.")

        val entitlements = root.objectForKey("Entitlements") as? NSDictionary
        val applicationId = entitlements?.objectForKey("application-identifier")?.toString()
        val teamIds = strings(root, "TeamIdentifier")
        val appPrefixes = strings(root, "ApplicationIdentifierPrefix")
        val devices = strings(root, "ProvisionedDevices")

        return ProfileInfo(
            name = string(root, "Name"),
            uuid = string(root, "UUID"),
            teamIdentifiers = teamIds,
            applicationIdentifierPrefixes = appPrefixes,
            bundleIdentifier = applicationId?.let { appId ->
                (teamIds + appPrefixes).firstOrNull { appId.startsWith("${it}.") }
                    ?.let { prefix -> appId.removePrefix("${prefix}.") } ?: appId
            },
            teamBundleIdentifier = applicationId,
            platforms = strings(root, "Platform"),
            provisionedDevices = devices,
            provisionsAllDevices = (root.objectForKey("ProvisionsAllDevices")?.toString() == "true"),
            creationDate = date(root, "CreationDate"),
            expirationDate = date(root, "ExpirationDate"),
            developerCertificateCount = (root.objectForKey("DeveloperCertificates") as? NSArray)?.array?.size ?: 0
        )
    }

    private fun extractPlist(bytes: ByteArray): ByteArray {
        val trimmed = bytes.dropWhile { it == 0x20.toByte() || it == 0x09.toByte() || it == 0x0A.toByte() || it == 0x0D.toByte() }
            .take(16).toByteArray()
        if (trimmed.isNotEmpty() &&
            (trimmed[0] == '<'.code.toByte() || trimmed[0] == 'b'.code.toByte())
        ) {
            // Also accept an unwrapped XML/binary plist for diagnostics and tests.
            return bytes
        }

        val cms = try {
            CMSSignedData(bytes)
        } catch (error: Exception) {
            throw IllegalArgumentException("Không giải mã được CMS của provisioning profile: ${error.message ?: "định dạng không hợp lệ"}")
        }
        val content = cms.signedContent?.content as? ByteArray
            ?: throw IllegalArgumentException("CMS không chứa plist nội bộ.")
        require(content.isNotEmpty() && content.size <= MAX_PROFILE_BYTES) {
            "Nội dung plist trong profile không hợp lệ hoặc quá lớn."
        }
        return content
    }

    private fun string(root: NSDictionary, key: String): String? =
        root.objectForKey(key)?.toString()?.takeIf { it.isNotBlank() }

    private fun strings(root: NSDictionary, key: String): List<String> {
        val array = root.objectForKey(key) as? NSArray ?: return emptyList()
        return array.array.map { it.toString() }.filter { it.isNotBlank() }
    }

    private fun date(root: NSDictionary, key: String): Date? =
        (root.objectForKey(key) as? NSDate)?.date

    private fun formatDate(value: Date?): String {
        if (value == null) return "Không có"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(value)
    }

    private const val MAX_PROFILE_BYTES = 10 * 1024 * 1024
}
