package com.bosideload.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LockdownResponseParserTest {
    @Test fun parsesQueryTypeValue() {
        val bytes = LockdownPlistCodec.encode(mapOf(
            "Request" to "QueryType",
            "Value" to "com.apple.mobile.lockdown"
        ))
        val result = LockdownResponseParser.parse(bytes) as LockdownResponseParser.Response.Success
        assertEquals("QueryType", result.request)
        assertEquals("com.apple.mobile.lockdown", result.value)
        assertNull(result.sessionId)
    }

    @Test fun parsesStartSessionFields() {
        val bytes = LockdownPlistCodec.encode(mapOf(
            "Request" to "StartSession",
            "SessionID" to "SESSION-1",
            "EnableSessionSSL" to true
        ))
        val result = LockdownResponseParser.parse(bytes) as LockdownResponseParser.Response.Success
        assertEquals("SESSION-1", result.sessionId)
        assertEquals(true, result.enableSessionSsl)
    }

    @Test fun preservesEscrowBagFromSuccessfulPairResponse() {
        val escrow = byteArrayOf(3, 1, 4, 1, 5)
        val result = LockdownResponseParser.parse(
            LockdownPlistCodec.encode(mapOf("Request" to "Pair", "EscrowBag" to escrow))
        ) as LockdownResponseParser.Response.Success
        assertEquals("Pair", result.request)
        org.junit.Assert.assertArrayEquals(escrow, result.escrowBag)
    }

    @Test fun errorResponseIsNeverReportedAsSuccess() {
        val bytes = LockdownPlistCodec.encode(mapOf(
            "Request" to "StartSession",
            "Error" to "InvalidHostID",
            "ErrorDescription" to "Host is not paired"
        ))
        val result = LockdownResponseParser.parse(bytes) as LockdownResponseParser.Response.Failure
        assertEquals("InvalidHostID", result.error)
        assertEquals("Host is not paired", result.description)
    }

    @Test fun missingErrorMeansSuccessEvenWithoutValue() {
        val result = LockdownResponseParser.parse(
            LockdownPlistCodec.encode(mapOf("Request" to "Pair"))
        ) as LockdownResponseParser.Response.Success
        assertEquals("Pair", result.request)
        assertNull(result.value)
    }
}
