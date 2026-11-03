package com.bosideload.android

/**
 * Normalizes Lockdown plist replies without interpreting an error as success.
 * Values are kept as decoded plist objects for higher-level service clients.
 */
internal object LockdownResponseParser {
    sealed class Response {
        data class Success(
            val request: String?,
            val value: Any?,
            val sessionId: String?,
            val enableSessionSsl: Boolean?,
            val escrowBag: ByteArray?,
            val port: Long? = null,
            val enableServiceSsl: Boolean? = null
        ) : Response()
        data class Failure(
            val request: String?,
            val error: String,
            val description: String?
        ) : Response()
    }

    fun parse(payload: ByteArray): Response {
        val message = LockdownPlistCodec.decode(payload)
        val request = message["Request"] as? String
        val error = message["Error"] as? String
        if (!error.isNullOrBlank()) {
            return Response.Failure(
                request = request,
                error = error,
                description = message["ErrorDescription"] as? String
            )
        }
        val sessionId = message["SessionID"] as? String
        val ssl = message["EnableSessionSSL"] as? Boolean
        val port = (message["Port"] as? Number)?.toLong()
        val serviceSsl = message["EnableServiceSSL"] as? Boolean
        return Response.Success(
            request = request,
            value = message["Value"],
            sessionId = sessionId,
            enableSessionSsl = ssl,
            escrowBag = message["EscrowBag"] as? ByteArray,
            port = port,
            enableServiceSsl = serviceSsl
        )
    }
}
