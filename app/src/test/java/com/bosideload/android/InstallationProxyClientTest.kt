package com.bosideload.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InstallationProxyClientTest {
    @Test
    fun propertyListServiceUsesBigEndianLengthPrefix() {
        val payload = "<?xml version=\"1.0\"?><plist><dict/></plist>".toByteArray()
        val encoded = PropertyListServiceCodec.encode(payload)
        assertEquals(payload.size, ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN).int)
        assertArrayEquals(payload, PropertyListServiceCodec.read(encoded.inputStream()))
    }

    @Test
    fun rejectsOversizedAndTruncatedPropertyListPackets() {
        assertThrows(IllegalArgumentException::class.java) {
            PropertyListServiceCodec.encode(ByteArray(10), maxPayloadBytes = 5)
        }
        val badLength = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(100).array()
        assertThrows(IOException::class.java) {
            PropertyListServiceCodec.read(ByteArrayInputStream(badLength), maxPayloadBytes = 50)
        }
        assertThrows(IOException::class.java) {
            PropertyListServiceCodec.read(ByteArrayInputStream(byteArrayOf(0, 0)))
        }
    }

    @Test
    fun installSendsCommandAndConsumesCompleteStatus() {
        val response = LockdownPlistCodec.encode(
            mapOf("Status" to "Complete", "PercentComplete" to 100L)
        )
        val incoming = PropertyListServiceCodec.encode(response)
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<InstallationProgress>()
        val client = InstallationProxyClient(ByteArrayInputStream(incoming), output)

        client.install("/PublicStaging/test.ipa", onProgress = progress::add)

        val sent = output.toByteArray()
        val commandBytes = PropertyListServiceCodec.read(sent.inputStream())
        val command = LockdownPlistCodec.decode(commandBytes)
        assertEquals("Install", command["Command"])
        assertEquals("/PublicStaging/test.ipa", command["PackagePath"])
        assertEquals(1, progress.size)
        assertEquals("Complete", progress.single().status)
        assertEquals(100, progress.single().percentComplete)
    }

    @Test
    fun installReportsEveryProgressUpdateAndSendsClientOptions() {
        val responses = listOf(
            mapOf("Status" to "CreatingStagingDirectory", "PercentComplete" to 0L),
            mapOf("Status" to "Installing", "PercentComplete" to 45L),
            mapOf("Status" to "Complete", "PercentComplete" to 100L)
        ).map { LockdownPlistCodec.encode(it) }
        val incoming = ByteArrayOutputStream().apply {
            responses.forEach { write(PropertyListServiceCodec.encode(it)) }
        }.toByteArray()
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<InstallationProgress>()
        val options = mapOf<String, Any?>("ApplicationType" to "User")
        val client = InstallationProxyClient(ByteArrayInputStream(incoming), output)

        client.install(
            remotePackagePath = "/PublicStaging/test.ipa",
            clientOptions = options,
            onProgress = progress::add
        )

        assertEquals(
            listOf("CreatingStagingDirectory", "Installing", "Complete"),
            progress.map { it.status }
        )
        assertEquals(listOf(0, 45, 100), progress.map { it.percentComplete })

        val command = LockdownPlistCodec.decode(
            PropertyListServiceCodec.read(output.toByteArray().inputStream())
        )
        assertEquals("Install", command["Command"])
        assertEquals(options, command["ClientOptions"])
    }

    @Test
    fun installFailsOnDeviceReportedError() {
        val response = LockdownPlistCodec.encode(
            mapOf("Status" to "Error", "Error" to "ApplicationVerificationFailed", "ErrorDescription" to "signature invalid")
        )
        val client = InstallationProxyClient(
            ByteArrayInputStream(PropertyListServiceCodec.encode(response)),
            ByteArrayOutputStream()
        )
        val error = assertThrows(IOException::class.java) {
            client.install("/PublicStaging/test.ipa")
        }
        assertEquals(true, error.message!!.contains("signature invalid"))
    }
    @Test
    fun installRejectsStatusResponseWithoutStatusOrError() {
        val response = LockdownPlistCodec.encode(mapOf("PercentComplete" to 50L))
        val client = InstallationProxyClient(
            ByteArrayInputStream(PropertyListServiceCodec.encode(response)),
            ByteArrayOutputStream()
        )

        val error = assertThrows(IOException::class.java) {
            client.install("/PublicStaging/test.ipa")
        }
        assertEquals(true, error.message!!.contains("without Status or Error"))
    }

    @Test
    fun installRejectsEmptyStatusResponse() {
        val response = LockdownPlistCodec.encode(emptyMap<String, Any?>())
        val client = InstallationProxyClient(
            ByteArrayInputStream(PropertyListServiceCodec.encode(response)),
            ByteArrayOutputStream()
        )

        assertThrows(IOException::class.java) {
            client.install("/PublicStaging/test.ipa")
        }
    }

}
