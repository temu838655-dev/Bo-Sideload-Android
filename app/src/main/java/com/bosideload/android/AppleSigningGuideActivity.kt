package com.bosideload.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.text.InputType
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class AppleSigningGuideActivity : Activity() {
    private val muted = 0xFFB8C5D8.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val blue = 0xFF70A7FF.toInt()
    private lateinit var importedFileInfo: TextView
    private var profileCertificateFingerprints: Set<String> = emptySet()
    private var profileTeamIds: Set<String> = emptySet()
    private var profileBundleIdentifier: String? = null

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
        profileCertificateFingerprints = emptySet()
        profileTeamIds = emptySet()
        profileBundleIdentifier = null
        val type = when {
            name.endsWith(".mobileprovision", true) -> "Provisioning profile iOS / tvOS"
            name.endsWith(".provisionprofile", true) -> "Provisioning profile macOS"
            name.endsWith(".cer", true) || name.endsWith(".pem", true) -> "Certificate công khai"
            name.endsWith(".p12", true) -> "P12 chứa khóa riêng — đọc cục bộ bằng mật khẩu"
            else -> "Tệp chưa phân loại"
        }
        importedFileInfo.text = "Đang đọc $name…"
        if (name.endsWith(".mobileprovision", true) ||
            name.endsWith(".provisionprofile", true) ||
            name.endsWith(".plist", true)
        ) {
            Thread {
                var parsedProfile: AppleProvisioningProfileReader.ProfileInfo? = null
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
                    parsedProfile = info
                    val expiryStatus = when {
                        info.expirationDate == null -> "Không xác định được ngày hết hạn trong profile."
                        info.expirationDate.before(java.util.Date()) -> "CẢNH BÁO: Profile đã hết hạn."
                        else -> "Profile chưa quá hạn theo ngày ghi trong tệp."
                    }
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
                        append(expiryStatus)
                        appendLine()
                        append("Lưu ý: đã đọc nội dung CMS/plist; chưa xác minh chữ ký CMS, chuỗi tin cậy Apple, thu hồi chứng chỉ hoặc khả năng cài trên thiết bị.")
                    }
                } catch (error: Exception) {
                    "Không đọc được profile: ${error.message ?: "định dạng không hợp lệ"}\n\nTệp: $name"
                }
                runOnUiThread {
                    parsedProfile?.let { parsed ->
                        profileCertificateFingerprints = parsed.developerCertificateSha256.toSet()
                        profileTeamIds = parsed.teamIdentifiers.toSet()
                        profileBundleIdentifier = parsed.bundleIdentifier
                    }
                    importedFileInfo.text = result
                    Toast.makeText(this, "Đã phân tích nội dung profile (chưa xác minh chữ ký).", Toast.LENGTH_SHORT).show()
                }
            }.start()
        } else if (name.endsWith(".cer", true) || name.endsWith(".pem", true)) {
            Thread {
                val result = try {
                    val bytes = contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8 * 1024)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 2 * 1024 * 1024) { "Certificate vượt giới hạn 2 MB." }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: throw IllegalStateException("Không mở được certificate.")
                    val info = AppleCertificateReader.read(bytes)
                    "Tệp: $name\nLoại: X.509 public certificate\n\n${info.summary()}\n\nLưu ý: chưa xác minh chuỗi tin cậy Apple, thu hồi chứng chỉ hoặc khóa riêng tương ứng."
                } catch (error: Exception) {
                    "Không đọc được certificate: ${error.message ?: "định dạng không hợp lệ"}\n\nTệp: $name"
                }
                runOnUiThread {
                    importedFileInfo.text = result
                    Toast.makeText(this, "Đã đọc thông tin certificate công khai.", Toast.LENGTH_SHORT).show()
                }
            }.start()
        } else if (name.endsWith(".p12", true)) {
            inspectP12(uri, name)
        } else {
            importedFileInfo.text = "Tên: $name\nLoại: $type\n\nĐịnh dạng này chưa được hỗ trợ."
        }
    }

    private fun inspectP12(uri: Uri, name: String) {
        val passwordInput = EditText(this).apply {
            hint = "Mật khẩu P12 (chỉ nhập trên thiết bị)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Mở certificate P12")
            .setMessage("Mật khẩu chỉ được dùng trong bộ nhớ để đọc tệp trên thiết bị. Bo Sideload không gửi mật khẩu đi đâu và không xuất khóa riêng.")
            .setView(passwordInput)
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Đọc P12") { _, _ ->
                val password = passwordInput.text.toString().toCharArray()
                importedFileInfo.text = "Đang đọc P12 cục bộ…"
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
                                require(total <= 10 * 1024 * 1024) { "P12 vượt giới hạn 10 MB." }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: throw IllegalStateException("Không mở được P12.")
                        val entries = AppleP12Reader.read(bytes, password)
                        buildString {
                            appendLine("Tệp: $name")
                            appendLine("Số certificate: ${entries.size}")
                            entries.forEachIndexed { index, entry ->
                                appendLine()
                                appendLine("Mục ${index + 1}: ${entry.alias}")
                                appendLine("Subject: ${entry.subject}")
                                appendLine("Issuer: ${entry.issuer}")
                                appendLine("Có private key: ${if (entry.hasPrivateKey) "Có" else "Không"}")
                                appendLine("Hết hạn: ${entry.notAfter}")
                                appendLine("SHA-256: ${entry.sha256Fingerprint}")
                                if (profileCertificateFingerprints.isNotEmpty()) {
                                    appendLine(if (entry.sha256Fingerprint in profileCertificateFingerprints)
                                        "KẾT QUẢ: Certificate khớp một certificate được nhúng trong profile."
                                    else "CẢNH BÁO: Fingerprint không khớp certificate nào trong profile đã chọn.")
                                }
                                val teamMatch = profileTeamIds.any { team ->
                                    entry.subject.contains("OU=$team") || entry.subject.contains("OU=$team,")
                                }
                                if (profileTeamIds.isNotEmpty()) {
                                    appendLine(if (teamMatch) "Team ID: có dấu hiệu khớp OU trong Subject."
                                        else "Team ID: chưa xác nhận được OU khớp Team ID của profile.")
                                }
                            }
                            appendLine()
                            append("Lưu ý: đọc cấu trúc PKCS#12 và certificate; chưa xác minh chuỗi tin cậy Apple, trạng thái thu hồi hoặc quyền ký thực tế.")
                        }
                    } catch (error: Exception) {
                        "Không mở được P12: ${error.message ?: "sai mật khẩu hoặc tệp không hợp lệ"}"
                    } finally {
                        password.fill('\u0000')
                    }
                    runOnUiThread {
                        importedFileInfo.text = result
                        Toast.makeText(this, "Đã xử lý P12 trên thiết bị.", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            .show()
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
