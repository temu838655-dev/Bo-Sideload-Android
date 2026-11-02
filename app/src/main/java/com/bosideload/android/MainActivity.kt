package com.bosideload.android

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var resultPanel: TextView
    private lateinit var recentPanel: LinearLayout
    private lateinit var importButton: Button
    private lateinit var store: RecentIpaStore
    private val pageColor = Color.rgb(8, 14, 27)
    private val panelColor = Color.rgb(22, 33, 53)
    private val secondaryText = Color.rgb(174, 188, 208)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RecentIpaStore(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
            setBackgroundColor(pageColor)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(pageColor)
            addView(page)
        }
        page.addView(label("BO SIDELOAD", 13f, Color.rgb(89, 156, 255), true), fullWidth())
        page.addView(label("Quản lý IPA dễ dàng", 27f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }, fullWidth())
        page.addView(label("Thư viện và công cụ kiểm tra tệp IPA trên Android", 14f, secondaryText, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        }, fullWidth())

        importButton = Button(this).apply {
            text = "＋  Chọn tệp IPA"
            isAllCaps = false
            textSize = 16f
            setOnClickListener { chooseIpa() }
        }
        page.addView(importButton, fullWidth())

        resultPanel = label("Chưa có tệp nào được kiểm tra.\nChọn một tệp IPA để bắt đầu.", 14f, Color.WHITE, false).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(panelColor)
        }
        page.addView(resultPanel, fullWidth().apply { topMargin = dp(14) })
        page.addView(label("Tệp đã mở gần đây", 18f, Color.WHITE, true).apply {
            setPadding(0, dp(28), 0, dp(12))
        }, fullWidth())
        recentPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(recentPanel, fullWidth())

        page.addView(label("TRUNG TÂM CÔNG CỤ", 18f, Color.WHITE, true).apply {
            setPadding(0, dp(28), 0, dp(12))
        }, fullWidth())
        addToolCard(
            page,
            "Xcode — Công cụ phát triển iOS của Apple",
            "Chạy trên macOS. Dùng để tạo dự án, quản lý signing identity / provisioning profile và cài bản build lên thiết bị đã kết nối. Không thể chạy Xcode trực tiếp trên Android.",
            "Mở trang Apple Xcode",
            "https://developer.apple.com/xcode/"
        )
        addToolCard(
            page,
            "Cydia Impactor — Công cụ đời cũ",
            "Công cụ sideload IPA trên máy tính. Trang dự án lưu ý từ cuối năm 2019 việc cài IPA cần tài khoản Apple Developer trả phí. Đây không phải chức năng chạy bên trong Bo Sideload; hãy thận trọng với bản tải từ trang không chính thức.",
            "Mở trang Cydia Impactor",
            "https://www.cydiaimpactor.com/"
        )
        page.addView(label(
            "Lưu ý: ứng dụng chỉ kiểm tra cấu trúc ZIP cơ bản. Chưa xác minh chữ ký, chứng chỉ hoặc khả năng cài đặt IPA lên iPhone.",
            12f, secondaryText, false
        ).apply { setPadding(dp(4), dp(20), dp(4), 0) }, fullWidth())
        setContentView(scroll)
        renderRecent()
    }

    private fun addToolCard(
        parent: LinearLayout,
        title: String,
        description: String,
        buttonText: String,
        url: String
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(12))
            setBackgroundColor(panelColor)
        }
        card.addView(label(title, 16f, Color.WHITE, true), fullWidth())
        card.addView(label(description, 13f, secondaryText, false).apply {
            setPadding(0, dp(8), 0, dp(10))
        }, fullWidth())
        card.addView(Button(this).apply {
            text = buttonText
            isAllCaps = false
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Không thể mở liên kết.", Toast.LENGTH_SHORT).show()
                }
            }
        }, fullWidth())
        parent.addView(card, fullWidth().apply { bottomMargin = dp(10) })
    }

    private fun chooseIpa() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, PICK_FILE)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // The provider may grant temporary access only.
        }
        inspect(uri, true)
    }

    private fun inspect(uri: Uri, remember: Boolean) {
        val name = displayName(uri)
        val size = fileSize(uri)
        if (remember) {
            store.add(RecentIpa(uri.toString(), name, size))
            renderRecent()
        }
        resultPanel.text = "ĐANG KIỂM TRA\nTên: $name\nDung lượng: ${formatSize(size)}\n\nĐang đọc cấu trúc tệp…"
        importButton.isEnabled = false
        Thread {
            val result = try {
                val stream = contentResolver.openInputStream(uri)
                if (stream == null) IpaArchiveValidator.Result(false, "Không thể mở tệp.")
                else stream.use { IpaArchiveValidator.validate(it) }
            } catch (error: SecurityException) {
                IpaArchiveValidator.Result(
                    false,
                    "Android không còn cấp quyền đọc tệp này. Hãy chọn lại IPA từ bộ chọn tệp để cấp quyền truy cập mới."
                )
            } catch (error: Exception) {
                IpaArchiveValidator.Result(false, "Không thể đọc tệp: ${error.message ?: "lỗi không xác định"}")
            }
            runOnUiThread {
                importButton.isEnabled = true
                resultPanel.text = buildString {
                    append(if (result.valid) "✓ CẤU TRÚC CƠ BẢN ĐẠT" else "⚠ KIỂM TRA CHƯA ĐẠT")
                    append("\nTên: ").append(name)
                    append("\nDung lượng: ").append(formatSize(size))
                    append("\n\n").append(result.message)
                    val info = result.metadata
                    if (info != null) {
                        append("\n\nTHÔNG TIN ỨNG DỤNG")
                        info.appName?.let { append("\nTên ứng dụng: ").append(it) }
                        info.bundleId?.let { append("\nBundle ID: ").append(it) }
                        info.shortVersion?.let { append("\nPhiên bản: ").append(it) }
                        info.buildVersion?.let { append("\nMã bản dựng: ").append(it) }
                    } else if (result.valid) {
                        append("\n\nKhông đọc được metadata. Info.plist có thể bị lỗi hoặc dùng định dạng không được hỗ trợ.")
                    }
                    append("\n\nKIỂM TRA TỆP KÝ")
                    append("\nCodeResources: ").append(if (result.hasCodeResources) "Có" else "Không tìm thấy")
                    append("\nembedded.mobileprovision: ").append(if (result.hasProvisioningProfile) "Có" else "Không tìm thấy")
                    append("\nLưu ý: chỉ nhận diện tệp, không xác minh chữ ký mật mã hay chứng chỉ.")
                }
            }
        }.start()
    }

    private fun renderRecent() {
        recentPanel.removeAllViews()
        val files = store.all()
        if (files.isEmpty()) {
            recentPanel.addView(label("Chưa có tệp gần đây", 14f, secondaryText, false).apply {
                setPadding(dp(14), dp(18), dp(14), dp(18))
                setBackgroundColor(panelColor)
            }, fullWidth())
            return
        }
        files.forEach { file ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(10))
                setBackgroundColor(panelColor)
            }
            card.addView(label(file.name, 15f, Color.WHITE, true), fullWidth())
            card.addView(label(formatSize(file.sizeBytes), 12f, secondaryText, false).apply {
                setPadding(0, dp(4), 0, dp(6))
            }, fullWidth())
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            actions.addView(Button(this).apply {
                text = "Kiểm tra"
                isAllCaps = false
                setOnClickListener { inspect(Uri.parse(file.uri), false) }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) })
            actions.addView(Button(this).apply {
                text = "Chia sẻ"
                isAllCaps = false
                setOnClickListener { share(file) }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) })
            card.addView(actions, fullWidth())
            card.addView(Button(this).apply {
                text = "Bỏ khỏi danh sách"
                isAllCaps = false
                setOnClickListener {
                    store.remove(file.uri)
                    renderRecent()
                    Toast.makeText(this@MainActivity, "Đã bỏ khỏi danh sách gần đây", Toast.LENGTH_SHORT).show()
                }
            }, fullWidth())
            recentPanel.addView(card, fullWidth().apply { bottomMargin = dp(10) })
        }
    }

    private fun share(file: RecentIpa) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, Uri.parse(file.uri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Chia sẻ tệp IPA"))
        } catch (_: Exception) {
            Toast.makeText(this, "Không thể chia sẻ tệp này.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun displayName(uri: Uri): String {
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

    private fun fileSize(uri: Uri): Long {
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
        if (bytes < 0) return "Không rõ dung lượng"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
        return String.format(Locale.getDefault(), "%.1f MB", kb / 1024.0)
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    companion object {
        private const val PICK_FILE = 100
    }
}
