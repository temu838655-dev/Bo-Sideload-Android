package com.bosideload.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AfcClientPathValidationTest {
    @Test
    fun rejectsRelativeAndEmptyPathsBeforeSendingUsbPackets() {
        val output = ByteArrayOutputStream()
        val client = AfcClient(ByteArrayInputStream(ByteArray(0)), output)

        assertThrows(IllegalArgumentException::class.java) {
            client.uploadFile("PublicStaging/app.ipa", ByteArrayInputStream(byteArrayOf(1)), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.uploadFile("", ByteArrayInputStream(byteArrayOf(1)), 1)
        }
        assertEquals(0, output.size())
    }

    @Test
    fun rejectsTraversalAndNulPathsBeforeSendingUsbPackets() {
        val output = ByteArrayOutputStream()
        val client = AfcClient(ByteArrayInputStream(ByteArray(0)), output)

        assertThrows(IllegalArgumentException::class.java) {
            client.uploadFile("/PublicStaging/../private/app.ipa", ByteArrayInputStream(byteArrayOf(1)), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.uploadFile("/PublicStaging/./app.ipa", ByteArrayInputStream(byteArrayOf(1)), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.uploadFile("/PublicStaging/app.ipa\u0000", ByteArrayInputStream(byteArrayOf(1)), 1)
        }
        assertEquals(0, output.size())
    }

    @Test
    fun rejectsNegativeExpectedSizeBeforeOpeningRemoteFile() {
        val output = ByteArrayOutputStream()
        val client = AfcClient(ByteArrayInputStream(ByteArray(0)), output)

        assertThrows(IllegalArgumentException::class.java) {
            client.uploadFile("/PublicStaging/app.ipa", ByteArrayInputStream(ByteArray(0)), -1)
        }
        assertEquals(0, output.size())
    }
}
