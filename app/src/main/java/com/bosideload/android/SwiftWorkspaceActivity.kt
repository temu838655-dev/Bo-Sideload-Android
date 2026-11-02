package com.bosideload.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import java.io.ByteArrayOutputStream

/**
 * Lightweight source editor for Swift, plist and entitlement text files.
 * It edits source text only; it does not compile against Apple's iOS SDK.
 */
class SwiftWorkspaceActivity : Activity() {
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private var currentName = "Main.swift"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(16))
            setBackgroundColor(Color.rgb(8, 14, 27))
        }
        root.addView(TextView(this).apply {
            text = "BO CODE WORKSPACE"
            textSize = 13f
            setTextColor(Color.rgb(89, 156, 255))
            setTypeface(null, Typeface.BOLD)
        }, fullWidth())
        root.addView(TextView(this).apply {
            text = "Trình soạn mã Swift / plist"
            textSize = 23f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(4))
        }, fullWidth())
        root.addView(TextView(this).apply {
            text = "Có thể chỉnh sửa và xuất tệp mã nguồn. Đây không phải Swift compiler hoặc Xcode; không thể build IPA bằng iOS SDK trên Android."
            textSize = 12f
            setTextColor(Color.rgb(174, 188, 208))
            setPadding(0, 0, 0, dp(12))
        }, fullWidth())

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "Mở tệp"
            isAllCaps = false
            setOnClickListener { openSource() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        actions.addView(Button(this).apply {
            text = "Xuất tệp"
            isAllCaps = false
            setOnClickListener { saveSource() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })
        root.addView(actions, fullWidth())

        status = TextView(this).apply {
            text = "Tệp mới: Main.swift"
            textSize = 12f
            setTextColor(Color.rgb(174, 188, 208))
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(status, fullWidth())

        editor = EditText(this).apply {
            setTextColor(Color.rgb(230, 237, 247))
            setHintTextColor(Color.rgb(125, 143, 166))
            hint = "import Foundation\n\nstruct AppConfig {\n    let name = \"Bo Sideload\"\n}\n"
            textSize = 14f
            typeface = Typeface.MONOSPACE
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(false)
            minLines = 20
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(18, 28, 45))
            isVerticalScrollBarEnabled = true
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(editor)
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    private fun openSource() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, OPEN_SOURCE)
    }

    private fun saveSource() {
        val safeName = currentName.substringAfterLast('/').ifBlank { "Main.swift" }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, safeName)
        }
        startActivityForResult(intent, SAVE_SOURCE)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            OPEN_SOURCE -> {
                try {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Không mở được tệp.")
                    val bytes = input.use { stream ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8 * 1024)
                        var total = 0
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_SOURCE_BYTES) {
                                throw IllegalArgumentException("Tệp mã nguồn vượt giới hạn 2 MB.")
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    editor.setText(bytes.toString(Charsets.UTF_8))
                    currentName = queryName(uri)
                    status.text = "Đang sửa: $currentName"
                } catch (error: Exception) {
                    Toast.makeText(this, error.message ?: "Không đọc được tệp.", Toast.LENGTH_LONG).show()
                }
            }
            SAVE_SOURCE -> {
                try {
                    val output = contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Không mở được nơi lưu.")
                    output.use { it.write(editor.text.toString().toByteArray(Charsets.UTF_8)) }
                    status.text = "Đã xuất mã nguồn: ${queryName(uri)}"
                    Toast.makeText(this, "Đã xuất tệp mã nguồn.", Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    Toast.makeText(this, error.message ?: "Không lưu được tệp.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun queryName(uri: Uri): String = uri.lastPathSegment?.substringAfterLast('/') ?: currentName

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    companion object {
        private const val OPEN_SOURCE = 201
        private const val SAVE_SOURCE = 202
        private const val MAX_SOURCE_BYTES = 2 * 1024 * 1024
    }
}
