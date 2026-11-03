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

    @Test fun pairRequestContainsOnlyPublicCertificatesAndIdentifiers() {
        val message = decode(LockdownRequests.pair(
            "DEVICE-CERT".toByteArray(),
            "HOST-CERT".toByteArray(),
            "ROOT-CERT".toByteArray(),
            "HOST-ID",
            "SYSTEM-BUID"
        ))
        assertEquals("Pair", message["Request"])
        assertEquals("2", message["ProtocolVersion"])
        val pairRecord = message["PairRecord"] as Map<*, *>
        assertEquals("DEVICE-CERT", String(pairRecord["DeviceCertificate"] as ByteArray))
        assertEquals("HOST-CERT", String(pairRecord["HostCertificate"] as ByteArray))
        assertEquals("ROOT-CERT", String(pairRecord["RootCertificate"] as ByteArray))
        assertEquals("HOST-ID", pairRecord["HostID"])
        assertEquals("SYSTEM-BUID", pairRecord["SystemBUID"])
        assertFalse(pairRecord.containsKey("HostPrivateKey"))
        assertFalse(pairRecord.containsKey("RootPrivateKey"))
    }

    @Test fun pairRequestRejectsMissingCertificatesOrIdentifiers() {
        assertTrue(runCatching {
            LockdownRequests.pair(byteArrayOf(), byteArrayOf(1), byteArrayOf(1), "H", "B")
        }.isFailure)
        assertTrue(runCatching {
            LockdownRequests.pair(byteArrayOf(1), byteArrayOf(1), byteArrayOf(1), "", "B")
        }.isFailure)
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
