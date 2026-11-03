package com.bosideload.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayOutputStream

/** Paid Apple Developer connection. Never asks for Apple ID password or 2FA. */
class AppleConnectActivity : Activity() {
    private lateinit var keyId: EditText
    private lateinit var issuerId: EditText
    private lateinit var status: TextView
    private lateinit var vault: AppleConnectCredentialVault
    private var p8: String? = null
    private var currentCsr: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = AppleConnectCredentialVault(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
            setBackgroundColor(0xFF080E1B.toInt())
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(page) }
        page.addView(label("APPLE DEVELOPER • TEAM API", 13, 0xFF70A7FF.toInt(), true), full())
        page.addView(label("Kết nối tài khoản trả phí", 24, -1, true), full())
        page.addView(label(
            "Chỉ dành cho Apple Developer Program có Team API Key. Không nhập Apple ID, mật khẩu hoặc mã 2FA. Tệp .p8 được mã hóa bằng Android Keystore và lưu trên thiết bị; không gửi lên GitHub hoặc máy chủ Bo Sideload.",
            14, 0xFFB8C5D8.toInt(), false), full())
        keyId = field("Key ID")
        issuerId = field("Issuer ID")
        page.addView(keyId, full()); page.addView(issuerId, full())
        page.addView(Button(this).apply {
            text = "① Chọn tệp API Key .p8"; isAllCaps = false
            setOnClickListener { chooseP8() }
        }, full())
        page.addView(Button(this).apply {
            text = "② Lưu an toàn và kiểm tra Apple API"; isAllCaps = false
            setOnClickListener { saveAndValidate() }
        }, full())
        page.addView(Button(this).apply {
            text = "③ Tạo khóa ký P-256 và CSR trên thiết bị"; isAllCaps = false
            setOnClickListener { generateLocalCsr() }
        }, full())
        page.addView(Button(this).apply {
            text = "④ Xuất CSR để lưu thành tệp"; isAllCaps = false
            setOnClickListener { exportCsr() }
        }, full())
        page.addView(Button(this).apply {
            text = "Xóa toàn bộ khóa API và khóa ký đã lưu"; isAllCaps = false
            setOnClickListener {
                vault.clear(); p8 = null; keyId.text.clear(); issuerId.text.clear()
                status.text = "Đã xóa khóa API và dữ liệu mã hóa khỏi ứng dụng trên thiết bị."
            }
        }, full())
        status = label("Chưa kết nối. Bước này chỉ đọc danh sách certificate.", 13, 0xFFB8C5D8.toInt(), false).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundColor(0xFF162135.toInt())
        }
        page.addView(status, full())
        page.addView(label(
            "Apple chỉ cho tải private key .p8 một lần. Nếu mất hoặc lộ khóa, hãy thu hồi key và tạo key mới. Individual API Key không dùng được Provisioning endpoints. Bản này chỉ kiểm tra kết nối, chưa tạo certificate/profile hoặc đăng ký UDID.",
            13, 0xFFFFD28A.toInt(), true), full())
        page.addView(Button(this).apply { text = "Quay lại"; isAllCaps = false; setOnClickListener { finish() } }, full())
        currentCsr = runCatching { vault.loadSigningMaterial()?.csrPem }.getOrNull()
        vault.load()?.let {
            keyId.setText(it.keyId); issuerId.setText(it.issuerId)
            status.text = "Đã lưu thông tin API Key được mã hóa trên thiết bị. Chưa xác minh loại key hoặc quyền truy cập; bấm kiểm tra để xác nhận với Apple."
        }
        setContentView(scroll)
    }

    private fun field(hintValue: String) = EditText(this).apply {
        hint = hintValue; isSingleLine = true; setTextColor(-1)
        setHintTextColor(0xFF91A1B8.toInt()); textSize = 15f
        setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundColor(0xFF162135.toInt())
    }

    private fun chooseP8() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, 810)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        if (requestCode == 811) {
            val csr = currentCsr ?: run { status.text = "Chưa có CSR. Hãy tạo CSR trước."; return }
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(csr.toByteArray(Charsets.US_ASCII)) }
                    ?: error("Không mở được tệp đích.")
                status.text = "Đã xuất CSR. Tệp này không chứa khóa riêng."
            } catch (e: Exception) {
                status.text = "Không thể xuất CSR: " + (e.message ?: "lỗi ghi tệp")
            }
            return
        }
        if (requestCode != 810) return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(2048); var total = 0
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    total += n; require(total <= 32768) { "Tệp .p8 vượt 32 KB." }; out.write(buffer, 0, n)
                }
                out.toByteArray()
            } ?: error("Không mở được tệp.")
            val pem = String(bytes, Charsets.US_ASCII); bytes.fill(0)
            require(pem.contains("-----BEGIN PRIVATE KEY-----") && pem.contains("-----END PRIVATE KEY-----")) {
                "Không đúng định dạng PKCS#8 .p8 của Apple."
            }
            p8 = pem
            status.text = "Đã đọc .p8 vào bộ nhớ tạm. Nội dung khóa không hiển thị. Nhập Key ID và Issuer ID rồi kiểm tra."
        } catch (e: Exception) {
            p8 = null; status.text = "Không đọc được .p8: " + (e.message ?: "lỗi không xác định")
        }
    }


    private fun generateLocalCsr() {
        status.text = "Đang tạo khóa ký P-256 và CSR cục bộ…"
        Thread {
            val result = runCatching {
                val generated = AppleCsrGenerator.generate()
                vault.saveSigningMaterial(generated.material)
                generated.material.csrPem
            }
            runOnUiThread {
                result.onSuccess {
                    currentCsr = it
                    status.text = "Đã tạo cặp khóa P-256 và CSR. Khóa riêng được mã hóa bằng Android Keystore; CSR sẵn sàng xuất. Chưa gửi dữ liệu lên Apple."
                }.onFailure {
                    status.text = "Không tạo được CSR: " + (it.message ?: "lỗi không xác định")
                }
            }
        }.start()
    }

    private fun exportCsr() {
        val csr = currentCsr ?: runCatching { vault.loadSigningMaterial()?.csrPem }.getOrNull()
        if (csr.isNullOrBlank()) {
            status.text = "Chưa có CSR. Hãy tạo khóa ký và CSR trước."
            return
        }
        currentCsr = csr
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pkcs10"
            putExtra(Intent.EXTRA_TITLE, "Bo-Sideload-iOS.csr")
        }
        startActivityForResult(intent, 811)
    }

    private fun saveAndValidate() {
        val pem = p8 ?: runCatching { vault.load()?.privateKeyPem }.getOrNull()
        if (pem.isNullOrBlank()) { status.text = "Chọn tệp .p8 hoặc kiểm tra khóa đã lưu."; return }
        val credentials = AppleConnectCredentialVault.Credentials(
            keyId.text.toString().trim(), issuerId.text.toString().trim(), pem
        )
        try { vault.save(credentials) } catch (e: Exception) {
            status.text = "Không thể lưu/đọc khóa: " + (e.message ?: "dữ liệu không hợp lệ"); return
        }
        p8 = null
        status.text = "Đang kiểm tra Team API Key với Apple…"
        Thread {
            val result = try { AppleConnectApi.validate(credentials) }
            catch (e: Exception) { AppleConnectApi.Result(false, "Không thể kết nối: " + (e.message ?: "lỗi mạng/khóa")) }
            runOnUiThread { status.text = result.message }
        }.start()
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun full() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
}
