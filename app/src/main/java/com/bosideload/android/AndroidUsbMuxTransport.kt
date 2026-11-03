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
) : Closeable {

    @Volatile
    private var closed = false

    @Synchronized
    @Throws(IOException::class)
    fun writePacket(message: Long, tag: Long, payload: ByteArray) {
        checkOpen()
        val packet = UsbMuxPacketCodec.encode(message, tag, payload)
        var offset = 0
        while (offset < packet.size) {
            val count = connection.bulkTransfer(
                output, packet, offset, packet.size - offset, timeoutMs
            )
            if (count <= 0) throw IOException("USB bulk OUT failed or timed out (result=$count)")
            offset += count
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun readPacket(maxPacketSize: Long = UsbMuxPacketHeader.DEFAULT_MAX_PACKET_SIZE): UsbMuxPacketCodec.Packet {
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

    private fun readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            checkOpen()
            val count = connection.bulkTransfer(
                input, target, offset, target.size - offset, timeoutMs
            )
            if (count < 0) throw EOFException("USB bulk IN failed or timed out (result=$count)")
            if (count == 0) throw EOFException("USB bulk IN returned no data")
            offset += count
        }
    }

    private fun checkOpen() {
        check(!closed) { "USB mux transport is closed" }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            connection.releaseInterface(usbInterface)
        } finally {
            connection.close()
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5000

        /**
         * Finds the standard Apple USBMux interface (class FE, subclass 01,
         * protocol 02) and its bulk IN/OUT endpoints.
         */
        fun open(
            usbManager: UsbManager,
            device: UsbDevice,
            timeoutMs: Int = DEFAULT_TIMEOUT_MS
        ): AndroidUsbMuxTransport {
            require(timeoutMs > 0) { "timeout must be positive" }
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

        private const val USBMUX_CLASS = 0xFE
        private const val USBMUX_SUBCLASS = 0x01
        private const val USBMUX_PROTOCOL = 0x02
    }
}
