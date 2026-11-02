package com.bosideload.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class AppleSigningGuideActivity : Activity() {
    private val muted = 0xFFB8C5D8.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val blue = 0xFF70A7FF.toInt()
    private lateinit var importedFileInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
            setBackgroundColor(0xFF080E1B.toInt())
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        }
        page.addView(text("BO SIDELOAD • APPLE SIGNING", 13, blue, true), full())
        page.addView(text("Trung tâm Apple ID miễn phí", 25, white, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(14))
        }, full())
        page.addView(text(
            "Chế độ hiện tại: Personal Team. Bo Sideload không đăng nhập Apple ID thay bạn và không tự tạo chứng chỉ Apple. Hãy dùng Xcode trên máy Mac của chính bạn để Apple tạo certificate/profile hợp lệ.",
            14, muted, false
        ), full())
        page.addView(section("iPhone / iPad", "Có thể phát triển và thử ứng dụng cá nhân qua Xcode Personal Team. Giới hạn Apple công bố: tối đa 10 App ID và 3 thiết bị; App ID và profile hết hạn sau 7 ngày; tối đa 3 ứng dụng trên mỗi thiết bị. Sau khi hết hạn phải ký và cài lại."), full())
        page.addView(section("MacBook / macOS", "Apple ID miễn phí không cấp Developer ID Application/Installer và không mở quyền notarization. Bo Sideload hiện chỉ có thể kiểm tra tệp/gói và hướng dẫn; không thể ký hoặc notarize bản macOS để phân phối rộng rãi."), full())
        page.addView(section("Apple TV / tvOS", "Ứng dụng tvOS cần build bằng Xcode, chữ ký và provisioning profile dành riêng cho tvOS. Không dùng chứng chỉ iOS tùy ý để ký tvOS. Hỗ trợ Personal Team phụ thuộc Xcode, phiên bản tvOS và thiết bị; Bo Sideload chưa thể tự cấp profile hoặc cài trực tiếp."), full())
        page.addView(text("NHẬP HỒ SƠ ĐỂ KIỂM TRA", 17, white, true).apply {
            setPadding(0, dp(24), 0, dp(8))
        }, full())
        page.addView(text(
            "Chọn tệp provisioning profile (.mobileprovision / .provisionprofile) hoặc chứng chỉ công khai (.cer). Bo Sideload có thể giải mã phần CMS/plist để đọc metadata, nhưng chưa xác minh chữ ký CMS hoặc chuỗi tin cậy Apple.",
            13, muted, false
        ), full())
        page.addView(Button(this).apply {
            text = "＋  Chọn profile / certificate"
            isAllCaps = false
            setOnClickListener { pickAppleFile() }
        }, full())
        importedFileInfo = text("Chưa chọn tệp.", 13, muted, false).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFF162135.toInt())
        }
        page.addView(importedFileInfo, full())
        page.addView(text(
            "BẢO MẬT: Không gửi Apple ID, mật khẩu, mã xác minh hai yếu tố hoặc khóa riêng P12 vào cuộc trò chuyện. Không dùng certificate/profile của người khác. Bo Sideload không lưu mật khẩu Apple ID.",
            13, 0xFFFFD28A.toInt(), true
        ).apply { setPadding(0, dp(18), 0, dp(10)) }, full())
        page.addView(Button(this).apply {
            text = "Quay lại Bo Sideload"
            isAllCaps = false
            setOnClickListener { finish() }
        }, full())
        setContentView(scroll)
    }

    private fun pickAppleFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, 701)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 701 || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val name = queryName(uri)
        val type = when {
            name.endsWith(".mobileprovision", true) -> "Provisioning profile iOS / tvOS"
            name.endsWith(".provisionprofile", true) -> "Provisioning profile macOS"
            name.endsWith(".cer", true) || name.endsWith(".pem", true) -> "Certificate công khai"
            name.endsWith(".p12", true) -> "P12 chứa khóa riêng — không phân tích trong màn hình này"
            else -> "Tệp chưa phân loại"
        }
        importedFileInfo.text = "Đang đọc $name…"
        if (name.endsWith(".mobileprovision", true) ||
            name.endsWith(".provisionprofile", true) ||
            name.endsWith(".plist", true)
        ) {
            Thread {
                val result = try {
                    val bytes = contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 10 * 1024 * 1024) { "Tệp vượt giới hạn 10 MB." }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: throw IllegalStateException("Không mở được tệp.")
                    val info = AppleProvisioningProfileReader.read(bytes)
                    val expired = info.expirationDate?.before(java.util.Date()) == true
                    val platformNote = when {
                        info.platforms.any { it.equals("tvOS", true) } -> "Profile có khai báo tvOS."
                        info.platforms.any { it.equals("macOS", true) } -> "Profile có khai báo macOS."
                        info.platforms.any { it.equals("iOS", true) } -> "Profile có khai báo iOS."
                        else -> "Không xác định được nền tảng từ trường Platform."
                    }
                    buildString {
                        appendLine("Tệp: $name")
                        appendLine("Loại: $type")
                        appendLine(platformNote)
                        appendLine()
                        appendLine(info.summary())
                        appendLine()
                        append(if (expired) "CẢNH BÁO: Profile đã hết hạn." else "Profile chưa quá hạn theo ngày ghi trong tệp.")
                        appendLine()
                        append("Lưu ý: đã đọc nội dung CMS/plist; chưa xác minh chữ ký CMS, chuỗi tin cậy Apple, thu hồi chứng chỉ hoặc khả năng cài trên thiết bị.")
                    }
                } catch (error: Exception) {
                    "Không đọc được profile: ${error.message ?: "định dạng không hợp lệ"}\n\nTệp: $name"
                }
                runOnUiThread {
                    importedFileInfo.text = result
                    Toast.makeText(this, "Đã phân tích nội dung profile (chưa xác minh chữ ký).", Toast.LENGTH_SHORT).show()
                }
            }.start()
        } else {
            importedFileInfo.text = "Tên: $name\nLoại: $type\n\nChỉ hỗ trợ đọc nội dung provisioning profile ở phiên bản này. Chứng chỉ .cer/.pem và P12 chưa được xác minh."
        }
    }

    private fun queryName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) ?: "Không rõ tên" else "Không rõ tên"
            } ?: "Không rõ tên"
        } catch (_: Exception) { uri.lastPathSegment ?: "Không rõ tên" }
    }

    private fun section(title: String, body: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        setBackgroundColor(0xFF162135.toInt())
        addView(text(title, 16, white, true), full())
        addView(text(body, 13, muted, false).apply { setPadding(0, dp(7), 0, 0) }, full())
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun full() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(10) }
}
