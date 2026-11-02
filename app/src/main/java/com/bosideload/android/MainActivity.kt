package com.bosideload.android

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.zip.ZipInputStream

class MainActivity : Activity() {
    private lateinit var fileInfo: TextView
    private lateinit var chooseButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 52, 28, 28)
            setBackgroundColor(Color.rgb(9, 15, 29))
        }
        val title = TextView(this).apply {
            text = "Bo Sideload"
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Trình quản lý và kiểm tra tệp IPA trên Android"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 30)
        }
        fileInfo = TextView(this).apply {
            text = "Chưa chọn tệp IPA"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16, 24, 16, 24)
            setBackgroundColor(Color.rgb(24, 35, 55))
        }
        chooseButton = Button(this).apply {
            text = "Chọn tệp IPA"
            setOnClickListener {
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(picker, PICK_IPA)
            }
        }
        val warning = TextView(this).apply {
            text = "Kiểm tra này chỉ xác nhận cấu trúc ZIP cơ bản và sự hiện diện của Payload/<ứng dụng>.app/Info.plist. Nó KHÔNG xác minh chữ ký, chứng chỉ, khả năng cài đặt hoặc thay thế quy trình ký và cài ứng dụng Apple."
            textSize = 13f
            setTextColor(Color.rgb(190, 200, 215))
            gravity = Gravity.CENTER
            setPadding(8, 26, 8, 0)
        }
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(subtitle)
        root.addView(fileInfo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(chooseButton)
        root.addView(warning)
        setContentView(root)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_IPA || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Temporary read access is sufficient for this validation.
        }
        val name = queryName(uri)
        val size = querySize(uri)
        val isIpa = name.lowercase(Locale.ROOT).endsWith(".ipa")
        fileInfo.text = buildString {
            append(if (isIpa) "Đang kiểm tra tệp IPA…" else "Đang kiểm tra tệp được chọn…")
            append("\nTên: ").append(name)
            append("\nDung lượng: ").append(if (size >= 0) formatSize(size) else "Không xác định")
        }
        chooseButton.isEnabled = false
        Thread {
            val result = validateIpa(uri)
            runOnUiThread {
                chooseButton.isEnabled = true
                fileInfo.text = buildString {
                    append(if (isIpa) "KẾT QUẢ KIỂM TRA IPA" else "KẾT QUẢ KIỂM TRA TỆP")
                    append("\nTên: ").append(name)
                    append("\nDung lượng: ").append(if (size >= 0) formatSize(size) else "Không xác định")
                    append("\n\n").append(result.message)
                    append("\n\nLưu ý: Đây không phải kiểm tra chữ ký hoặc khả năng cài đặt.")
                }
            }
        }.start()
    }

    private data class ValidationResult(val valid: Boolean, val message: String)

    private fun validateIpa(uri: Uri): ValidationResult {
        return try {
            val input = contentResolver.openInputStream(uri)
                ?: return ValidationResult(false, "Không thể mở tệp để đọc.")
            input.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var entryCount = 0
                    var totalBytes = 0L
                    var foundInfoPlist = false
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entryCount++
                        if (entryCount > MAX_ENTRIES) {
                            return ValidationResult(false, "Tệp có quá nhiều mục ZIP; đã dừng để bảo vệ thiết bị.")
                        }
                        val path = entry.name.replace('\\', '/')
                        if (path.startsWith('/') || path.split('/').any { it == ".." }) {
                            return ValidationResult(false, "Tệp chứa đường dẫn ZIP không an toàn.")
                        }
                        if (INFO_PLIST_PATTERN.matches(path)) foundInfoPlist = true
                        if (!entry.isDirectory) {
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                totalBytes += read
                                if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                                    return ValidationResult(false, "Dữ liệu giải nén vượt giới hạn an toàn 512 MB.")
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                    if (entryCount == 0) return ValidationResult(false, "Không tìm thấy nội dung ZIP hợp lệ.")
                    if (!foundInfoPlist) {
                        ValidationResult(false, "Không tìm thấy Payload/<ứng dụng>.app/Info.plist. Đây có thể không phải IPA chuẩn.")
                    } else {
                        ValidationResult(true, "Đã đọc cấu trúc ZIP và tìm thấy Info.plist trong thư mục Payload. Cấu trúc IPA cơ bản có vẻ hợp lệ.")
                    }
                }
            }
        } catch (e: Exception) {
            ValidationResult(false, "Không thể đọc hoặc giải mã tệp ZIP: ${e.message ?: "lỗi không xác định"}")
        }
    }

    private fun queryName(uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) ?: "Không rõ tên"
            } else "Không rõ tên"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "Không rõ tên"
        } finally {
            cursor?.close()
        }
    }

    private fun querySize(uri: Uri): Long {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } catch (_: Exception) {
            -1L
        } finally {
            cursor?.close()
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
        return String.format(Locale.getDefault(), "%.1f MB", kb / 1024.0)
    }

    companion object {
        private const val PICK_IPA = 100
        private const val MAX_ENTRIES = 100_000
        private const val MAX_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L
        private val INFO_PLIST_PATTERN = Regex("^Payload/[^/]+\\.app/Info\\.plist$")
    }
}