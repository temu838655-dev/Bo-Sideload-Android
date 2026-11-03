package com.bosideload.android

import java.io.IOException

/**
 * Requests a named service from an already authenticated Lockdown session.
 *
 * The returned descriptor is only a service endpoint description. It does
 * not claim that a second usbmux connection or service TLS handshake worked.
 */
internal object LockdownServiceStarter {
    data class ServiceDescriptor(
        val identifier: String,
        val port: Int,
        val enableServiceSsl: Boolean
    )

    @Throws(IOException::class)
    fun start(
        channel: LockdownSessionChannel,
        identifier: String,
        escrowBag: ByteArray? = null
    ): ServiceDescriptor {
        if (identifier.isBlank()) throw IOException("service identifier must not be blank")
        val response = channel.exchange(LockdownRequests.startService(identifier, escrowBag))
        val success = response as? LockdownResponseParser.Response.Success
            ?: throw IOException("Lockdown rejected StartService")
        if (success.request != "StartService") {
            throw IOException("unexpected Lockdown response to StartService")
        }
        val port = success.port
            ?: throw IOException("StartService response has no Port")
        if (port !in 1L..65535L) {
            throw IOException("StartService returned an invalid service port")
        }
        return ServiceDescriptor(
            identifier = identifier,
            port = port.toInt(),
            enableServiceSsl = success.enableServiceSsl ?: false
        )
    }
}
