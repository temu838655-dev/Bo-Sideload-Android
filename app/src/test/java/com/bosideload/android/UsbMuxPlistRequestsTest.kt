package com.bosideload.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UsbMuxPlistRequestsTest {
    @Test
    fun listenRequestContainsExpectedProtocolFields() {
        val decoded = LockdownPlistCodec.decode(UsbMuxPlistRequests.listenPayload())
        assertEquals("Listen", decoded["MessageType"])
        assertEquals("Bo-Sideload Android", decoded["ClientVersionString"])
        assertEquals("Bo-Sideload", decoded["ProgName"])
        assertEquals("com.bosideload.android", decoded["BundleID"])
        assertEquals(3L, decoded["kLibUSBMuxVersion"])
    }

    @Test
    fun connectRequestContainsDeviceAndNetworkOrderPort() {
        val decoded = LockdownPlistCodec.decode(
            UsbMuxPlistRequests.connectPayload(deviceId = 42, devicePort = 62078)
        )
        assertEquals("Connect", decoded["MessageType"])
        assertEquals(42L, decoded["DeviceID"])
        assertEquals(0x7EF2L, decoded["PortNumber"])
    }

    @Test
    fun listenPacketUsesPlistMessageAndPreservesTag() {
        val packet = UsbMuxPacketCodec.read(
            UsbMuxPlistRequests.listenPacket(tag = 99).inputStream()
        )
        assertEquals(UsbMuxPlistRequests.PROTOCOL_MESSAGE_PLIST, packet.header.message)
        assertEquals(99L, packet.header.tag)
        assertEquals("Listen", LockdownPlistCodec.decode(packet.payload)["MessageType"])
    }

    @Test
    fun connectPacketPreservesTagAndDeviceId() {
        val packet = UsbMuxPacketCodec.read(
            UsbMuxPlistRequests.connectPacket(tag = 7, deviceId = 12, devicePort = 62078).inputStream()
        )
        assertEquals(7L, packet.header.tag)
        val decoded = LockdownPlistCodec.decode(packet.payload)
        assertEquals(12L, decoded["DeviceID"])
        assertEquals(0x7EF2L, decoded["PortNumber"])
    }

    @Test
    fun rejectsInvalidDeviceIdAndPort() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbMuxPlistRequests.connectPayload(deviceId = 0, devicePort = 62078)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UsbMuxPlistRequests.connectPayload(deviceId = 1, devicePort = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UsbMuxPlistRequests.connectPayload(deviceId = 1, devicePort = 65536)
        }
    }
}
