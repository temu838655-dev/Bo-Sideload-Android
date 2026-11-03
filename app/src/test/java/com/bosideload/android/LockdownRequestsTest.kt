package com.bosideload.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockdownRequestsTest {
    private fun decode(bytes: ByteArray) = LockdownPlistCodec.decode(bytes)

    @Test fun queryTypeHasExpectedRequestEnvelope() {
        val message = decode(LockdownRequests.queryType())
        assertEquals("QueryType", message["Request"])
        assertEquals("Bo-Sideload Android", message["Label"])
        assertEquals(2, message.size)
    }

    @Test fun getValueSupportsAllValuesAndDomainScopedKey() {
        val all = decode(LockdownRequests.getValue())
        assertEquals("GetValue", all["Request"])
        assertFalse(all.containsKey("Domain"))
        assertFalse(all.containsKey("Key"))

        val scoped = decode(LockdownRequests.getValue("com.apple.mobile.installation_proxy", "Capabilities"))
        assertEquals("com.apple.mobile.installation_proxy", scoped["Domain"])
        assertEquals("Capabilities", scoped["Key"])
    }

    @Test fun startSessionIncludesCallerSuppliedIdentifiers() {
        val message = decode(LockdownRequests.startSession("HOST-UUID", "SYSTEM-BUID"))
        assertEquals("StartSession", message["Request"])
        assertEquals("HOST-UUID", message["HostID"])
        assertEquals("SYSTEM-BUID", message["SystemBUID"])
    }

    @Test fun rejectsBlankSessionIdentifiers() {
        assertTrue(runCatching { LockdownRequests.startSession("", "BUID") }.isFailure)
        assertTrue(runCatching { LockdownRequests.startSession("HOST", " ") }.isFailure)
    }

    @Test fun rejectsBlankGetValueSelectors() {
        assertTrue(runCatching { LockdownRequests.getValue(" ", null) }.isFailure)
        assertTrue(runCatching { LockdownRequests.getValue(null, "") }.isFailure)
    }
}
