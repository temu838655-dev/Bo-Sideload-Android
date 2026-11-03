package com.bosideload.android

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockdownServiceStarterTest {
    private fun channel(reply: Map<String, Any?>): Pair<LockdownSessionChannel, ByteArrayOutputStream> {
        val framedReply = ByteArrayOutputStream().apply {
            LockdownPacketCodec.write(this, LockdownPlistCodec.encode(reply))
        }
        return LockdownSessionChannel(
            ByteArrayInputStream(framedReply.toByteArray()),
            ByteArrayOutputStream()
        ) to ByteArrayOutputStream()
    }

    @Test fun returnsServicePortAndTlsFlagFromLockdown() {
        val (session, _) = channel(mapOf(
            "Request" to "StartService",
            "Port" to 54321L,
            "EnableServiceSSL" to true
        ))
        val descriptor = LockdownServiceStarter.start(
            session,
            "com.apple.mobile.installation_proxy"
        )
        assertEquals("com.apple.mobile.installation_proxy", descriptor.identifier)
        assertEquals(54321, descriptor.port)
        assertTrue(descriptor.enableServiceSsl)
    }

    @Test fun absentTlsFlagDefaultsToFalse() {
        val (session, _) = channel(mapOf("Request" to "StartService", "Port" to 1234L))
        val descriptor = LockdownServiceStarter.start(session, "com.apple.afc")
        assertFalse(descriptor.enableServiceSsl)
    }

    @Test fun rejectsLockdownServiceError() {
        val (session, _) = channel(mapOf(
            "Request" to "StartService",
            "Error" to "ServiceProhibited"
        ))
        assertTrue(runCatching {
            LockdownServiceStarter.start(session, "com.apple.mobile.installation_proxy")
        }.exceptionOrNull() is IOException)
    }

    @Test fun rejectsMissingOrInvalidPort() {
        val missing = channel(mapOf("Request" to "StartService")).first
        assertTrue(runCatching {
            LockdownServiceStarter.start(missing, "com.apple.afc")
        }.exceptionOrNull() is IOException)

        val invalid = channel(mapOf("Request" to "StartService", "Port" to 70000L)).first
        assertTrue(runCatching {
            LockdownServiceStarter.start(invalid, "com.apple.afc")
        }.exceptionOrNull() is IOException)
    }

    @Test fun rejectsUnexpectedResponseType() {
        val (session, _) = channel(mapOf("Request" to "GetValue", "Port" to 1234L))
        assertTrue(runCatching {
            LockdownServiceStarter.start(session, "com.apple.afc")
        }.exceptionOrNull() is IOException)
    }
}
