package com.bosideload.android

import java.util.Date

/**
 * Compares IPA metadata with provisioning-profile and P12 metadata.
 * This is a compatibility preflight only, not Apple CMS/trust/signature validation.
 */
internal object AppleSigningCompatibility {
    data class Check(
        val compatible: Boolean,
        val errors: List<String>,
        val warnings: List<String>
    )

    fun check(
        ipa: IpaPlistReader.Metadata,
        profile: AppleProvisioningProfileReader.ProfileInfo,
        certificates: List<AppleP12Reader.EntryInfo>,
        now: Date = Date(),
        targetUdid: String? = null
    ): Check {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val bundleId = ipa.bundleId?.takeIf { it.isNotBlank() }
        val appIdentifier = profile.teamBundleIdentifier
        if (bundleId == null) {
            errors += "Không đọc được CFBundleIdentifier trong IPA."
        } else if (appIdentifier.isNullOrBlank()) {
            errors += "Profile không có Entitlements.application-identifier."
        } else {
            if (!matchesApplicationIdentifier(appIdentifier, bundleId)) {
                errors += "Bundle ID của IPA không khớp application-identifier trong profile."
            }
            if (!hasKnownProfilePrefix(appIdentifier, profile.teamIdentifiers, profile.applicationIdentifierPrefixes)) {
                errors += "Prefix của application-identifier không khớp TeamIdentifier hoặc ApplicationIdentifierPrefix trong profile."
            }
        }

        if (profile.expirationDate == null) {
            warnings += "Không xác định được ngày hết hạn của profile."
        } else if (profile.expirationDate.before(now)) {
            errors += "Provisioning profile đã hết hạn."
        }

        if (profile.platforms.isEmpty()) {
            warnings += "Profile không khai báo Platform; chưa thể đối chiếu nền tảng."
        } else if (profile.platforms.none { it.equals("iOS", true) }) {
            errors += "Profile không khai báo nền tảng iOS; không phù hợp với quy trình ký IPA iOS này."
        }

        if (certificates.isEmpty()) {
            errors += "P12 không có certificate X.509 để đối chiếu."
        } else {
            val profileHashes = profile.developerCertificateSha256.toSet()
            val matchingCertificates = if (profileHashes.isEmpty()) {
                warnings += "Profile không cung cấp DeveloperCertificates để so fingerprint."
                certificates
            } else {
                certificates.filter { it.sha256Fingerprint in profileHashes }.also {
                    if (it.isEmpty()) {
                        errors += "Không có certificate trong P12 khớp fingerprint DeveloperCertificates của profile."
                    }
                }
            }
            if (matchingCertificates.none {
                    it.hasPrivateKey && isCertificateValidAt(it.notBefore, it.notAfter, now)
                }) {
                errors += "Không tìm thấy cùng một certificate vừa khớp profile, có private key và đang trong thời hạn hiệu lực."
            }
        }

        when (isDeviceProvisioned(
            profile.provisionedDevices,
            profile.provisionsAllDevices,
            targetUdid
        )) {
            true -> Unit
            false -> errors += "UDID iPhone không nằm trong danh sách thiết bị được cấp quyền của provisioning profile."
            null -> warnings += if (profile.provisionsAllDevices) {
                "Profile bật All Devices; không cần đối chiếu UDID cụ thể."
            } else if (profile.provisionedDevices.isEmpty()) {
                "Profile không liệt kê thiết bị cụ thể và không bật All Devices; chưa xác nhận được thiết bị đích."
            } else {
                "Chưa có UDID iPhone để đối chiếu với danh sách thiết bị trong profile."
            }
        }

        if (profile.teamIdentifiers.isEmpty()) {
            warnings += "Profile không có TeamIdentifier."
        }

        return Check(errors.isEmpty(), errors, warnings)
    }

