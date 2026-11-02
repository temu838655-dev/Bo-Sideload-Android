package com.bosideload.android

import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile

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

        val executable = output.metadata?.executable?.takeIf { it.isNotBlank() }
            ?: return Result(false, "Info.plist không khai báo CFBundleExecutable.")
        if (executable.contains("/") || executable.contains("\\\\") || executable == "." || executable == "..") {
            return Result(false, "CFBundleExecutable chứa tên tệp không an toàn.")
        }
        val machO = inspectMainExecutable(outputIpa, executable)
            ?: return Result(false, "Không tìm thấy hoặc không đọc được tệp thực thi chính trong Payload.")
        if (!machO.isMachO) {
            return Result(false, "Tệp thực thi chính không phải Mach-O hợp lệ: ${machO.message}")
        }
        if (!machO.hasCodeSignatureCommand) {
            return Result(false, "Mach-O chính không có LC_CODE_SIGNATURE sau khi ký.")
        }

        return Result(
            true,
            "Đạt kiểm tra cấu trúc sau ký: ZIP đọc được, Bundle ID giữ nguyên, CodeResources và embedded.mobileprovision hiện diện; Mach-O chính có LC_CODE_SIGNATURE. Chưa xác minh mật mã CodeDirectory/CMS hoặc chuỗi tin cậy Apple.",
            signedBundle
        )
    }

    private fun inspectMainExecutable(file: File, executable: String): MachOInspector.Result? {
        return try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                var prefix: String? = null
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.matches(Regex("^Payload/[^/]+\\.app/Info\\.plist$"))) {
                        prefix = entry.name.removeSuffix("Info.plist")
                        break
                    }
                }
                val appPrefix = prefix ?: return null
                val entry = zip.getEntry(appPrefix + executable) ?: return null
                if (entry.isDirectory || entry.size > MAX_EXECUTABLE_BYTES) return null
                val output = ByteArrayOutputStream()
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size().toLong() + count > MAX_EXECUTABLE_BYTES) return null
                        output.write(buffer, 0, count)
                    }
                }
                MachOInspector.inspect(output.toByteArray())
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun validate(file: File): IpaArchiveValidator.Result =
        file.inputStream().buffered().use { stream ->
            IpaArchiveValidator.validate(
                stream,
                maxEntries = MAX_ENTRIES,
                maxUncompressedBytes = MAX_UNCOMPRESSED_BYTES
            )
        }

    private const val MAX_EXECUTABLE_BYTES = 512L * 1024L * 1024L
    private const val MAX_IPA_BYTES = 2L * 1024L * 1024L * 1024L
    private const val MAX_UNCOMPRESSED_BYTES = 2L * 1024L * 1024L * 1024L
    private const val MAX_ENTRIES = 100_000
}
