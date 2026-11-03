package com.bosideload.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class LockdownSessionChannelTest {
    @Test
    fun exchangesRequestAndParsesMatchingResponse() {
        val response = LockdownPlistCodec.encode(mapOf(
            "Request" to "QueryType",
            "Value" to "com.apple.mobile.lockdown"
        ))
        val output = ByteArrayOutputStream()
        val channel = LockdownSessionChannel(
            ByteArrayInputStream(LockdownPacketCodec.encode(response)),
            output
        )

        val result = channel.exchange(LockdownRequests.queryType())
            as LockdownResponseParser.Response.Success

        assertEquals("com.apple.mobile.lockdown", result.value)
        val sent = LockdownPacketCodec.read(ByteArrayInputStream(output.toByteArray()))
        assertEquals("QueryType", LockdownPlistCodec.decode(sent)["Request"])
    }

    @Test
    fun returnsLockdownErrorsAsFailures() {
        val response = LockdownPlistCodec.encode(mapOf(
            "Request" to "GetValue",
            "Error" to "MissingValue"
        ))
        val channel = LockdownSessionChannel(
            ByteArrayInputStream(LockdownPacketCodec.encode(response)),
            ByteArrayOutputStream()
        )

        val result = channel.exchange(LockdownRequests.getValue())
            as LockdownResponseParser.Response.Failure

        assertEquals("MissingValue", result.error)
    }

    @Test
    fun rejectsResponseForDifferentRequest() {
        val response = LockdownPlistCodec.encode(mapOf(
            "Request" to "GetValue",
            "Value" to "unexpected"
        ))
        val channel = LockdownSessionChannel(
            ByteArrayInputStream(LockdownPacketCodec.encode(response)),
            ByteArrayOutputStream()
        )

        assertThrows(IOException::class.java) {
            channel.exchange(LockdownRequests.queryType())
        }
    }

    @Test
    fun rejectsMalformedRequestBeforeWriting() {
        val output = ByteArrayOutputStream()
        val channel = LockdownSessionChannel(ByteArrayInputStream(byteArrayOf()), output)

        assertThrows(IOException::class.java) {
            channel.exchange("not a plist".toByteArray())
        }
        assertEquals(0, output.size())
    }

    @Test
    fun rejectsMissingRequestField() {
        val output = ByteArrayOutputStream()
        val channel = LockdownSessionChannel(
            ByteArrayInputStream(byteArrayOf()),
            output
        )

        assertThrows(IOException::class.java) {
            channel.exchange(LockdownPlistCodec.encode(mapOf("Label" to "Bo")))
        }
        assertEquals(0, output.size())
    }

    @Test
    fun refusesExchangeAfterClose() {
        val channel = LockdownSessionChannel(
            ByteArrayInputStream(byteArrayOf()),
            ByteArrayOutputStream()
        )
        channel.close()

        assertThrows(IllegalStateException::class.java) {
            channel.exchange(LockdownRequests.queryType())
        }
    }
}
