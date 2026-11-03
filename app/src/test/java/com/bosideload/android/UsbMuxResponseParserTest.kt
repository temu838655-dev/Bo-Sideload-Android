package com.bosideload.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class UsbMuxResponseParserTest {
    private fun packet(values: Map<String, Any?>): UsbMuxPacketCodec.Packet =
        UsbMuxPacketCodec.read(
            UsbMuxPacketCodec.encode(UsbMuxPacketHeader.MESSAGE_PLIST, 1, LockdownPlistCodec.encode(values)).inputStream()
        )

    @Test
    fun parsesSuccessfulResult() {
        assertEquals(UsbMuxResponseParser.Response.Result(0),
            UsbMuxResponseParser.parse(packet(mapOf("MessageType" to "Result", "Number" to 0L))))
    }

    @Test
    fun parsesAttachedDeviceProperties() {
        val response = UsbMuxResponseParser.parse(packet(mapOf(
            "MessageType" to "Attached",
            "DeviceID" to 23L,
            "Properties" to mapOf(
                "SerialNumber" to "SERIAL",
                "ConnectionType" to "USB",
                "ProductID" to 0x12A8L
            )
        ))) as UsbMuxResponseParser.Response.Attached
        assertEquals(23L, response.device.deviceId)
        assertEquals("SERIAL", response.device.serialNumber)
        assertEquals("USB", response.device.connectionType)
        assertEquals(0x12A8L, response.device.productId)
    }

    @Test
    fun parsesDetachedDevice() {
        assertEquals(UsbMuxResponseParser.Response.Detached(23),
            UsbMuxResponseParser.parse(packet(mapOf("MessageType" to "Detached", "DeviceID" to 23L))))
    }

    @Test
    fun preservesUnknownMessageTypes() {
        assertEquals(UsbMuxResponseParser.Response.Unknown("Paired"),
            UsbMuxResponseParser.parse(packet(mapOf("MessageType" to "Paired"))))
    }

    @Test
    fun rejectsMalformedResponses() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbMuxResponseParser.parse(packet(mapOf("MessageType" to "Result")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UsbMuxResponseParser.parse(packet(mapOf("MessageType" to "Attached", "DeviceID" to 2L)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UsbMuxResponseParser.parse(packet(mapOf("MessageType" to "Detached", "DeviceID" to 0L)))
        }
    }

    @Test
    fun optionalDevicePropertiesMayBeAbsent() {
        val response = UsbMuxResponseParser.parse(packet(mapOf(
            "MessageType" to "Attached",
            "DeviceID" to 1L,
            "Properties" to emptyMap<String, Any?>()
        ))) as UsbMuxResponseParser.Response.Attached
        assertNull(response.device.serialNumber)
        assertNull(response.device.connectionType)
        assertNull(response.device.productId)
    }
}
