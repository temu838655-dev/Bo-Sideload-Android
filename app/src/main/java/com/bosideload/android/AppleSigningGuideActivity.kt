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
        page.addView(text("Trung tâm Apple Signing", 25, white, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(14))
        }, full())
        page.addView(text(
            "Bo Sideload hỗ trợ kiểm tra và ký IPA cục bộ bằng P12 + provisioning profile hợp lệ. Apple ID miễn phí (Personal Team) có thể dùng nếu bạn đã có bộ hồ sơ do Xcode tạo và xuất ra; luồng tạo tự động qua App Store Connect API cần tài khoản Developer trả phí cùng Team API Key đủ quyền. Ứng dụng không yêu cầu mật khẩu Apple ID hoặc mã xác minh hai yếu tố.",
            14, muted, false
        ), full())
        page.addView(section("Apple ID miễn phí • iPhone / iPad", "Có thể ký IPA trên Bo Sideload nếu bạn đã có P12 chứa private key và provisioning profile Personal Team hợp lệ do Xcode tạo. Việc tạo bộ hồ sơ Personal Team thường cần Xcode trên macOS; Bo Sideload không thể tự cấp chứng chỉ miễn phí qua Team API. Hồ sơ miễn phí có thời hạn ngắn (thường 7 ngày) và cần ký/cài lại khi hết hạn."), full())
        page.addView(Button(this).apply {
            text = "Kết nối Apple Developer trả phí bằng Team API Key"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(this@AppleSigningGuideActivity, AppleConnectActivity::class.java))
            }
        }, full())
        page.addView(section("Apple Developer trả phí • iPhone / iPad", "Dùng Apple Development + Development provisioning profile để thử trên thiết bị đã đăng ký; hoặc Apple Distribution + Ad Hoc profile cho phân phối thử nghiệm tới UDID đã đăng ký. Tài khoản phải còn hiệu lực và có quyền Certificates, Identifiers & Profiles phù hợp. Với Team API Key đủ quyền, Bo Sideload có thể đọc tài nguyên và yêu cầu Apple tạo certificate/profile sau khi bạn xác nhận. Không chọn App Store profile để sideload trực tiếp; TestFlight/App Store là luồng phân phối riêng."), full())
        page.addView(section("MacBook / macOS", "Với tài khoản trả phí, có thể dùng Mac Development để thử trên máy đã đăng ký; Mac App Distribution để phân phối qua Mac App Store; hoặc Developer ID Application để ký ứng dụng macOS phân phối ngoài App Store (thường cần notarization). Apple ID miễn phí không cấp các chứng chỉ phân phối này. Bo Sideload hiện chỉ kiểm tra metadata; chưa thay thế quy trình ký/notarize macOS của Xcode."), full())
        page.addView(section("Apple TV / tvOS", "Ứng dụng tvOS cần certificate và provisioning profile dành riêng cho tvOS; không dùng profile iOS để ký tvOS. Tài khoản trả phí có thể tạo hồ sơ Development hoặc phân phối tvOS theo quyền của team và phương thức Apple hỗ trợ. Personal Team miễn phí có giới hạn hơn và phụ thuộc Xcode/thiết bị. Bo Sideload hiện chưa tự tạo profile tvOS hoặc xác nhận cài đặt trực tiếp trên Apple TV."), full())
        page.addView(text("NHẬP HỒ SƠ ĐỂ KIỂM TRA", 17, white, true).apply {
            setPadding(0, dp(24), 0, dp(8))
        }, full())
        page.addView(text(
            "Đối với ký IPA trên Android, chọn P12 có private key và provisioning profile tương ứng. Cả certificate/profile Personal Team miễn phí lẫn Apple Developer trả phí đều có thể dùng nếu đúng loại, còn hạn và khớp Bundle ID, Team ID, certificate, nền tảng và UDID. Profile/certificate công khai chỉ dùng để kiểm tra metadata; không thay thế P12 có private key. Bo Sideload có thể đọc metadata nhưng chưa xác minh đầy đủ chữ ký CMS hoặc chuỗi tin cậy Apple.",
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
            "LƯU Ý: Bo Sideload không phân biệt theo việc tài khoản đã trả phí hay chưa bằng cách hỏi mật khẩu. Ứng dụng kiểm tra chính bộ P12/profile được nhập. Không gửi Apple ID, mật khẩu, mã xác minh hai yếu tố hoặc P12 cho người khác; không dùng certificate/profile của người khác. Mật khẩu P12 chỉ nhập cục bộ khi ký và không được lưu.",
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
