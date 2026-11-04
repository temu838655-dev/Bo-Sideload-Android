package com.bosideload.android

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-level Android USB Host transport for Apple's usbmux interface.
 *
 * The caller must obtain UsbManager permission before opening this transport.
 * This class only moves usbmux packet bytes; it does not pair with Lockdown.
 */
internal class AndroidUsbMuxTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint,
    private val timeoutMs: Int
) : UsbMuxTransport {

    private val closed = AtomicBoolean(false)
    private val transferContext: String = "USBMux interface id=${usbInterface.id}, IN=0x${input.address.toString(16).padStart(2, '0')} (maxPacket=${input.maxPacketSize}), OUT=0x${output.address.toString(16).padStart(2, '0')} (maxPacket=${output.maxPacketSize})"

    @Synchronized
    @Throws(IOException::class)
    override fun writePacket(message: Long, tag: Long, payload: ByteArray) {
        checkOpen()
        val packet = UsbMuxPacketCodec.encode(message, tag, payload)
        var offset = 0
        while (offset < packet.size) {
            val length = minOf(MAX_BULK_TRANSFER_BYTES, packet.size - offset)
            val count = connection.bulkTransfer(
                output, packet, offset, length, timeoutMs
            )
            if (count <= 0) throw IOException("USB bulk OUT failed (result=$count; $transferContext)")
            offset += count
        }
    }

    @Throws(IOException::class)
    override fun readPacket(maxPacketSize: Long): UsbMuxPacketCodec.Packet {
        checkOpen()
        require(maxPacketSize in UsbMuxPacketHeader.HEADER_SIZE.toLong()..Int.MAX_VALUE.toLong()) {
            "invalid maximum packet size"
        }
        val headerBytes = ByteArray(UsbMuxPacketHeader.HEADER_SIZE)
        readFully(headerBytes)
        val header = try {
            UsbMuxPacketHeader.decode(headerBytes, maxPacketSize)
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid usbmux packet header", error)
        }
        val payload = ByteArray((header.length - UsbMuxPacketHeader.HEADER_SIZE).toInt())
        readFully(payload)
        return UsbMuxPacketCodec.Packet(header, payload)
    }

    /**
     * Writes raw service bytes after a successful usbmux Connect. No usbmux
     * header is added in this mode.
     */
    @Synchronized
    @Throws(IOException::class)
    override fun writeRaw(bytes: ByteArray, offset: Int, length: Int) {
        checkOpen()
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
            "invalid write range"
        }
        var written = 0
        while (written < length) {
            val chunk = minOf(MAX_BULK_TRANSFER_BYTES, length - written)
            val count = connection.bulkTransfer(
                output, bytes, offset + written, chunk, timeoutMs
            )
            if (count <= 0) throw IOException("USB raw bulk OUT failed (result=$count; $transferContext)")
            written += count
        }
    }

    /**
     * Reads raw service bytes after a successful usbmux Connect. Each call may
     * return fewer bytes than requested, as required by InputStream semantics.
     */
    @Throws(IOException::class)
    override fun readRaw(target: ByteArray, offset: Int, length: Int): Int {
        checkOpen()
        require(offset >= 0 && length >= 0 && offset <= target.size - length) {
            "invalid read range"
        }
        if (length == 0) return 0
        val chunk = minOf(MAX_BULK_TRANSFER_BYTES, length)
        while (true) {
            val count = connection.bulkTransfer(input, target, offset, chunk, timeoutMs)
            // Android UsbDeviceConnection.bulkTransfer() returns 0 when no
            // bytes arrive before the transfer timeout on supported API levels.
            // Treat it as a timeout instead of spinning and repeatedly polling.
            if (count <= 0) throw IOException("USB raw bulk IN failed (result=$count; $transferContext)")
            return count
        }
    }

    private fun readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            checkOpen()
            val length = minOf(MAX_BULK_TRANSFER_BYTES, target.size - offset)
            val count = connection.bulkTransfer(
                input, target, offset, length, timeoutMs
            )
            // bulkTransfer() may return 0 on timeout. Do not retry in a tight
            // loop: surface the timeout so the monitor can close and recover.
            if (count <= 0) throw EOFException("USB bulk IN failed (result=$count; $transferContext)")
            offset += count
        }
    }

    private fun checkOpen() {
        check(!closed.get()) { "USB mux transport is closed" }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Close immediately so a blocking bulkTransfer on the worker can unwind.
        connection.close()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5000

        /**
         * Finds the Apple USBMux vendor-specific interface (class FF, subclass FE,
         * protocol 02) and its bulk IN/OUT endpoints.
         */
        fun open(
            usbManager: UsbManager,
            device: UsbDevice,
            timeoutMs: Int = DEFAULT_TIMEOUT_MS
        ): AndroidUsbMuxTransport {
            require(timeoutMs >= 0) { "timeout must not be negative; use 0 for an indefinite USB read" }
            check(usbManager.hasPermission(device)) { "Android USB permission has not been granted" }

            var selected: UsbInterface? = null
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null

            for (index in 0 until device.interfaceCount) {
                val candidate = device.getInterface(index)
                if (candidate.interfaceClass != USBMUX_CLASS ||
                    candidate.interfaceSubclass != USBMUX_SUBCLASS ||
                    candidate.interfaceProtocol != USBMUX_PROTOCOL) continue

                var candidateIn: UsbEndpoint? = null
                var candidateOut: UsbEndpoint? = null
                for (endpointIndex in 0 until candidate.endpointCount) {
                    val endpoint = candidate.getEndpoint(endpointIndex)
                    if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    when (endpoint.direction) {
                        UsbConstants.USB_DIR_IN -> candidateIn = endpoint
                        UsbConstants.USB_DIR_OUT -> candidateOut = endpoint
                    }
                }
                if (candidateIn != null && candidateOut != null) {
                    selected = candidate
                    input = candidateIn
                    output = candidateOut
                    break
                }
            }

            val usbInterface = selected
                ?: throw IOException("Apple USBMux interface with bulk IN/OUT endpoints was not found")
            val inEndpoint = input ?: throw IOException("USBMux bulk IN endpoint was not found")
            val outEndpoint = output ?: throw IOException("USBMux bulk OUT endpoint was not found")
            val connection = usbManager.openDevice(device)
                ?: throw IOException("Android could not open the USB device")

            if (!connection.claimInterface(usbInterface, true)) {
                connection.close()
                throw IOException("Could not claim the Apple USBMux interface")
            }
            return AndroidUsbMuxTransport(connection, usbInterface, inEndpoint, outEndpoint, timeoutMs)
        }

        private const val USBMUX_CLASS = 0xFF
        private const val USBMUX_SUBCLASS = 0xFE
        private const val USBMUX_PROTOCOL = 0x02
        // Android USB Host has a 16 KiB bulkTransfer limit on older API levels.
        private const val MAX_BULK_TRANSFER_BYTES = 16 * 1024
    }
}
