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
        now: Date = Date()
    ): Check {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val bundleId = ipa.bundleId?.takeIf { it.isNotBlank() }
        val appIdentifier = profile.teamBundleIdentifier
        if (bundleId == null) {
            errors += "Không đọc được CFBundleIdentifier trong IPA."
        } else if (appIdentifier.isNullOrBlank()) {
            errors += "Profile không có Entitlements.application-identifier."
        } else if (!matchesApplicationIdentifier(appIdentifier, bundleId)) {
            errors += "Bundle ID của IPA không khớp application-identifier trong profile."
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
            if (profileHashes.isEmpty()) {
                warnings += "Profile không cung cấp DeveloperCertificates để so fingerprint."
            } else if (certificates.none { it.sha256Fingerprint in profileHashes }) {
                errors += "Không có certificate trong P12 khớp fingerprint DeveloperCertificates của profile."
            }
            if (certificates.none { it.hasPrivateKey }) {
                errors += "P12 không chứa private key; không thể dùng làm khóa ký."
            }
            if (certificates.none { !it.notAfter.before(now) }) {
                errors += "Certificate trong P12 đã hết hạn."
            }
        }

        if (profile.provisionedDevices.isEmpty() && !profile.provisionsAllDevices) {
            warnings += "Profile không liệt kê thiết bị cụ thể và không bật All Devices; chưa xác nhận được thiết bị đích có được cấp quyền."
        } else {
            warnings += "Chưa kiểm tra UDID thiết bị đích vì Bo Sideload chưa đọc được UDID iPhone qua Lockdown."
        }

        if (profile.teamIdentifiers.isEmpty()) {
            warnings += "Profile không có TeamIdentifier."
        }

        return Check(errors.isEmpty(), errors, warnings)
    }

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
}
