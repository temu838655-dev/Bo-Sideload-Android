package com.bosideload.android

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Opens a legacy Apple Wi-Fi Sync Lockdown service only for a device whose
 * Bonjour authTag matched an already stored pairing record. The authTag is
 * treated only as candidate selection; Lockdown TLS performs authentication.
 */
internal object WifiLockdownConnector {
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val HANDSHAKE_READ_TIMEOUT_MS = 15_000
    private const val SESSION_READ_TIMEOUT_MS = 30_000

    @Throws(IOException::class)
    fun connect(
        hostAddress: String,
        port: Int,
        record: PairRecord
    ): LockdownTlsSession {
        require(hostAddress.isNotBlank()) { "Wi-Fi device address is missing" }
        require(port in 1..65535) { "Wi-Fi service port is invalid" }
        record.validate()

        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(
                InetSocketAddress(hostAddress, port),
                CONNECT_TIMEOUT_MS
            )
            socket.soTimeout = HANDSHAKE_READ_TIMEOUT_MS

            val plainChannel = LockdownSessionChannel(
                socket.getInputStream(),
                socket.getOutputStream()
            )
            val authenticated = LockdownTlsSession.start(
                plainChannel = plainChannel,
                transportSocket = socket,
                record = record
            )
            socket.soTimeout = SESSION_READ_TIMEOUT_MS
            return authenticated
        } catch (error: Exception) {
            runCatching { socket.close() }
            if (error is IOException) throw error
            throw IOException("Could not authenticate Apple Wi-Fi Lockdown session", error)
        }
    }
}
