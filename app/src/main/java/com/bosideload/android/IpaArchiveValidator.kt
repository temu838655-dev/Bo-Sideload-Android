package com.bosideload.android

import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Checks basic IPA container structure only. It does not verify code signatures,
 * provisioning profiles, certificates, or whether iOS can install the package.
 */
internal object IpaArchiveValidator {
    data class Result(val valid: Boolean, val message: String)

    fun validate(
        input: InputStream,
        maxEntries: Int = 100_000,
        maxUncompressedBytes: Long = 512L * 1024L * 1024L
    ): Result {
        return try {
            ZipInputStream(input.buffered()).use { zip ->
                var entryCount = 0
                var totalBytes = 0L
                var foundInfoPlist = false
                val buffer = ByteArray(16 * 1024)

                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > maxEntries) {
                        return Result(false, "Tệp có quá nhiều mục ZIP; đã dừng để bảo vệ thiết bị.")
                    }

                    val path = entry.name.replace('\\\\', '/')
                    val segments = path.split('/')
                    if (path.startsWith('/') || segments.any { it == ".." } ||
                        Regex("^[A-Za-z]:").containsMatchIn(path)
                    ) {
                        return Result(false, "Tệp chứa đường dẫn ZIP không an toàn.")
                    }

                    if (INFO_PLIST_PATTERN.matches(path)) foundInfoPlist = true

                    if (!entry.isDirectory) {
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            totalBytes += read
                            if (totalBytes > maxUncompressedBytes) {
                                return Result(false, "Dữ liệu giải nén vượt giới hạn an toàn.")
                            }
                        }
                    }
                    zip.closeEntry()
                }

                when {
                    entryCount == 0 -> Result(false, "Không tìm thấy nội dung ZIP hợp lệ.")
                    !foundInfoPlist -> Result(
                        false,
                        "Không tìm thấy Payload/<ứng dụng>.app/Info.plist. Đây có thể không phải IPA chuẩn."
                    )
                    else -> Result(
                        true,
                        "Đã đọc cấu trúc ZIP và tìm thấy Info.plist trong thư mục Payload. Cấu trúc IPA cơ bản có vẻ hợp lệ."
                    )
                }
            }
        } catch (e: Exception) {
            Result(false, "Không thể đọc hoặc giải mã tệp ZIP: ${e.message ?: "lỗi không xác định"}")
        }
    }

    private val INFO_PLIST_PATTERN = Regex("^Payload/[^/]+\\\\.app/Info\\\\.plist$")
}
