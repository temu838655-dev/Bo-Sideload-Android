package com.bosideload.android

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Opens one device service through usbmux Connect.
 *
 * The Connect request/reply uses the 16-byte usbmux packet envelope. Once
 * Result(0) is received, the USB bulk endpoints become a raw byte stream for
 * the selected service; Lockdown's own length-prefixed plist framing is then
 * handled by LockdownSessionChannel.
 */
internal class UsbMuxConnectSession private constructor(
    private val transport: UsbMuxTransport
) : Closeable {
    private var closed = false

    val inputStream: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            check(!closed) { "USBMux Connect session is closed" }
            require(offset >= 0 && length >= 0 && offset <= target.size - length) {
                "invalid read range"
            }
            if (length == 0) return 0
            return transport.readRaw(target, offset, length)
        }

        override fun close() = this@UsbMuxConnectSession.close()
    }

    val outputStream: OutputStream = object : OutputStream() {
        override fun write(value: Int) {
            val one = byteArrayOf(value.toByte())
            write(one, 0, 1)
        }

        override fun write(source: ByteArray, offset: Int, length: Int) {
            check(!closed) { "USBMux Connect session is closed" }
            require(offset >= 0 && length >= 0 && offset <= source.size - length) {
                "invalid write range"
            }
            if (length == 0) return
            transport.writeRaw(source, offset, length)
        }

        override fun close() = this@UsbMuxConnectSession.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        transport.close()
    }

    companion object {
        const val LOCKDOWN_PORT = 62078

        /**
         * Sends Connect and returns only after the matching successful Result.
         * A non-zero result or mismatched response tag never exposes raw streams.
         */
        @Throws(IOException::class)
        fun connect(
            transport: UsbMuxTransport,
            deviceId: Long,
            devicePort: Int = LOCKDOWN_PORT,
            tag: Long = 1L
        ): UsbMuxConnectSession {
            require(tag in 0L..0xFFFF_FFFFL) { "tag out of range" }
            try {
                transport.writePacket(
                    UsbMuxPlistRequests.PROTOCOL_MESSAGE_PLIST,
                    tag,
                    UsbMuxPlistRequests.connectPayload(deviceId, devicePort)
                )
                val reply = transport.readPacket()
                if (reply.header.message != UsbMuxPacketHeader.MESSAGE_PLIST) {
                    throw IOException("USBMux Connect returned an unsupported packet type")
                }
                if (reply.header.tag != tag) {
                    throw IOException("USBMux Connect response tag does not match request")
                }
                val response = UsbMuxResponseParser.parse(reply)
                val result = response as? UsbMuxResponseParser.Response.Result
                    ?: throw IOException("USBMux Connect did not return a Result response")
                if (result.number != 0L) {
                    throw IOException("USBMux Connect failed with result ${result.number}")
                }
                return UsbMuxConnectSession(transport)
            } catch (error: IOException) {
                runCatching { transport.close() }
                throw error
            } catch (error: Exception) {
                runCatching { transport.close() }
                throw IOException("Could not establish USBMux Connect session", error)
            }
        }
    }
}