    fun preflight(
        ipaFile: java.io.File,
        p12File: java.io.File,
        profileFile: java.io.File,
        password: String,
        now: Date = Date(),
        targetUdid: String? = null
    ): Check {
        require(ipaFile.isFile && ipaFile.length() in 1..MAX_IPA_BYTES) {
            "IPA không tồn tại, rỗng hoặc vượt giới hạn 2 GB."
        }
        require(p12File.isFile && p12File.length() in 1..MAX_CREDENTIAL_BYTES) {
            "P12 không tồn tại, rỗng hoặc vượt giới hạn 10 MB."
        }
        require(profileFile.isFile && profileFile.length() in 1..MAX_CREDENTIAL_BYTES) {
            "Provisioning profile không tồn tại, rỗng hoặc vượt giới hạn 10 MB."
        }
        val metadata = readIpaMetadata(ipaFile)
            ?: return Check(false, listOf("Không đọc được Info.plist / CFBundleIdentifier từ IPA."), emptyList())
        val profile = AppleProvisioningProfileReader.read(profileFile.readBytes())
        val passwordChars = password.toCharArray()
        val certificates = try {
            AppleP12Reader.read(p12File.readBytes(), passwordChars)
        } finally {
            passwordChars.fill(0.toChar())
        }
        val baseCheck = check(metadata, profile, certificates, now, targetUdid)
        val nestedBundles = inspectNestedCodeBundles(ipaFile)
        return if (nestedBundles.isEmpty()) {
            baseCheck
        } else {
            val bundleSummary = nestedBundles.joinToString("; ") { bundle ->
                "${bundle.bundleId ?: "không đọc được Bundle ID"} (${bundle.path})"
            }
            baseCheck.copy(
                warnings = baseCheck.warnings + listOf(
                    "Phát hiện ${nestedBundles.size} app extension/Watch bundle: $bundleSummary. " +
                        "Bản hiện tại chỉ nhận một provisioning profile qua giao diện ký; chưa chọn và đối chiếu profile riêng cho từng Bundle ID. " +
                        "zsign có thể xử lý nhiều profile qua nhiều tham số -m, nhưng Bo Sideload chưa cung cấp luồng đó. " +
                        "Không nên coi IPA này đã sẵn sàng cài đặt cho đến khi từng bundle được ký đúng profile và kiểm thử trên thiết bị."
                )
            )
        }
    }

