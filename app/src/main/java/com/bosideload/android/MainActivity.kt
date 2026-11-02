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
            text = "Bo Sideload hiện kiểm tra cấu trúc ZIP cơ bản và tìm Info.plist. Ứng dụng chưa xác minh chữ ký, chứng chỉ hoặc khả năng cài đặt IPA."
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
            append("\\nTên: ").append(name)
            append("\\nDung lượng: ").append(if (size >= 0) formatSize(size) else "Không xác định")
        }
        chooseButton.isEnabled = false

        Thread {
            val result = try {
                val input = contentResolver.openInputStream(uri)
                if (input == null) {
                    IpaArchiveValidator.Result(false, "Không thể mở tệp để đọc.")
                } else {
                    input.use { IpaArchiveValidator.validate(it) }
                }
            } catch (e: Exception) {
                IpaArchiveValidator.Result(false, "Không thể đọc tệp: ${e.message ?: "lỗi không xác định"}")
            }

            runOnUiThread {
                chooseButton.isEnabled = true
                fileInfo.text = buildString {
                    append(if (isIpa) "KẾT QUẢ KIỂM TRA IPA" else "KẾT QUẢ KIỂM TRA TỆP")
                    append("\\nTên: ").append(name)
                    append("\\nDung lượng: ").append(if (size >= 0) formatSize(size) else "Không xác định")
                    append("\\n\\n").append(result.message)
                    append("\\n\\nLưu ý: Đây không phải kiểm tra chữ ký hoặc khả năng cài đặt.")
                }
            }
        }.start()
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
    }
}
