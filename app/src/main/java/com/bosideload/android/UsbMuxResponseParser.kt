package com.bosideload.android

/**
 * Interprets plist-form usbmuxd replies. It does not own a USB transport.
 */
internal object UsbMuxResponseParser {
    data class Device(
        val deviceId: Long,
        val serialNumber: String?,
        val connectionType: String?,
        val productId: Long?
    )

    sealed class Response {
        data class Result(val number: Long) : Response()
        data class Attached(val device: Device) : Response()
        data class Detached(val deviceId: Long) : Response()
        data class Unknown(val messageType: String) : Response()
    }

    fun parse(packet: UsbMuxPacketCodec.Packet): Response {
        require(packet.header.message == UsbMuxPacketHeader.MESSAGE_PLIST) {
            "unsupported usbmux packet message"
        }
        val values = LockdownPlistCodec.decode(packet.payload)
        val messageType = values["MessageType"] as? String
            ?: throw IllegalArgumentException("usbmux response has no MessageType")

        return when (messageType) {
            "Result" -> {
                val number = values["Number"] as? Long
                    ?: throw IllegalArgumentException("usbmux Result has no numeric Number")
                Response.Result(number)
            }
            "Attached" -> Response.Attached(parseDevice(values))
            "Detached" -> {
                val id = values["DeviceID"] as? Long
                    ?: throw IllegalArgumentException("usbmux Detached has no DeviceID")
                require(id in 1L..0xFFFF_FFFFL) { "invalid detached DeviceID" }
                Response.Detached(id)
            }
            else -> Response.Unknown(messageType)
        }
    }

    private fun parseDevice(values: Map<String, Any?>): Device {
        val id = values["DeviceID"] as? Long
            ?: throw IllegalArgumentException("usbmux Attached has no DeviceID")
        require(id in 1L..0xFFFF_FFFFL) { "invalid attached DeviceID" }
        val properties = values["Properties"] as? Map<*, *>
            ?: throw IllegalArgumentException("usbmux Attached has no Properties dictionary")
        val serial = properties["SerialNumber"] as? String
        val connection = properties["ConnectionType"] as? String
        val productId = (properties["ProductID"] as? Long)?.also {
            require(it in 0L..0xFFFFL) { "invalid ProductID" }
        }
        return Device(id, serial, connection, productId)
    }
}
