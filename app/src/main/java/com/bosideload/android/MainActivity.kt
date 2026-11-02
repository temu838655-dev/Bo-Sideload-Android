package com.bosideload.android

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.EditText
import android.widget.LinearLayout
import android.text.InputType
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var resultPanel: TextView
    private lateinit var recentPanel: LinearLayout
    private lateinit var importButton: Button
    private lateinit var p12Button: Button
    private lateinit var profileButton: Button
    private lateinit var signButton: Button
    private lateinit var signingStatus: TextView
    private var selectedIpaUri: Uri? = null
    private var selectedIpaName: String = "BoSideload-signed.ipa"
    private var p12Uri: Uri? = null
    private var profileUri: Uri? = null
    private var pendingSignedFile: File? = null
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
        page.addView(label("Bo Sideload Studio", 27f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }, fullWidth())
        page.addView(label("Không gian kiểm tra và ký lại IPA trực tiếp trên Android", 14f, secondaryText, false).apply {
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
        page.addView(Button(this).apply {
            text = "Mở không gian mã nguồn Swift"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SwiftWorkspaceActivity::class.java))
            }
        }, fullWidth())

        page.addView(Button(this).apply {
            text = "Kết nối iPhone qua USB"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(this@MainActivity, DeviceConnectionActivity::class.java))
            }
        }, fullWidth())

        page.addView(Button(this).apply {
            text = "Apple ID miễn phí • iPhone / MacBook / Apple TV"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AppleSigningGuideActivity::class.java))
            }
        }, fullWidth())

        page.addView(label("KÝ IPA NGAY TRÊN ANDROID", 18f, Color.WHITE, true).apply {
            setPadding(0, dp(24), 0, dp(10))
        }, fullWidth())
        page.addView(label(
            "Chọn IPA, chứng chỉ .p12 và provisioning profile hợp lệ. Bo Sideload dùng bộ máy zsign chạy cục bộ trên thiết bị ARM.",
            13f, secondaryText, false
        ).apply { setPadding(0, 0, 0, dp(8)) }, fullWidth())
        p12Button = Button(this).apply {
            text = "①  Chọn chứng chỉ .p12"
            isAllCaps = false
            setOnClickListener { chooseCredential(PICK_P12) }
        }
        page.addView(p12Button, fullWidth())
        profileButton = Button(this).apply {
            text = "②  Chọn provisioning profile"
            isAllCaps = false
            setOnClickListener { chooseCredential(PICK_PROFILE) }
        }
        page.addView(profileButton, fullWidth())
        signButton = Button(this).apply {
            text = "③  Ký lại IPA bằng zsign"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { showSigningPasswordDialog() }
        }
        page.addView(signButton, fullWidth())
        signingStatus = label(
            "Chưa chọn đủ IPA, chứng chỉ và provisioning profile.",
            13f, secondaryText, false
        ).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(panelColor)
        }
        page.addView(signingStatus, fullWidth().apply { topMargin = dp(8) })

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

        page.addView(label("BỘ MÁY VÀ GIỚI HẠN", 18f, Color.WHITE, true).apply {
            setPadding(0, dp(28), 0, dp(12))
        }, fullWidth())
        page.addView(label(
            "Bộ ký: zsign Android 1.1.2 (MIT), chạy cục bộ. Có thể ký lại IPA bằng P12 và provisioning profile. Việc ký không đồng nghĩa iPhone chắc chắn cài được. Đã có màn hình dò thiết bị USB và kiểm tra quyền USB. Ghép đôi Lockdown, kết nối Wi-Fi và cài IPA trực tiếp lên iPhone vẫn chưa được triển khai.",
            12f, secondaryText, false
        ).apply { setPadding(dp(4), dp(8), dp(4), 0) }, fullWidth())
        setContentView(scroll)
        renderRecent()
    }

    private fun chooseCredential(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, requestCode)
    }

    private fun updateSignButton() {
        if (!::signButton.isInitialized) return
        signButton.isEnabled = selectedIpaUri != null && p12Uri != null && profileUri != null
    }

    private fun showSigningPasswordDialog() {
        val passwordInput = EditText(this).apply {
            hint = "Mật khẩu P12"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(passwordInput, fullWidth())
        }
        AlertDialog.Builder(this)
            .setTitle("Mật khẩu chứng chỉ")
            .setMessage("Mật khẩu chỉ dùng trong lần ký này, không được lưu vào lịch sử.")
            .setView(container)
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Ký IPA") { _, _ ->
                val password = passwordInput.text.toString()
                passwordInput.text?.clear()
                startSigning(password)
            }
            .show()
    }

    private fun startSigning(password: String) {
        val ipaUri = selectedIpaUri ?: return
        val certificateUri = p12Uri ?: return
        val provisioningUri = profileUri ?: return
        signButton.isEnabled = false
        signingStatus.text = "Đang chuẩn bị tệp và chạy zsign trên thiết bị…"
        Thread {
            var result: ZsignEngine.SignResult
            var signedFile: File? = null
            try {
                val workDir = File(cacheDir, "signing-work").apply {
                    deleteRecursively()
                    mkdirs()
                }
                val input = copyUriToFile(ipaUri, File(workDir, "source.ipa"), MAX_IPA_BYTES)
                val p12 = copyUriToFile(certificateUri, File(workDir, "certificate.p12"), MAX_CREDENTIAL_BYTES)
                val profile = copyUriToFile(provisioningUri, File(workDir, "profile.mobileprovision"), MAX_CREDENTIAL_BYTES)
                val output = File(cacheDir, "BoSideload-${System.currentTimeMillis()}-signed.ipa")
                val preflight = AppleSigningCompatibility.preflight(input, p12, profile, password)
                if (!preflight.compatible) {
                    result = ZsignEngine.SignResult(
                        false,
                        null,
                        buildString {
                            appendLine("ĐÃ DỪNG TRƯỚC KHI KÝ — dữ liệu chưa tương thích:")
                            preflight.errors.forEach { appendLine("• $it") }
                            preflight.warnings.forEach { appendLine("Cảnh báo: $it") }
                            append("Hãy chọn đúng IPA, P12 và provisioning profile.")
                        }
                    )
                } else {
                    result = ZsignEngine(this).sign(input, p12, profile, password, output)
                    if (result.success) signedFile = result.output
                    if (preflight.warnings.isNotEmpty()) {
                        result = result.copy(message = result.message + "\\n\\nKIỂM TRA TRƯỚC KHI KÝ\\n" +
                            preflight.warnings.joinToString("\\n") { "• $it" })
                    }
                }
                p12.delete()
                profile.delete()
                input.delete()
                workDir.deleteRecursively()
            } catch (error: Exception) {
                File(cacheDir, "signing-work").deleteRecursively()
                result = ZsignEngine.SignResult(
                    false, null,
                    "Không thể chuẩn bị tệp ký: ${error.message ?: "lỗi không xác định"}"
                )
            }
            runOnUiThread {
                signButton.isEnabled = true
                signingStatus.text = result.message
                if (result.success && signedFile != null) {
                    pendingSignedFile = signedFile
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_TITLE, selectedIpaName)
                    }
                    startActivityForResult(intent, CREATE_SIGNED_IPA)
                }
                updateSignButton()
            }
        }.start()
    }

    private fun copyUriToFile(uri: Uri, destination: File, maxBytes: Long): File {
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Không mở được tệp đã chọn.")
        input.use { source ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) {
                        destination.delete()
                        throw IllegalArgumentException("Tệp vượt giới hạn kích thước an toàn.")
                    }
                    output.write(buffer, 0, count)
                }
            }
        }
        return destination
    }

    private fun exportSignedIpa(destination: Uri) {
        val source = pendingSignedFile
        if (source == null || !source.isFile) {
            signingStatus.text = "Không còn tệp IPA đã ký để xuất."
            return
        }
        Thread {
            val message = try {
                val output = contentResolver.openOutputStream(destination)
                    ?: throw IllegalStateException("Không mở được vị trí lưu.")
                output.use { target -> source.inputStream().use { it.copyTo(target) } }
                source.delete()
                "✓ Đã xuất IPA đã ký. Bạn vẫn cần phương thức cài đặt tương thích và profile hợp lệ trên iPhone."
            } catch (error: Exception) {
                "Không xuất được IPA: ${error.message ?: "lỗi không xác định"}"
            }
            if (message.startsWith("✓")) pendingSignedFile = null
            runOnUiThread { signingStatus.text = message }
        }.start()
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
        if (resultCode != RESULT_OK) {
            if (requestCode == CREATE_SIGNED_IPA) {
                pendingSignedFile?.delete()
                pendingSignedFile = null
                if (::signingStatus.isInitialized) {
                    signingStatus.text = "Đã hủy lưu IPA. Tệp tạm đã được xóa."
                }
            }
            return
        }
        val uri = data?.data ?: return
        when (requestCode) {
            PICK_FILE, PICK_P12, PICK_PROFILE -> {
                try {
                    contentResolver.takePersistableUriPermission(uri, data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                    // The provider may grant temporary access only.
                }
                when (requestCode) {
                    PICK_FILE -> inspect(uri, true)
                    PICK_P12 -> {
                        p12Uri = uri
                        p12Button.text = "✓  Chứng chỉ: ${displayName(uri)}"
                        updateSignButton()
                    }
                    PICK_PROFILE -> {
                        profileUri = uri
                        profileButton.text = "✓  Profile: ${displayName(uri)}"
                        updateSignButton()
                    }
                }
            }
            CREATE_SIGNED_IPA -> exportSignedIpa(uri)
        }
    }

    private fun inspect(uri: Uri, remember: Boolean) {
        selectedIpaUri = uri
        selectedIpaName = displayName(uri).substringAfterLast('/').substringBeforeLast('.', displayName(uri))
            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(100) + "-signed.ipa"
        updateSignButton()
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
        private const val PICK_P12 = 101
        private const val PICK_PROFILE = 102
        private const val CREATE_SIGNED_IPA = 103
        private const val MAX_IPA_BYTES = 512L * 1024L * 1024L
        private const val MAX_CREDENTIAL_BYTES = 20L * 1024L * 1024L
    }
}
