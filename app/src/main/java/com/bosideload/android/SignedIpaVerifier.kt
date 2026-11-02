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
        if (executable.contains("/") || executable.contains("\\") || executable == "." || executable == "..") {
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
        if (!machO.hasCodeDirectory) {
            return Result(false, "Không tìm thấy CodeDirectory có cấu trúc hợp lệ trong chữ ký Mach-O.")
        }

        return Result(
            true,
            "Đạt kiểm tra sau ký ở mức cấu trúc: ZIP đọc được, Bundle ID giữ nguyên, embedded.mobileprovision hiện diện; CodeDirectory chính khớp hash trang mã máy; Info.plist ở special slot 1, CodeResources ở special slot 3 và Entitlements ở special slot 5 (khi có blob Entitlements). Đây chỉ là kiểm tra cấu trúc và hash được hỗ trợ; chưa xác minh từng tài nguyên bên trong CodeResources, CMS, chuỗi chứng chỉ, thu hồi chứng chỉ, CodeDirectory phụ hoặc độ tin cậy Apple.",
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
                val infoEntry = zip.getEntry(appPrefix + "Info.plist") ?: return null
                if (infoEntry.isDirectory || infoEntry.size !in 1..MAX_INFO_PLIST_BYTES) return null
                val infoOutput = ByteArrayOutputStream()
                zip.getInputStream(infoEntry).use { input ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (infoOutput.size().toLong() + count > MAX_INFO_PLIST_BYTES) return null
                        infoOutput.write(buffer, 0, count)
                    }
                }
                val infoBytes = infoOutput.toByteArray()

                val sealEntry = zip.getEntry(appPrefix + "_CodeSignature/CodeResources") ?: return null
                if (sealEntry.isDirectory || sealEntry.size !in 1..MAX_CODE_RESOURCES_BYTES) return null
                val sealOutput = ByteArrayOutputStream()
                zip.getInputStream(sealEntry).use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (sealOutput.size().toLong() + count > MAX_CODE_RESOURCES_BYTES) return null
                        sealOutput.write(buffer, 0, count)
                    }
                }
                val sealBytes = sealOutput.toByteArray()

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
                MachOInspector.inspect(output.toByteArray(), sealBytes, infoBytes)
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
    private const val MAX_CODE_RESOURCES_BYTES = 16L * 1024L * 1024L
    private const val MAX_INFO_PLIST_BYTES = 1L * 1024L * 1024L
    private const val MAX_IPA_BYTES = 2L * 1024L * 1024L * 1024L
    private const val MAX_UNCOMPRESSED_BYTES = 2L * 1024L * 1024L * 1024L
    private const val MAX_ENTRIES = 100_000
}
