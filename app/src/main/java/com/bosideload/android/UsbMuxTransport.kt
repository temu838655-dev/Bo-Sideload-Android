package com.bosideload.android

import java.io.Closeable
import java.io.IOException

/**
 * USBMux transport contract. Before Connect it carries usbmux packets; after
 * a successful Connect it carries the selected device service's raw TCP byte
 * stream, without additional usbmux headers.
 */
internal interface UsbMuxTransport : Closeable {
    @Throws(IOException::class)
    fun writePacket(message: Long, tag: Long, payload: ByteArray)

    @Throws(IOException::class)
    fun readPacket(maxPacketSize: Long = UsbMuxPacketHeader.DEFAULT_MAX_PACKET_SIZE): UsbMuxPacketCodec.Packet

    @Throws(IOException::class)
    fun writeRaw(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size)

    @Throws(IOException::class)
    fun readRaw(target: ByteArray, offset: Int = 0, length: Int = target.size): Int
}
