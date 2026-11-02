package com.bosideload.android

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 48, 28, 28)
            setBackgroundColor(Color.rgb(9, 15, 29))
        }
        val title = TextView(this).apply {
            text = "Bo Sideload"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Công cụ hỗ trợ quy trình cài ứng dụng iOS"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 28)
        }
        val status = TextView(this).apply {
            text = "Chào mừng bạn đến với Bo Sideload"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16, 24, 16, 24)
        }
        val choose = Button(this).apply {
            text = "Chọn tệp IPA"
            setOnClickListener {
                val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                }
                startActivityForResult(intent, 100)
            }
        }
        val note = TextView(this).apply {
            text = "Bản thử nghiệm ban đầu. Việc ký và cài IPA lên iPhone cần tích hợp giao thức Apple và kiểm thử trên thiết bị thật."
            textSize = 13f
            setTextColor(Color.rgb(190, 200, 215))
            gravity = Gravity.CENTER
            setPadding(8, 24, 8, 0)
        }
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(subtitle)
        root.addView(status)
        root.addView(choose)
        root.addView(note)
        setContentView(root)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            val name = data?.data?.lastPathSegment ?: "đã chọn"
            android.widget.Toast.makeText(this, "Đã chọn tệp: $name", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