    private fun readIpaMetadata(file: java.io.File): IpaPlistReader.Metadata? {
        require(file.isFile && file.length() > 0L) { "IPA không tồn tại hoặc rỗng." }
        java.util.zip.ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && isAppInfoPlistPath(entry.name)) {
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 1024 * 1024) { "Info.plist vượt giới hạn 1 MB." }
                        output.write(buffer, 0, count)
                    }
                    return IpaPlistReader.read(output.toByteArray())
                }
                zip.closeEntry()
            }
        }
        return null
    }

    internal fun isAppInfoPlistPath(path: String): Boolean =
        APP_INFO_PLIST_PATTERN.matches(path)

    /**
     * Finds nested executable bundles that can require their own App ID and
     * provisioning profile. The current UI accepts only one profile, so these
     * are surfaced as a preflight warning rather than silently claiming support.
     */
    internal fun findNestedCodeBundleInfoPlists(ipaFile: java.io.File): List<String> {
        val found = linkedSetOf<String>()
        java.util.zip.ZipInputStream(ipaFile.inputStream().buffered()).use { zip ->
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += 1
                require(entries <= MAX_ARCHIVE_ENTRIES) {
                    "IPA có quá nhiều mục ZIP khi quét app extension."
                }
                if (!entry.isDirectory && isNestedCodeBundleInfoPlistPath(entry.name)) {
                    found += entry.name
                }
                zip.closeEntry()
            }
        }
        return found.toList()
    }

    internal fun isNestedCodeBundleInfoPlistPath(path: String): Boolean =
        NESTED_CODE_BUNDLE_INFO_PATTERN.matches(path)

    internal data class NestedBundleInfo(
        val path: String,
        val bundleId: String?,
        val executable: String?
    )

    /**
     * Reads nested extension/Watch Info.plist metadata with a strict per-entry
     * size cap. This identifies bundle IDs for diagnostics only; it does not
     * validate or sign each nested bundle with its own provisioning profile.
     */
    internal fun inspectNestedCodeBundles(ipaFile: java.io.File): List<NestedBundleInfo> {
        val found = linkedMapOf<String, NestedBundleInfo>()
        java.util.zip.ZipInputStream(ipaFile.inputStream().buffered()).use { zip ->
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += 1
                require(entries <= MAX_ARCHIVE_ENTRIES) {
                    "IPA có quá nhiều mục ZIP khi quét app extension."
                }
                if (!entry.isDirectory && isNestedCodeBundleInfoPlistPath(entry.name)) {
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= MAX_NESTED_PLIST_BYTES) {
                            "Info.plist của extension vượt giới hạn 1 MB: ${entry.name}"
                        }
                        output.write(buffer, 0, count)
                    }
                    val metadata = IpaPlistReader.read(output.toByteArray())
                    found[entry.name] = NestedBundleInfo(
                        path = entry.name,
                        bundleId = metadata?.bundleId,
                        executable = metadata?.executable
                    )
                }
                zip.closeEntry()
            }
        }
        return found.values.toList()
    }


    internal fun hasKnownProfilePrefix(
        applicationIdentifier: String,
        teamIdentifiers: List<String>,
        applicationIdentifierPrefixes: List<String>
    ): Boolean {
        val separator = applicationIdentifier.indexOf('.')
        if (separator <= 0) return false
        val prefix = applicationIdentifier.substring(0, separator)
        return prefix in teamIdentifiers || prefix in applicationIdentifierPrefixes
    }

    /**
     * Returns true/false when eligibility can be determined, or null when the
     * profile/device data is insufficient. Enterprise profiles bypass UDID lists.
     */
    internal fun isDeviceProvisioned(
        provisionedDevices: List<String>,
        provisionsAllDevices: Boolean,
        targetUdid: String?
    ): Boolean? {
        if (provisionsAllDevices) return true
        if (provisionedDevices.isEmpty()) return null
        val udid = targetUdid?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return provisionedDevices.any { it.trim().equals(udid, ignoreCase = true) }
    }

    internal fun isCertificateValidAt(notBefore: Date, notAfter: Date, now: Date): Boolean =
        !now.before(notBefore) && !now.after(notAfter)

    internal fun matchesApplicationIdentifier(profileApplicationId: String, bundleId: String): Boolean {
        val separator = profileApplicationId.indexOf('.')
        if (separator <= 0 || separator == profileApplicationId.lastIndex) return false
        val allowedBundle = profileApplicationId.substring(separator + 1)
        if (allowedBundle == "*") return bundleId.isNotBlank()
        if (allowedBundle.endsWith(".*")) {
            val prefix = allowedBundle.removeSuffix(".*")
            return bundleId.startsWith("$prefix.") && bundleId.length > prefix.length + 1
        }
        return allowedBundle == bundleId
    }

    private const val MAX_IPA_BYTES = 2L * 1024L * 1024L * 1024L
    private const val MAX_CREDENTIAL_BYTES = 10L * 1024L * 1024L
    private const val MAX_ARCHIVE_ENTRIES = 100_000
    private const val MAX_NESTED_PLIST_BYTES = 1024 * 1024
    private val APP_INFO_PLIST_PATTERN = Regex("^Payload/[^/]+\\.app/Info\\.plist$")
    private val NESTED_CODE_BUNDLE_INFO_PATTERN =
        Regex("^Payload/[^/]+\\.app/(?:PlugIns/[^/]+\\.appex|Watch/[^/]+\\.app)/Info\\.plist$")
}
