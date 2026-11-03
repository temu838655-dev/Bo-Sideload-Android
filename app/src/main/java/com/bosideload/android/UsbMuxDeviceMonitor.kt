package com.bosideload.android

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background listener for usbmux device attach/detach notifications. A 30-second
 * read timeout ends the session; it never retries a partially consumed packet.
 * A successful Listen acknowledgement is not a Lockdown pairing.
 */
internal class UsbMuxDeviceMonitor(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val listener: Listener
) : Closeable {
    interface Listener {
        fun onStatus(message: String)
        fun onDeviceAttached(device: UsbMuxResponseParser.Device)
        fun onDeviceDetached(deviceId: Long)
        fun onError(message: String)
    }

    private val stopped = AtomicBoolean(false)
    @Volatile private var session: UsbMuxListenSession? = null
    @Volatile private var worker: Thread? = null

    @Synchronized
    fun start() {
        check(worker == null) { "USBMux monitor has already been started" }
        worker = Thread({
            runMonitor()
        }, "BoSideload-usbmux-listener").apply {
            isDaemon = true
            start()
        }
    }

    private fun runMonitor() {
        try {
            listener.onStatus("Đang mở giao diện USBMux của Apple…")
            val transport = AndroidUsbMuxTransport.open(usbManager, device, timeoutMs = 30_000)
            val activeSession = UsbMuxListenSession(transport)
            session = activeSession
            activeSession.start()
            listener.onStatus("Đã gửi yêu cầu usbmux Listen. Đang chờ iPhone phản hồi…")
            while (!stopped.get()) {
                when (val response = activeSession.readNextResponse()) {
                    is UsbMuxResponseParser.Response.Result -> {
                        if (response.number == 0L) {
                            listener.onStatus("usbmux Listen được chấp nhận. Đang chờ sự kiện thiết bị…")
                        } else {
                            throw IllegalStateException("usbmux từ chối Listen (Result=${response.number})")
                        }
                    }
                    is UsbMuxResponseParser.Response.Attached ->
                        listener.onDeviceAttached(response.device)
                    is UsbMuxResponseParser.Response.Detached ->
                        listener.onDeviceDetached(response.deviceId)
                    is UsbMuxResponseParser.Response.Unknown ->
                        listener.onStatus("usbmux gửi loại thông điệp chưa hỗ trợ: ${response.messageType}")
                }
            }
        } catch (error: Exception) {
            if (!stopped.get()) {
                listener.onError(error.message ?: "Không thể duy trì phiên USBMux.")
            }
        } finally {
            try { session?.close() } catch (_: Exception) { }
            session = null
            if (stopped.get()) listener.onStatus("Đã dừng theo dõi usbmux.")
        }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        // Closing the session releases the claimed interface and unblocks I/O.
        try { session?.close() } catch (_: Exception) { }
    }
}
