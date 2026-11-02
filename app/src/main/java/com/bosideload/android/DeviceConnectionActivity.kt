package com.bosideload.android

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** USB diagnostics only; does not claim to complete Apple pairing or IPA install. */
class DeviceConnectionActivity : Activity() {
    private lateinit var usbManager: UsbManager
    private lateinit var status: TextView
    private lateinit var devicesPanel: LinearLayout
    private val permissionAction = "com.bosideload.android.USB_PERMISSION"

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == permissionAction) refreshDevices()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(0xFF080E1B.toInt())
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        root.addView(TextView(this).apply {
            text = "KẾT NỐI THIẾT BỊ"
            textSize = 23f
            setTextColor(-1)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Cắm iPhone/iPad vào Android bằng cáp dữ liệu và OTG tương thích. Mở khóa iPhone và chọn Tin cậy nếu iOS hỏi."
            textSize = 14f
            setTextColor(0xFFAEBCD0.toInt())
            setPadding(0, dp(14), 0, dp(14))
        })
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(-1)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(0xFF162135.toInt())
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Quét lại thiết bị USB"
            isAllCaps = false
            setOnClickListener { refreshDevices() }
        })
        devicesPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(devicesPanel)
        root.addView(TextView(this).apply {
            text = "Giới hạn: màn hình này dò USB và xin quyền Android. Chưa có ghép đôi Apple Lockdown/usbmuxd, đọc thông tin iPhone hoặc cài IPA. Thấy USB không có nghĩa là iPhone đã kết nối tin cậy."
            textSize = 13f
            setTextColor(0xFFFFD18A.toInt())
            setPadding(0, dp(18), 0, dp(12))
        })
        setContentView(scroll)
        val filter = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(permissionReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDevices()
    }

    override fun onDestroy() {
        try { unregisterReceiver(permissionReceiver) } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun refreshDevices() {
        devicesPanel.removeAllViews()
        val devices = usbManager.deviceList.values.toList()
        if (devices.isEmpty()) {
            status.text = "Chưa phát hiện USB. Kiểm tra cáp truyền dữ liệu, OTG và thử quét lại."
            return
        }
        devices.forEach { device ->
            val apple = device.vendorId == APPLE_VENDOR_ID
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundColor(0xFF162135.toInt())
            }
            card.addView(TextView(this).apply {
                text = buildString {
                    append(if (apple) "Thiết bị USB thuộc Apple (VID)\n" else "Thiết bị USB\n")
                    append("Tên: ").append(device.deviceName)
                    append("\nVendor ID: ").append(hex(device.vendorId))
                    append("   Product ID: ").append(hex(device.productId))
                    append("\nUSB class: ").append(device.deviceClass)
                    append("   Interfaces: ").append(device.interfaceCount)
                    append("\nQuyền Android: ").append(if (usbManager.hasPermission(device)) "Đã cấp" else "Chưa cấp")
                }
                textSize = 13f
                setTextColor(-1)
            })
            if (!usbManager.hasPermission(device)) {
                card.addView(Button(this).apply {
                    text = "Cấp quyền truy cập USB"
                    isAllCaps = false
                    setOnClickListener { requestUsbPermission(device) }
                })
            }
            devicesPanel.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        status.text = if (devices.any { it.vendorId == APPLE_VENDOR_ID }) {
            "Đã thấy VID Apple 05AC ở tầng USB. Chưa xác nhận Lockdown pairing hoặc khả năng cài ứng dụng."
        } else {
            "Có USB nhưng không thấy VID Apple (05AC). Kiểm tra thiết bị, cáp và OTG."
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val intent = Intent(permissionAction).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        try {
            usbManager.requestPermission(device, PendingIntent.getBroadcast(this, device.deviceId, intent, flags))
        } catch (_: Exception) { }
    }

    private fun hex(value: Int): String = "0x" + value.toString(16).uppercase().padStart(4, '0')
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object { private const val APPLE_VENDOR_ID = 0x05AC }
}
