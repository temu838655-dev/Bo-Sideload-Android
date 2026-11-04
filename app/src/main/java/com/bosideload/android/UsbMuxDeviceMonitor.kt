package com.bosideload.android

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background listener for usbmux device attach/detach notifications. USB reads
 * use bounded transfer timeouts so a stalled OTG adapter cannot block forever.
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
    private val sessionLock = Any()
    @Volatile private var session: UsbMuxListenSession? = null
    @Volatile private var worker: Thread? = null

    @Synchronized
    fun start() {
        check(worker == null) { "USBMux monitor has already been started" }
        worker = Thread({ runMonitor() }, "BoSideload-usbmux-listener").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Some iPhones expose more than one interface with identical usbmux
     * descriptors. Probe each matching interface until Listen is explicitly
     * acknowledged; do not assume the first descriptor is the active one.
     */
    private fun openAcceptedListenSession(): UsbMuxListenSession {
        val candidates = (0 until device.interfaceCount)
            .map { index -> index to device.getInterface(index) }
            .filter { (_, candidate) ->
                candidate.interfaceClass == 0xFF &&
                    candidate.interfaceSubclass == 0xFE &&
                    candidate.interfaceProtocol == 0x02 &&
                    (0 until candidate.endpointCount).any { e ->
                        val ep = candidate.getEndpoint(e)
                        ep.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK &&
                            ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN
                    } &&
                    (0 until candidate.endpointCount).any { e ->
                        val ep = candidate.getEndpoint(e)
                        ep.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK &&
                            ep.direction == android.hardware.usb.UsbConstants.USB_DIR_OUT
                    }
            }
        if (candidates.isEmpty()) {
            throw java.io.IOException("Không tìm thấy interface USBMux có đủ bulk IN/OUT.")
        }

        val failures = mutableListOf<String>()
        for ((listIndex, candidate) in candidates) {
            if (stopped.get()) throw java.io.IOException("Đã dừng dò interface USBMux.")
            var session: UsbMuxListenSession? = null
            try {
                listener.onStatus(
                    "Đang thử USBMux interface id=${candidate.id}, listIndex=$listIndex " +
                        "(class=0x${candidate.interfaceClass.toString(16)}, " +
                        "subclass=0x${candidate.interfaceSubclass.toString(16)}, " +
                        "protocol=0x${candidate.interfaceProtocol.toString(16)}; " +
                        "${listIndex + 1}/${candidates.size})…"
                )
                val transport = AndroidUsbMuxTransport.open(
                    usbManager,
                    device,
                    timeoutMs = AndroidUsbMuxTransport.DEFAULT_TIMEOUT_MS,
                    interfaceId = candidate.id
                )
                session = UsbMuxListenSession(transport)
                session.start()
                when (val first = session.readNextResponse()) {
                    is UsbMuxResponseParser.Response.Result -> {
                        if (first.number == 0L) {
                            listener.onStatus("USBMux Listen được chấp nhận trên interface id=${candidate.id}, listIndex=$listIndex.")
                            return session
                        }
                        throw java.io.IOException("Listen Result=${first.number}")
                    }
                    else -> throw java.io.IOException("Phản hồi đầu tiên không phải Listen Result(0): $first")
                }
            } catch (error: Exception) {
                try { session?.close() } catch (_: Exception) {}
                val detail = error.message ?: error.javaClass.simpleName
                failures += "interface id=${candidate.id} (listIndex=$listIndex, class=0x${candidate.interfaceClass.toString(16)}/0x${candidate.interfaceSubclass.toString(16)}/0x${candidate.interfaceProtocol.toString(16)}): $detail"
            }
        }
        throw java.io.IOException(
            "Không interface USBMux nào xác nhận Listen. " + failures.joinToString(" | ")
        )
    }

    private fun runMonitor() {
        try {
            listener.onStatus("Đang mở giao diện USBMux của Apple…")
            // Never use an infinite bulkTransfer timeout. Some OTG adapters or
            // iOS USB states may not complete the first Listen write/read.
            // A bounded timeout keeps this worker recoverable.
            val activeSession = openAcceptedListenSession()
            // Publish the session atomically with respect to close(). If close()
            // wins while USB is opening, do not start a listener on a stopped monitor.
            val shouldStart = synchronized(sessionLock) {
                if (stopped.get()) {
                    false
                } else {
                    session = activeSession
                    true
                }
            }
            if (!shouldStart) {
                activeSession.close()
                return
            }
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
            synchronized(sessionLock) {
                try {
                    session?.close()
                } catch (_: Exception) {
                }
                session = null
            }
            if (stopped.get()) listener.onStatus("Đã dừng theo dõi usbmux.")
        }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        // Closing the session releases the claimed interface and unblocks I/O.
        synchronized(sessionLock) {
            try {
                session?.close()
            } catch (_: Exception) {
            }
        }
    }
}
