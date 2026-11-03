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
import android.text.InputType
import java.io.ByteArrayOutputStream

/** Paid Apple Developer connection. Never asks for Apple ID password or 2FA. */
class AppleConnectActivity : Activity() {
    private lateinit var keyId: EditText
    private lateinit var issuerId: EditText
    private lateinit var status: TextView
    private lateinit var vault: AppleConnectCredentialVault
    private var p8: String? = null
    private var currentCsr: String? = null
    private var pendingP12: ByteArray? = null
    private var pendingProfile: ByteArray? = null

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
            text = "③ Tạo khóa ký RSA-2048 và CSR trên thiết bị"; isAllCaps = false
            setOnClickListener { generateLocalCsr() }
        }, full())
        page.addView(Button(this).apply {
            text = "④ Xuất CSR để lưu thành tệp"; isAllCaps = false
            setOnClickListener { exportCsr() }
        }, full())
        page.addView(Button(this).apply {
            text = "⑤ Tạo chứng chỉ Apple Development"; isAllCaps = false
            setOnClickListener { confirmCertificateCreation("DEVELOPMENT") }
        }, full())
        page.addView(Button(this).apply {
            text = "⑥ Tạo chứng chỉ Apple Distribution"; isAllCaps = false
            setOnClickListener { confirmCertificateCreation("DISTRIBUTION") }
        }, full())
        page.addView(Button(this).apply {
            text = "⑦ Xuất chứng chỉ và khóa riêng thành P12"; isAllCaps = false
            setOnClickListener { exportP12() }
        }, full())
        page.addView(Button(this).apply {
            text = "⑧ Lấy danh sách App ID / certificate / thiết bị"; isAllCaps = false
            setOnClickListener { loadAppleResourceIds() }
        }, full())
        page.addView(Button(this).apply {
            text = "⑨ Tạo provisioning profile"; isAllCaps = false
            setOnClickListener { createProvisioningProfile() }
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
            "Apple chỉ cho tải private key .p8 một lần. Nếu mất hoặc lộ khóa, hãy thu hồi key và tạo key mới. Individual API Key không dùng được Provisioning endpoints. Bản này hỗ trợ kiểm tra Team API, tạo certificate và provisioning profile theo xác nhận riêng. Việc đăng ký UDID và kiểm thử ký/cài IPA trên iPhone thật vẫn cần hoàn thiện.",
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
        if (resultCode != RESULT_OK) {
            if (requestCode == 812) {
                pendingP12?.fill(0); pendingP12 = null
                status.text = "Đã hủy xuất P12; dữ liệu tạm đã được xóa khỏi bộ nhớ."
            }
            if (requestCode == 813) {
                pendingProfile?.fill(0); pendingProfile = null
                status.text = "Đã hủy lưu provisioning profile; dữ liệu tạm đã được xóa."
            }
            return
        }
        val uri: Uri = data?.data ?: return
        if (requestCode == 813) {
            val bytes = pendingProfile ?: run { status.text = "Không còn profile để lưu."; return }
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("Không mở được tệp đích.")
                status.text = "Đã lưu provisioning profile. Hãy kiểm tra profile trước khi dùng ký IPA."
            } catch (e: Exception) {
                status.text = "Không thể lưu profile: " + (e.message ?: "lỗi ghi tệp")
            } finally {
                bytes.fill(0); pendingProfile = null
            }
            return
        }
        if (requestCode == 812) {
            val bytes = pendingP12 ?: run { status.text = "Không còn dữ liệu P12 để xuất."; return }
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("Không mở được tệp đích.")
                status.text = "Đã xuất P12. Hãy giữ mật khẩu an toàn; ứng dụng không lưu mật khẩu này."
            } catch (e: Exception) {
                status.text = "Không thể ghi P12: " + (e.message ?: "lỗi ghi tệp")
            } finally {
                bytes.fill(0); pendingP12 = null
            }
            return
        }
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
        status.text = "Đang tạo khóa ký RSA-2048 và CSR cục bộ…"
        Thread {
            val result = runCatching {
                val generated = AppleCsrGenerator.generate()
                vault.saveSigningMaterial(generated.material)
                generated.material.csrPem
            }
            runOnUiThread {
                result.onSuccess {
                    currentCsr = it
                    status.text = "Đã tạo cặp khóa RSA-2048 và CSR. Khóa riêng được mã hóa bằng Android Keystore; CSR sẵn sàng xuất. Chưa gửi dữ liệu lên Apple."
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

    private fun exportP12() {
        val material = runCatching { vault.loadSigningMaterial() }.getOrNull()
        val certificate = runCatching { vault.loadIssuedCertificate() }.getOrNull()
        if (material == null || certificate == null) {
            status.text = "Cần tạo khóa/CSR và được Apple cấp chứng chỉ trước khi xuất P12."
            return
        }
        val passwordInput = EditText(this).apply {
            hint = "Mật khẩu P12 (ít nhất 12 ký tự)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Đặt mật khẩu cho P12")
            .setMessage("P12 chứa khóa riêng. Mật khẩu chỉ dùng để tạo tệp, không được lưu trong ứng dụng. Hãy ghi nhớ mật khẩu này.")
            .setView(passwordInput)
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Tạo P12") { _, _ ->
                val password = passwordInput.text.toString().toCharArray()
                passwordInput.text?.clear()
                status.text = "Đang đóng gói P12 cục bộ…"
                Thread {
                    val result = runCatching { AppleP12Exporter.export(material, certificate, password) }
                    password.fill(0.toChar())
                    runOnUiThread {
                        result.onSuccess { bytes ->
                            pendingP12?.fill(0)
                            pendingP12 = bytes
                            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "application/x-pkcs12"
                                putExtra(Intent.EXTRA_TITLE, "Bo-Sideload-iOS-Signing.p12")
                            }
                            status.text = "Đã tạo P12 trong bộ nhớ tạm. Chọn nơi lưu tệp."
                            startActivityForResult(intent, 812)
                        }.onFailure {
                            status.text = "Không thể tạo P12: " + (it.message ?: "lỗi không xác định")
                        }
                    }
                }.start()
            }
            .show()
    }

    private fun loadAppleResourceIds() {
        val credentials = runCatching { vault.load() }.getOrNull()
        if (credentials == null) {
            status.text = "Chưa lưu Team API Key. Hãy kết nối và kiểm tra Apple API trước."
            return
        }
        status.text = "Đang đọc danh sách App ID, certificate và thiết bị từ Apple…"
        Thread {
            val result = AppleProvisioningProfileManager.listResources(credentials)
            runOnUiThread { status.text = result.message }
        }.start()
    }

    private fun createProvisioningProfile() {
        val credentials = runCatching { vault.load() }.getOrNull()
        if (credentials == null) {
            status.text = "Chưa lưu Team API Key. Hãy kết nối và kiểm tra Apple API trước."
            return
        }
        val profileName = EditText(this).apply { hint = "Tên profile, ví dụ Bo Sideload Dev"; setSingleLine(true) }
        val bundleId = EditText(this).apply { hint = "Bundle ID resource ID từ Apple API"; setSingleLine(true) }
        val certIds = EditText(this).apply { hint = "Certificate resource ID (phân cách dấu phẩy)"; setSingleLine(true) }
        val deviceIds = EditText(this).apply { hint = "Device resource ID (bắt buộc Development/Ad Hoc)"; setSingleLine(true) }
        val typeLabels = arrayOf("Development", "Ad Hoc", "App Store")
        var selectedType = 0
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(profileName, full()); addView(bundleId, full())
            addView(certIds, full()); addView(deviceIds, full())
            addView(TextView(this@AppleConnectActivity).apply {
                text = "ID ở đây là resource ID Apple trả về, không phải Bundle ID dạng com.example.app hoặc UDID thiết bị."
                textSize = 12f
            }, full())
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Thông tin provisioning profile")
            .setView(form)
            .setSingleChoiceItems(typeLabels, selectedType) { _, which -> selectedType = which }
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Tiếp tục") { _, _ ->
                val type = AppleProvisioningProfileManager.Type.values()[selectedType]
                val certList = certIds.text.toString().split(",").map { it.trim() }.filter { it.isNotBlank() }
                val deviceList = deviceIds.text.toString().split(",").map { it.trim() }.filter { it.isNotBlank() }
                val req = runCatching {
                    AppleProvisioningProfileManager.Request(
                        profileName.text.toString().trim(), type,
                        bundleId.text.toString().trim(), certList, deviceList
                    )
                }.getOrElse {
                    status.text = it.message ?: "Thông tin profile không hợp lệ."
                    return@setPositiveButton
                }
                android.app.AlertDialog.Builder(this)
                    .setTitle("Xác nhận tạo profile Apple")
                    .setMessage("Apple sẽ tạo một provisioning profile thật trong tài khoản của bạn. Loại: " + type.apiValue +
                        ". App ID resource ID: " + req.bundleIdResourceId +
                        ". Certificate IDs: " + req.certificateResourceIds.joinToString() +
                        ". Device IDs: " + req.deviceResourceIds.joinToString().ifBlank { "không áp dụng" } +
                        ". Thao tác này thay đổi tài nguyên trên Apple; ứng dụng không tự xóa profile.")
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Tạo profile") { _, _ -> runCreateProfile(credentials, req) }
                    .show()
            }
            .show()
    }

    private fun runCreateProfile(
        credentials: AppleConnectCredentialVault.Credentials,
        request: AppleProvisioningProfileManager.Request
    ) {
        status.text = "Đang tạo provisioning profile với Apple…"
        Thread {
            val result = AppleProvisioningProfileManager.create(credentials, request)
            runOnUiThread {
                if (result.ok && result.profileContent != null) {
                    pendingProfile?.fill(0)
                    pendingProfile = result.profileContent
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_TITLE, "Bo-Sideload-" + request.type.name + ".mobileprovision")
                    }
                    status.text = result.message
                    startActivityForResult(intent, 813)
                } else {
                    status.text = result.message
                }
            }
        }.start()
    }

    private fun confirmCertificateCreation(type: String) {
        val displayName = if (type == "DEVELOPMENT") "Apple Development" else "Apple Distribution"
        val material = runCatching { vault.loadSigningMaterial() }.getOrNull()
        if (material == null) {
            status.text = "Chưa có khóa ký và CSR. Hãy tạo CSR trước."
            return
        }
        val credentials = runCatching { vault.load() }.getOrNull()
        if (credentials == null) {
            status.text = "Chưa lưu Team API Key. Hãy kết nối và kiểm tra Apple API trước."
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Xác nhận tạo $displayName")
            .setMessage(
                "Thao tác này sẽ gửi CSR lên Apple và tạo chứng chỉ thật trong tài khoản Developer của bạn. " +
                "Có thể tiêu tốn hạn mức chứng chỉ của Team. Ứng dụng sẽ không tự thu hồi chứng chỉ cũ. " +
                "Chỉ tiếp tục nếu bạn chắc chắn muốn tạo $displayName."
            )
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Tạo chứng chỉ") { _, _ -> createCertificate(type, credentials, material) }
            .show()
    }

    private fun createCertificate(
        type: String,
        credentials: AppleConnectCredentialVault.Credentials,
        material: AppleConnectCredentialVault.SigningMaterial
    ) {
        status.text = "Đang gửi yêu cầu tạo chứng chỉ $type lên Apple…"
        Thread {
            val result = AppleCertificateProvisioner.create(credentials, material, type, vault)
            runOnUiThread { status.text = result.message }
        }.start()
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
