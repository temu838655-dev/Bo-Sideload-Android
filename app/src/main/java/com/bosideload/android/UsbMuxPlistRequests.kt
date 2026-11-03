package com.bosideload.android

/**
 * Builds usbmuxd protocol-v1 plist requests.
 *
 * This creates request packets only. A USB transport, response/event parser,
 * Lockdown pairing session and installation workflow are separate components.
 */
internal object UsbMuxPlistRequests {
    const val PROTOCOL_MESSAGE_PLIST = 8L
    const val LIBUSBMUX_VERSION = 3L

    private const val CLIENT_VERSION = "Bo-Sideload Android"
    private const val PROGRAM_NAME = "Bo-Sideload"
    private const val BUNDLE_ID = "com.bosideload.android"

    fun listenPayload(): ByteArray = LockdownPlistCodec.encode(baseMessage("Listen"))

    fun connectPayload(deviceId: Long, devicePort: Int): ByteArray {
        require(deviceId in 1L..0xFFFF_FFFFL) { "device id must be an unsigned non-zero 32-bit value" }
        require(devicePort in 1..0xFFFF) { "device port must be an unsigned 16-bit value" }
        return LockdownPlistCodec.encode(
            baseMessage("Connect") + mapOf(
                "DeviceID" to deviceId,
                "PortNumber" to portToNetworkOrder(devicePort).toLong()
            )
        )
    }

    fun listenPacket(tag: Long): ByteArray =
        UsbMuxPacketCodec.encode(PROTOCOL_MESSAGE_PLIST, tag, listenPayload())

    fun connectPacket(tag: Long, deviceId: Long, devicePort: Int): ByteArray =
        UsbMuxPacketCodec.encode(PROTOCOL_MESSAGE_PLIST, tag, connectPayload(deviceId, devicePort))

    /**
     * usbmuxd represents the TCP port as a 16-bit value already converted
     * with htons(). This explicit byte swap matches that wire representation.
     */
    internal fun portToNetworkOrder(port: Int): Int {
        require(port in 1..0xFFFF) { "device port must be an unsigned 16-bit value" }
        return ((port and 0xFF) shl 8) or ((port ushr 8) and 0xFF)
    }

    private fun baseMessage(messageType: String): Map<String, Any?> = linkedMapOf(
        "BundleID" to BUNDLE_ID,
        "ClientVersionString" to CLIENT_VERSION,
        "MessageType" to messageType,
        "ProgName" to PROGRAM_NAME,
        "kLibUSBMuxVersion" to LIBUSBMUX_VERSION
    )
}
