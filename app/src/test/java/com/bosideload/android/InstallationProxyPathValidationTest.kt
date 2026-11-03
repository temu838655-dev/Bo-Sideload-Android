package com.bosideload.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class InstallationProxyPathValidationTest {
    @Test
    fun rejectsRelativeAndEmptyPackagePathsBeforeWriting() {
        val output = ByteArrayOutputStream()
        val client = InstallationProxyClient(ByteArrayInputStream(ByteArray(0)), output)

        assertThrows(IllegalArgumentException::class.java) { client.install("") }
        assertThrows(IllegalArgumentException::class.java) { client.install("PublicStaging/app.ipa") }
        assertEquals(0, output.size())
    }

    @Test
    fun rejectsTraversalAndNulSegmentsBeforeWriting() {
        val output = ByteArrayOutputStream()
        val client = InstallationProxyClient(ByteArrayInputStream(ByteArray(0)), output)

        assertThrows(IllegalArgumentException::class.java) {
            client.install("/PublicStaging/../private/app.ipa")
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.install("/PublicStaging/./app.ipa")
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.install("/PublicStaging/app.ipa\u0000")
        }
        assertEquals(0, output.size())
    }

    @Test
    fun invalidPathDoesNotLeaveOperationLockHeld() {
        val output = ByteArrayOutputStream()
        val client = InstallationProxyClient(ByteArrayInputStream(ByteArray(0)), output)

        assertThrows(IllegalArgumentException::class.java) {
            client.install("/PublicStaging/../app.ipa")
        }
        val error = assertThrows(IOException::class.java) {
            client.install("/PublicStaging/valid.ipa")
        }
        // EOF is expected from the empty fake device stream. An operation-lock
        // error here would mean validation incorrectly left the client busy.
        assertEquals(true, error.message!!.contains("incomplete property-list service packet"))
        assertEquals(1, output.size().let { if (it > 0) 1 else 0 })
    }
}
