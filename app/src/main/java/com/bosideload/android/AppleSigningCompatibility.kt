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
        val warnings: List<String>,
        val profileOrder: List<Int> = emptyList()
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
    ): Check = preflight(ipaFile, p12File, listOf(profileFile), password, now, targetUdid)

    fun preflight(
        ipaFile: java.io.File,
        p12File: java.io.File,
        profileFiles: List<java.io.File>,
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
        require(profileFiles.size in 1..MAX_PROFILE_COUNT &&
            profileFiles.all { it.isFile && it.length() in 1..MAX_CREDENTIAL_BYTES } &&
            profileFiles.sumOf { it.length() } <= MAX_TOTAL_PROFILE_BYTES) {
            "Danh sách provisioning profile rỗng hoặc có tệp không hợp lệ/vượt giới hạn 10 MB."
        }
        val metadata = readIpaMetadata(ipaFile)
            ?: return Check(false, listOf("Không đọc được Info.plist / CFBundleIdentifier từ IPA."), emptyList())
        val nestedBundles = inspectNestedCodeBundles(ipaFile)
        val bundleIds = listOfNotNull(metadata.bundleId) + nestedBundles.mapNotNull { it.bundleId }
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (metadata.bundleId.isNullOrBlank()) errors += "Ứng dụng chính không có CFBundleIdentifier."
        nestedBundles.filter { it.bundleId.isNullOrBlank() }.forEach {
            errors += "Không đọc được Bundle ID của bundle lồng: ${it.path}"
        }
        val profiles = profileFiles.map { AppleProvisioningProfileReader.read(it.readBytes()) }
        val resolution = resolveProfileAssignments(bundleIds, profiles)
        errors += resolution.errors
        val passwordChars = password.toCharArray()
        val certificates = try {
            AppleP12Reader.read(p12File.readBytes(), passwordChars)
        } finally {
            passwordChars.fill(0.toChar())
        }
        resolution.assignments.forEach { assignment ->
            val profile = profiles[assignment.profileIndex]
            val bundleMetadata = metadata.copy(bundleId = assignment.bundleId)
            val result = check(bundleMetadata, profile, certificates, now, targetUdid)
            if (result.errors.isNotEmpty()) {
                errors += result.errors.map { "${assignment.bundleId}: $it" }
            }
            if (result.warnings.isNotEmpty()) {
                warnings += result.warnings.map { "${assignment.bundleId}: $it" }
            }
        }
        return Check(
            errors.isEmpty(), errors.distinct(), warnings.distinct(),
            resolution.assignments.map { it.profileIndex }.distinct()
        )
    }

    internal data class ProfileAssignment(val bundleId: String, val profileIndex: Int)
    internal data class ProfileResolution(val assignments: List<ProfileAssignment>, val errors: List<String>)

    internal fun resolveProfileAssignments(
        bundleIds: List<String>,
        profiles: List<AppleProvisioningProfileReader.ProfileInfo>
    ): ProfileResolution {
        val assignments = mutableListOf<ProfileAssignment>()
        val errors = mutableListOf<String>()
        if (bundleIds.isEmpty()) {
            return ProfileResolution(emptyList(), listOf("Không tìm thấy Bundle ID để ghép provisioning profile."))
        }
        bundleIds.forEach { bundleId ->
            val candidates = profiles.indices.filter { index ->
                val applicationId = profiles[index].teamBundleIdentifier
                !applicationId.isNullOrBlank() && matchesApplicationIdentifier(applicationId, bundleId)
            }
            when (candidates.size) {
                0 -> errors += "Không có provisioning profile phù hợp Bundle ID $bundleId."
                1 -> assignments += ProfileAssignment(bundleId, candidates.single())
                else -> errors += "Có nhiều provisioning profile cùng khớp Bundle ID $bundleId; hãy bỏ lựa chọn trùng/không rõ ràng."
            }
        }
        val used = assignments.map { it.profileIndex }.toSet()
        profiles.indices.filter { it !in used }.forEach {
            errors += "Provisioning profile thứ ${it + 1} không được ghép với bundle nào trong IPA."
        }
        return ProfileResolution(assignments, errors)
    }
    internal fun rejectUnsupportedNestedBundles(
        baseCheck: Check,
        nestedBundles: List<NestedBundleInfo>
    ): Check {
        if (nestedBundles.isEmpty()) return baseCheck
        val bundleSummary = nestedBundles.joinToString("; ") { bundle ->
            "${bundle.bundleId ?: "không đọc được Bundle ID"} (${bundle.path})"
        }
        val nestedError = "Không thể ký an toàn IPA này: phát hiện ${nestedBundles.size} app extension/Watch bundle: $bundleSummary. " +
            "Giao diện hiện chỉ nhận một provisioning profile và chưa hỗ trợ chọn, đối chiếu, truyền nhiều profile -m cho từng Bundle ID. " +
            "Để tránh tạo IPA có extension chưa được ký đúng, thao tác ký bị chặn. Hãy dùng IPA không có nested executable bundle hoặc chờ bản hỗ trợ nhiều profile."
        return baseCheck.copy(
            compatible = false,
            errors = baseCheck.errors + nestedError,
            warnings = baseCheck.warnings
        )
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
    private const val MAX_PROFILE_COUNT = 10
    private const val MAX_TOTAL_PROFILE_BYTES = 50L * 1024L * 1024L
    private const val MAX_ARCHIVE_ENTRIES = 100_000
    private const val MAX_NESTED_PLIST_BYTES = 1024 * 1024
    private val APP_INFO_PLIST_PATTERN = Regex("^Payload/[^/]+\\.app/Info\\.plist$")
    private val NESTED_CODE_BUNDLE_INFO_PATTERN =
        Regex("^Payload/[^/]+\\.app/(?:PlugIns/[^/]+\\.appex|Watch/[^/]+\\.app)/Info\\.plist$")
}
