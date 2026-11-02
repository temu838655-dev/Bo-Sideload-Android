package com.bosideload.android

import java.io.File

/**
 * Performs structural post-sign checks. This does not cryptographically verify
 * the Apple code signature, CMS chain, entitlements, or device installation eligibility.
 */
internal object SignedIpaVerifier {
    data class Result(
        val valid: Boolean,
        val message: String,
        val bundleId: String? = null
    )

    fun verify(inputIpa: File, outputIpa: File): Result {
        if (!inputIpa.isFile || inputIpa.length() !in 1..MAX_IPA_BYTES) {
            return Result(false, "IPA gốc không tồn tại, rỗng hoặc vượt giới hạn 2 GB.")
        }
        if (!outputIpa.isFile || outputIpa.length() !in 1..MAX_IPA_BYTES) {
            return Result(false, "IPA đầu ra không tồn tại, rỗng hoặc vượt giới hạn 2 GB.")
        }

        val input = validate(inputIpa)
        if (!input.valid) {
            return Result(false, "IPA gốc không đạt kiểm tra cấu trúc: ${input.message}")
        }
        val output = validate(outputIpa)
        if (!output.valid) {
            return Result(false, "IPA đầu ra không đạt kiểm tra cấu trúc: ${output.message}")
        }

        val originalBundle = input.metadata?.bundleId?.takeIf { it.isNotBlank() }
            ?: return Result(false, "Không đọc được Bundle ID của IPA gốc.")
        val signedBundle = output.metadata?.bundleId?.takeIf { it.isNotBlank() }
            ?: return Result(false, "Không đọc được Bundle ID của IPA sau khi ký.")
        if (originalBundle != signedBundle) {
            return Result(false, "Bundle ID đã thay đổi sau khi ký: $originalBundle → $signedBundle.")
        }
        if (!output.hasCodeResources) {
            return Result(false, "IPA đầu ra thiếu _CodeSignature/CodeResources.")
        }
        if (!output.hasProvisioningProfile) {
            return Result(false, "IPA đầu ra thiếu embedded.mobileprovision.")
        }

        return Result(
            true,
            "Đạt kiểm tra cấu trúc sau ký: ZIP đọc được, Bundle ID giữ nguyên, CodeResources và embedded.mobileprovision đều hiện diện. Chưa xác minh mật mã chữ ký Apple.",
            signedBundle
        )
    }

    private fun validate(file: File): IpaArchiveValidator.Result =
        file.inputStream().buffered().use { stream ->
            IpaArchiveValidator.validate(
                stream,
                maxEntries = MAX_ENTRIES,
                maxUncompressedBytes = MAX_UNCOMPRESSED_BYTES
            )
        }

    private const val MAX_IPA_BYTES = 2L * 1024L * 1024L * 1024L
    private const val MAX_UNCOMPRESSED_BYTES = 2L * 1024L * 1024L * 1024L
    private const val MAX_ENTRIES = 100_000
}
