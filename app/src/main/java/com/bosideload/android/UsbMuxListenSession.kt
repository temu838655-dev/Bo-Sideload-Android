package com.bosideload.android

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal synchronous usbmux Listen session over an already-open USB transport.
 * The first response is commonly Result(0), followed by Attached/Detached events.
 */
internal class UsbMuxListenSession(
    private val transport: AndroidUsbMuxTransport
) : Closeable {
    private var started = false
    private val closed = AtomicBoolean(false)
    private var nextTag = 1L

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        check(!closed.get()) { "usbmux Listen session is closed" }
        check(!started) { "usbmux Listen session has already started" }
        transport.writePacket(
            UsbMuxPlistRequests.PROTOCOL_MESSAGE_PLIST,
            nextTag++,
            UsbMuxPlistRequests.listenPayload()
        )
        started = true
    }

    @Synchronized
    @Throws(IOException::class)
    fun readNextResponse(): UsbMuxResponseParser.Response {
        check(!closed.get()) { "usbmux Listen session is closed" }
        check(started) { "start Listen before reading usbmux events" }
        return UsbMuxResponseParser.parse(transport.readPacket())
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        transport.close()
    }
}
