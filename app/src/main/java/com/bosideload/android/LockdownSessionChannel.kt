package com.bosideload.android

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Synchronous Lockdown request/response channel over an already-established
 * byte stream (normally the Lockdown service socket reached through usbmux).
 *
 * This class only handles packet framing and plist response validation. It
 * does not perform Pair, create trust records, negotiate TLS, or install IPAs.
 */
internal class LockdownSessionChannel(
    private val input: InputStream,
    private val output: OutputStream,
    private val maxPayloadSize: Int = LockdownPacketCodec.DEFAULT_MAX_PAYLOAD_SIZE
) : Closeable {
    private var closed = false

    init {
        require(maxPayloadSize > 0) { "maximum payload size must be positive" }
    }

    @Synchronized
    @Throws(IOException::class)
    fun exchange(requestPayload: ByteArray): LockdownResponseParser.Response {
        check(!closed) { "Lockdown session channel is closed" }
        val request = try {
            LockdownPlistCodec.decode(requestPayload)["Request"] as? String
        } catch (error: Exception) {
            throw IOException("invalid Lockdown request plist", error)
        } ?: throw IOException("Lockdown request is missing Request")

        try {
            LockdownPacketCodec.write(output, requestPayload, maxPayloadSize)
            val responsePayload = LockdownPacketCodec.read(input, maxPayloadSize)
            val response = LockdownResponseParser.parse(responsePayload)
            val responseRequest = when (response) {
                is LockdownResponseParser.Response.Success -> response.request
                is LockdownResponseParser.Response.Failure -> response.request
            }
            if (responseRequest != null && responseRequest != request) {
                throw IOException("Lockdown response request does not match")
            }
            return response
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("could not exchange Lockdown request", error)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { input.close() }
        runCatching { output.close() }
    }
}
