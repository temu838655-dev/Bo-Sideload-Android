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

/** USB discovery and ordinary Apple Lockdown pairing. IPA installation is wired to this screen but still requires real-device validation. */
class DeviceConnectionActivity : Activity() {
    private lateinit var usbManager: UsbManager
    private lateinit var pairRecordStore: EncryptedPairRecordStore
    private lateinit var status: TextView
    private lateinit var devicesPanel: LinearLayout
    private lateinit var wifiDevicesPanel: LinearLayout
    private lateinit var wifiScanButton: Button
    private var activeWifiDiscovery: WifiAppleDeviceDiscovery? = null
    private lateinit var pairButton: Button
    private lateinit var installButton: Button
    private var attachedAppleDevice: UsbDevice? = null
    private var attachedUsbmuxDeviceId: Long? = null
    private var attachedDeviceSerial: String? = null
    private var pairingInProgress = false
    private var diagnosticReport: String = "Chưa có dữ liệu USB."
    private var pendingPermissionDeviceId: Int? = null
    private var activeMonitor: UsbMuxDeviceMonitor? = null
    private var activeMonitorDeviceId: Int? = null
    private val permissionAction = "com.bosideload.android.USB_PERMISSION"

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == permissionAction) {
                val requestedId = pendingPermissionDeviceId ?: return
                val resultDevice: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                // Ignore stale or unrelated permission broadcasts.
                if (resultDevice == null || resultDevice.deviceId != requestedId) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                pendingPermissionDeviceId = null
                refreshDevices()
                if (!granted && requestedId != null) {
                    status.text = "Bạn đã từ chối quyền USB cho thiết bị ID $requestedId. Hãy cấp quyền nếu muốn xem interface và endpoint."
                    diagnosticReport += "\n\nKết quả xin quyền USB: bị từ chối (deviceId=$requestedId)."
                } else if (granted) {
                    status.text = "Đã được cấp quyền USB. Đang hiển thị thông tin interface và endpoint."
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        pairRecordStore = EncryptedPairRecordStore(this)
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
            text = "WI-FI LÀ CHẾ ĐỘ ƯU TIÊN"
            textSize = 16f
            setTextColor(-1)
            setPadding(0, dp(20), 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = "Đang phát triển kết nối không dây đúng giao thức Apple: tự tìm iPhone qua Bonjour/mDNS, ghép đôi Remote Pairing trên iOS 27 trở lên và tạo đường hầm bảo mật để truyền/cài IPA. Với iPhone đã ghép đôi qua USB và bật Wi-Fi Sync, ứng dụng cũng sẽ hỗ trợ kết nối lại qua mạng khi thiết bị cho phép.\n\nBản hiện tại chưa bật cài IPA qua Wi-Fi. Không nhập IP/cổng 62078 để ghép đôi mới: kết nối Lockdown trực tiếp không thay thế quy trình Remote Pairing."
            textSize = 13f
            setTextColor(0xFFFFD18A.toInt())
            setPadding(0, dp(6), 0, dp(10))
        })
        wifiScanButton = Button(this).apply {
            text = "Quét iPhone/iPad cùng Wi-Fi"
            isAllCaps = false
            setOnClickListener {
                val current = activeWifiDiscovery
                if (current != null) {
                    current.close()
                    activeWifiDiscovery = null
                    text = "Quét iPhone/iPad cùng Wi-Fi"
                } else {
                    wifiDevicesPanel.removeAllViews()
                    val discovery = WifiAppleDeviceDiscovery(this@DeviceConnectionActivity, object : WifiAppleDeviceDiscovery.Listener {
                        override fun onStatus(message: String) {
                            runOnUiThread { status.text = message }
                        }

                        override fun onDeviceFound(device: WifiAppleDeviceDiscovery.DiscoveredDevice) {
                            runOnUiThread {
                                val card = LinearLayout(this@DeviceConnectionActivity).apply {
                                    orientation = LinearLayout.VERTICAL
                                    tag = device.serviceName
                                    setPadding(dp(12), dp(10), dp(12), dp(10))
                                    setBackgroundColor(0xFF162135.toInt())
                                }
                                val storedRecords = pairRecordStore.listDeviceIds().mapNotNull { deviceId ->
                                    runCatching { pairRecordStore.load(deviceId) }.getOrNull()
                                }
                                val match = AppleWifiPairingMatcher.findMatch(device.txtRecord, storedRecords)
                                card.addView(TextView(this@DeviceConnectionActivity).apply {
                                    text = buildString {
                                        append("Dịch vụ Bonjour: ${device.serviceName}")
                                        append("\nĐịa chỉ mạng: ${device.hostAddress}:${device.port}")
                                        if (match != null) {
                                            append("\nĐÃ KHỚP pairing record: ${match.deviceId}")
                                            append("\nĐã xác định ứng viên theo authTag; vẫn phải xác thực TLS trước khi truyền dữ liệu.")
                                        } else {
                                            append("\nCHƯA KHỚP pairing record đã lưu.")
                                            append("\nKhông kết nối đến dịch vụ này và không gửi thông tin pairing.")
                                        }
                                        append("\nTXT identifier: ${if (device.txtRecord.containsKey("identifier")) "có" else "thiếu"}")
                                    }
                                    textSize = 13f
                                    setTextColor(-1)
                                })
                                wifiDevicesPanel.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                                status.text = if (match != null) {
                                    "Đã khớp quảng bá Wi-Fi với pairing record đã lưu. Bước xác thực TLS chưa được thực hiện."
                                } else {
                                    "Đã thấy dịch vụ Apple nhưng chưa khớp pairing record. Ứng dụng sẽ không kết nối."
                                }
                            }
                        }

                        override fun onDeviceLost(serviceName: String) {
                            runOnUiThread {
                                for (index in wifiDevicesPanel.childCount - 1 downTo 0) {
                                    if (wifiDevicesPanel.getChildAt(index).tag == serviceName) {
                                        wifiDevicesPanel.removeViewAt(index)
                                    }
                                }
                            }
                        }
                    })
                    activeWifiDiscovery = discovery
                    wifiScanButton.text = "Dừng quét Wi-Fi"
                    discovery.start()
                }
            }
        }
        root.addView(wifiScanButton)
        wifiDevicesPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(wifiDevicesPanel)
        pairButton = Button(this).apply {
            text = "Ghép đôi iPhone (Lockdown)"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { startApplePairing() }
        }
        root.addView(pairButton)
        installButton = Button(this).apply {
            text = "Cài IPA đã ký lên iPhone"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { startIpaInstallation() }
        }
        root.addView(installButton)
        root.addView(Button(this).apply {
            text = "Chia sẻ báo cáo USB"
            isAllCaps = false
            setOnClickListener { shareDiagnosticReport() }
        })
        root.addView(TextView(this).apply {
            text = "Màn hình này dò USB, theo dõi usbmux và thực hiện ghép đôi Apple Lockdown. Sau khi ghép đôi, hồ sơ được mã hóa bằng Android Keystore. Luồng cài IPA đã được nối nhưng chưa được xác nhận trên iPhone thật; thấy USB không đồng nghĩa cài đặt chắc chắn thành công."
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
        activeMonitor?.close()
        activeMonitor = null
        activeMonitorDeviceId = null
        activeWifiDiscovery?.close()
        activeWifiDiscovery = null
        attachedAppleDevice = null
        super.onDestroy()
    }

    private fun refreshDevices() {
        devicesPanel.removeAllViews()
        attachedAppleDevice = null
        if (attachedUsbmuxDeviceId == null) {
            attachedDeviceSerial = null
            pairButton.isEnabled = false
            pairButton.text = "Ghép đôi iPhone (Lockdown)"
        }
        val devices = usbManager.deviceList.values.toList()
        if (devices.isEmpty()) {
            status.text = "Chưa phát hiện USB. Kiểm tra cáp truyền dữ liệu, OTG và thử quét lại."
            diagnosticReport = status.text.toString()
            return
        }
        devices.forEach { device ->
            val apple = device.vendorId == APPLE_VENDOR_ID
            if (apple && usbManager.hasPermission(device)) attachedAppleDevice = device
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
                    if (Build.VERSION.SDK_INT >= 21) {
                        append("\nNhà sản xuất: ").append(device.manufacturerName ?: "Không cung cấp")
                        append("\nTên sản phẩm: ").append(device.productName ?: "Không cung cấp")
                    }
                    append("\nQuyền Android: ").append(if (usbManager.hasPermission(device)) "Đã cấp" else "Chưa cấp")
                    if (usbManager.hasPermission(device)) {
                        for (index in 0 until device.interfaceCount) {
                            val usbInterface = device.getInterface(index)
                            append("\n\nInterface ").append(index + 1)
                            append(": class=").append(hex(usbInterface.interfaceClass))
                            append(", subclass=").append(hex(usbInterface.interfaceSubclass))
                            append(", protocol=").append(hex(usbInterface.interfaceProtocol))
                            append(", endpoints=").append(usbInterface.endpointCount)
                            for (endpointIndex in 0 until usbInterface.endpointCount) {
                                val endpoint = usbInterface.getEndpoint(endpointIndex)
                                append("\n  Endpoint ").append(endpointIndex + 1)
                                append(": address=").append(hex(endpoint.address))
                                append(", direction=").append(if (endpoint.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) "IN" else "OUT")
                                append(", type=").append(endpoint.type)
                                append(", maxPacket=").append(endpoint.maxPacketSize)
                            }
                        }
                    }
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
            } else if (apple) {
                card.addView(Button(this).apply {
                    isAllCaps = false
                    text = if (activeMonitorDeviceId == device.deviceId) "Dừng theo dõi usbmux" else "Thử kết nối usbmux"
                    setOnClickListener {
                        if (activeMonitorDeviceId == device.deviceId) {
                            activeMonitor?.close()
                            activeMonitor = null
                            activeMonitorDeviceId = null
                            text = "Thử kết nối usbmux"
                            status.text = "Đã yêu cầu dừng phiên usbmux."
                        } else {
                            activeMonitor?.close()
                            val monitor = UsbMuxDeviceMonitor(usbManager, device, object : UsbMuxDeviceMonitor.Listener {
                                override fun onStatus(message: String) {
                                    runOnUiThread { status.text = message }
                                }
                                override fun onDeviceAttached(deviceInfo: UsbMuxResponseParser.Device) {
                                    runOnUiThread {
                                        status.text = buildString {
                                            append("usbmux báo thiết bị Attached. DeviceID: ").append(deviceInfo.deviceId)
                                            deviceInfo.serialNumber?.let { append("\nSerial: ").append(it) }
                                            deviceInfo.connectionType?.let { append("\nKiểu kết nối: ").append(it) }
                                            deviceInfo.productId?.let { append("\nProduct ID: ").append(hex(it.toInt())) }
                                            append("\nĐây chưa phải xác nhận Lockdown pairing.")
                                        }
                                        // Keep the Android USB device that owns this usbmux
                                        // interface; Lockdown pairing and IPA installation
                                        // must reopen this exact USB device after Listen stops.
                                        attachedAppleDevice = device
                                        attachedUsbmuxDeviceId = deviceInfo.deviceId
                                        attachedDeviceSerial = deviceInfo.serialNumber
                                        updatePairingActions(deviceInfo.serialNumber)
                                    }
                                }
                                override fun onDeviceDetached(deviceId: Long) {
                                    runOnUiThread {
                                        status.text = "usbmux báo thiết bị Detached (DeviceID=$deviceId)."
                                        if (attachedUsbmuxDeviceId == deviceId) {
                                            attachedUsbmuxDeviceId = null
                                            attachedDeviceSerial = null
                                            attachedAppleDevice = null
                                            pairButton.isEnabled = false
                                            installButton.isEnabled = false
                                            pairButton.text = "Chưa có iPhone — kết nối lại để ghép đôi"
                                            installButton.text = "Kết nối lại iPhone để cài IPA"
                                        }
                                    }
                                }
                                override fun onError(message: String) {
                                    runOnUiThread { status.text = "Không thể kết nối usbmux: $message" }
                                }
                            })
                            activeMonitor = monitor
                            activeMonitorDeviceId = device.deviceId
                            text = "Dừng theo dõi usbmux"
                            monitor.start()
                        }
                    }
                })
            }
            devicesPanel.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        diagnosticReport = buildString {
            appendLine("Bo Sideload - Báo cáo chẩn đoán USB")
            appendLine("Số thiết bị: ${devices.size}")
            devices.forEachIndexed { index, device ->
                appendLine("\nThiết bị ${index + 1}: ${device.deviceName}")
                appendLine("VID: ${hex(device.vendorId)} PID: ${hex(device.productId)}")
                appendLine("USB class: ${device.deviceClass}; interfaces: ${device.interfaceCount}")
                appendLine("Android permission: ${usbManager.hasPermission(device)}")
                if (usbManager.hasPermission(device)) {
                    for (i in 0 until device.interfaceCount) {
                        val usbInterface = device.getInterface(i)
                        appendLine("Interface $i: class=${hex(usbInterface.interfaceClass)} subclass=${hex(usbInterface.interfaceSubclass)} protocol=${hex(usbInterface.interfaceProtocol)}")
                        for (e in 0 until usbInterface.endpointCount) {
                            val endpoint = usbInterface.getEndpoint(e)
                            appendLine("Endpoint $e: address=${hex(endpoint.address)} direction=${endpoint.direction} type=${endpoint.type} maxPacket=${endpoint.maxPacketSize}")
                        }
                    }
                }
            }
            appendLine("\nUSB enumeration only; no Apple Lockdown pairing or IPA installation performed.")
        }
        status.text = if (devices.any { it.vendorId == APPLE_VENDOR_ID }) {
            "Đã thấy VID Apple 05AC ở tầng USB. Chưa xác nhận Lockdown pairing hoặc khả năng cài ứng dụng."
        } else {
            "Có USB nhưng không thấy VID Apple (05AC). Kiểm tra thiết bị, cáp và OTG."
        }
    }



    private fun updatePairingActions(serial: String?) {
        val paired = !serial.isNullOrBlank() && runCatching {
            EncryptedPairRecordStore(this).load(serial) != null
        }.getOrDefault(false)
        pairButton.text = when {
            serial.isNullOrBlank() -> "Không có UDID — chưa thể ghép đôi"
            paired -> "Đã ghép đôi • Ghép lại nếu cần"
            else -> "Ghép đôi iPhone (Lockdown)"
        }
        pairButton.isEnabled = !pairingInProgress && !serial.isNullOrBlank()
        val signedFile = signedIpaFile()
        installButton.isEnabled = paired && signedFile != null &&
            signedFile.isFile && signedFile.canRead() && signedFile.length() > 0
        installButton.text = when {
            signedFile == null -> "Quay lại màn hình chính để chọn IPA đã ký"
            !signedFile.isFile -> "Không tìm thấy IPA tạm — hãy ký lại"
            !paired -> "Ghép đôi iPhone trước khi cài IPA"
            else -> "Cài IPA đã ký lên iPhone"
        }
    }

    private fun signedIpaFile(): java.io.File? =
        intent.getStringExtra(EXTRA_SIGNED_IPA_PATH)?.let { java.io.File(it) }

    private fun startIpaInstallation() {
        val usbDevice = attachedAppleDevice
        val usbmuxDeviceId = attachedUsbmuxDeviceId
        val serial = attachedDeviceSerial
        val ipaFile = signedIpaFile()
        if (usbDevice == null || usbmuxDeviceId == null || serial.isNullOrBlank()) {
            status.text = "Chưa có iPhone Attached từ usbmux. Hãy cắm cáp, mở khóa iPhone và theo dõi usbmux."
            return
        }
        if (ipaFile == null || !ipaFile.isFile || !ipaFile.canRead() || ipaFile.length() <= 0) {
            status.text = "Không tìm thấy IPA đã ký trong bộ nhớ tạm. Hãy quay lại ký IPA."
            return
        }
        if (ipaFile.length() > MAX_INSTALL_IPA_BYTES) {
            status.text = "IPA vượt giới hạn 512 MB."
            return
        }
        if (pairingInProgress) return

        pairingInProgress = true
        pairButton.isEnabled = false
        installButton.isEnabled = false
        installButton.text = "Đang cài đặt…"
        status.text = "Đang xác thực phiên Lockdown với hồ sơ ghép đôi đã lưu…"
        activeMonitor?.close()
        activeMonitor = null
        activeMonitorDeviceId = null

        Thread({
            try {
                val record = EncryptedPairRecordStore(this).load(serial)
                    ?: throw IllegalStateException("iPhone chưa có hồ sơ ghép đôi. Hãy ghép đôi trước.")
                val coordinator = SideloadInstallCoordinator(
                    openLockdownSession = {
                        val transport = AndroidUsbMuxTransport.open(
                            usbManager,
                            usbDevice,
                            timeoutMs = SERVICE_IO_TIMEOUT_MS
                        )
                        val connect = UsbMuxConnectSession.connect(
                            transport = transport,
                            deviceId = usbmuxDeviceId,
                            devicePort = UsbMuxConnectSession.LOCKDOWN_PORT
                        )
                        LockdownTlsSession.start(connect, record)
                    },
                    deviceId = usbmuxDeviceId,
                    pairRecord = record,
                    newTransport = {
                        AndroidUsbMuxTransport.open(
                            usbManager,
                            usbDevice,
                            timeoutMs = SERVICE_IO_TIMEOUT_MS
                        )
                    }
                )
                val installResult = ipaFile.inputStream().use { input ->
                    coordinator.install(
                        ipaInput = input,
                        ipaSizeBytes = ipaFile.length()
                    ) { progress ->
                        runOnUiThread {
                            status.text = when (progress.stage) {
                                SideloadInstallCoordinator.Stage.UPLOADING_IPA ->
                                    "Đang tải IPA lên iPhone: ${progress.bytesTransferred ?: 0} / ${progress.totalBytes ?: 0} byte"
                                SideloadInstallCoordinator.Stage.INSTALLING_APP ->
                                    "Đang cài ứng dụng: ${progress.installPercent ?: 0}% — ${progress.message}"
                                SideloadInstallCoordinator.Stage.COMPLETE ->
                                    progress.message
                            }
                        }
                    }
                }
                getSharedPreferences(VERIFIED_DEVICE_PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_VERIFIED_UDID, serial)
                    .apply()
                runOnUiThread {
                    pairingInProgress = false
                    updatePairingActions(serial)
                    status.text = if (installResult.stagingCleanupSucceeded) {
                        "✓ iPhone đã báo cài đặt hoàn tất và IPA tạm đã được xóa. Nếu ứng dụng chưa xuất hiện, kiểm tra thông báo iOS và trạng thái chứng chỉ."
                    } else {
                        "✓ iPhone đã báo cài đặt hoàn tất, nhưng không xóa được IPA tạm. Bạn có thể xóa thủ công trong vùng PublicStaging nếu cần."
                    }
                    diagnosticReport += "\\n\\nInstallation Proxy: hoàn tất theo phản hồi của iPhone; IPA=${ipaFile.name}; xóa IPA tạm=${installResult.stagingCleanupSucceeded}."
                }
            } catch (error: Exception) {
                runOnUiThread {
                    pairingInProgress = false
                    updatePairingActions(serial)
                    status.text = "Cài IPA thất bại: ${error.message ?: "lỗi không xác định"}. Kiểm tra cáp, mở khóa iPhone, hồ sơ Tin cậy và provisioning profile."
                    diagnosticReport += "\\n\\nCài IPA thất bại: ${error.message}"
                }
            }
        }, "BoSideload-install-ipa").apply { isDaemon = true; start() }
    }

    /**
     * Performs the ordinary Apple Lockdown Pair exchange after usbmux Listen
     * has supplied a device ID and UDID. The USB monitor is stopped first so
     * the same Android USB interface is not claimed by two transports.
     */
    private fun startApplePairing() {
        val usbDevice = attachedAppleDevice
        val usbmuxDeviceId = attachedUsbmuxDeviceId
        val serial = attachedDeviceSerial
        if (usbDevice == null || usbmuxDeviceId == null || serial.isNullOrBlank()) {
            status.text = "Chưa có iPhone Attached kèm UDID từ usbmux. Hãy mở khóa iPhone và thử lại."
            pairButton.isEnabled = false
            return
        }
        if (!usbManager.hasPermission(usbDevice)) {
            status.text = "Quyền USB đã mất. Hãy cấp quyền USB lại trước khi ghép đôi."
            pairButton.isEnabled = false
            return
        }
        if (pairingInProgress) return

        pairingInProgress = true
        pairButton.isEnabled = false
        pairButton.text = "Đang ghép đôi…"
        status.text = "Đang dừng usbmux Listen và mở kênh Lockdown. Hãy giữ iPhone mở khóa."
        activeMonitor?.close()
        activeMonitor = null
        activeMonitorDeviceId = null

        Thread({
            try {
                val transport = AndroidUsbMuxTransport.open(usbManager, usbDevice)
                val connect = UsbMuxConnectSession.connect(
                    transport = transport,
                    deviceId = usbmuxDeviceId,
                    devicePort = UsbMuxConnectSession.LOCKDOWN_PORT
                )
                val identityStore = PairingIdentityStore(this)
                val systemBuid = identityStore.getOrCreateSystemBuid()
                val hostId = identityStore.getOrCreateHostId()
                val store = EncryptedPairRecordStore(this)
                connect.use { session ->
                    LockdownSessionChannel(session.inputStream, session.outputStream).use { channel ->
                        AppleLockdownPairingClient { record -> store.save(record) }.pair(
                            channel = channel,
                            deviceId = serial,
                            systemBuid = systemBuid,
                            hostId = hostId
                        )
                    }
                }
                runOnUiThread {
                    pairingInProgress = false
                    updatePairingActions(serial)
                    pairButton.text = "Đã ghép đôi • Ghép lại"
                    pairButton.isEnabled = true
                    status.text = "Ghép đôi Lockdown thành công. Hồ sơ tin cậy đã được mã hóa trong Android Keystore. Nếu iPhone hiện hộp thoại Tin cậy, hãy xác nhận trên iPhone."
                    diagnosticReport += "\\n\\nLockdown Pair: thành công; UDID=$serial; hồ sơ được lưu mã hóa."
                }
            } catch (error: Exception) {
                runOnUiThread {
                    pairingInProgress = false
                    pairButton.text = "Thử ghép đôi lại"
                    pairButton.isEnabled = attachedUsbmuxDeviceId != null && !attachedDeviceSerial.isNullOrBlank()
                    status.text = "Ghép đôi Lockdown thất bại: ${error.message ?: "lỗi không xác định"}. Hãy mở khóa iPhone, xác nhận Tin cậy và thử lại."
                    diagnosticReport += "\\n\\nLockdown Pair thất bại: ${error.message}"
                }
            }
        }, "BoSideload-lockdown-pair").apply { isDaemon = true; start() }
    }

    private fun shareDiagnosticReport() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Bo Sideload USB diagnostics")
            putExtra(Intent.EXTRA_TEXT, diagnosticReport)
        }
        startActivity(Intent.createChooser(intent, "Chia sẻ báo cáo USB"))
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val intent = Intent(permissionAction).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        try {
            pendingPermissionDeviceId = device.deviceId
            status.text = "Đang chờ bạn xác nhận quyền truy cập USB..."
            usbManager.requestPermission(device, PendingIntent.getBroadcast(this, device.deviceId, intent, flags))
        } catch (error: Exception) {
            pendingPermissionDeviceId = null
            status.text = "Không thể yêu cầu quyền USB: ${error.message ?: "lỗi không xác định"}"
        }
    }

    private fun hex(value: Int): String = "0x" + value.toString(16).uppercase().padStart(4, '0')
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SIGNED_IPA_PATH = "com.bosideload.android.extra.SIGNED_IPA_PATH"
        internal const val VERIFIED_DEVICE_PREFS = "bo_verified_iphone"
        internal const val KEY_LAST_VERIFIED_UDID = "last_verified_udid"
        private const val MAX_INSTALL_IPA_BYTES = 512L * 1024L * 1024L
        private const val SERVICE_IO_TIMEOUT_MS = 120_000
        private const val APPLE_VENDOR_ID = 0x05AC
    }
}
