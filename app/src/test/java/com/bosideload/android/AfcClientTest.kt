package com.bosideload.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AfcClientTest {
    @Test
    fun uploadsFileUsingOpenWriteAndCloseRequests() {
        val handle = 42L
        val responses = ByteArrayOutputStream().apply {
            write(response(1, 0x0E, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(handle).array()))
            write(response(2, 0x01, status(0)))
            write(response(3, 0x01, status(0)))
            write(response(4, 0x01, status(0)))
        }
        val requests = ByteArrayOutputStream()
        val source = byteArrayOf(10, 20, 30, 40)

        val result = AfcClient(
            input = ByteArrayInputStream(responses.toByteArray()),
            output = requests,
            maxWriteChunkBytes = 2
        ).use { client ->
            client.uploadFile("/PublicStaging/test.ipa", ByteArrayInputStream(source), source.size.toLong())
        }

        assertEquals("/PublicStaging/test.ipa", result.remotePath)
        assertEquals(4L, result.bytesWritten)

        val wire = requests.toByteArray()
        val operations = mutableListOf<Long>()
        val bodies = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < wire.size) {
            val headerBytes = wire.copyOfRange(offset, offset + AfcPacketCodec.HEADER_SIZE)
            val header = AfcPacketCodec.decodeHeader(headerBytes)
            val end = offset + header.entireLength.toInt()
            operations += header.operation
            bodies += wire.copyOfRange(offset + AfcPacketCodec.HEADER_SIZE, end)
            offset = end
        }
        assertEquals(listOf(0x0DL, 0x10L, 0x10L, 0x14L), operations)

        val openBody = bodies.first()
        assertEquals(3L, ByteBuffer.wrap(openBody).order(ByteOrder.LITTLE_ENDIAN).long)
        assertEquals(
            "/PublicStaging/test.ipa\u0000",
            String(openBody, 8, openBody.size - 8, Charsets.UTF_8)
        )
        val firstWrite = bodies[1]
        assertEquals(handle, ByteBuffer.wrap(firstWrite).order(ByteOrder.LITTLE_ENDIAN).long)
        assertEquals(10.toByte(), firstWrite[8])
        assertEquals(20.toByte(), firstWrite[9])
    }

    @Test
    fun closesRemoteHandleWhenSourceEndsEarly() {
        val responses = ByteArrayOutputStream().apply {
            write(response(1, 0x0E, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(9L).array()))
            write(response(2, 0x01, status(0)))
            write(response(3, 0x01, status(0)))
        }
        val requests = ByteArrayOutputStream()
        val error = assertThrows(IOException::class.java) {
            AfcClient(
                input = ByteArrayInputStream(responses.toByteArray()),
                output = requests,
                maxWriteChunkBytes = 8
            ).use { client ->
                client.uploadFile("/PublicStaging/short.ipa", ByteArrayInputStream(byteArrayOf(1)), 2)
            }
        }
        assertEquals("IPA source ended before expected size", error.message)

        val wire = requests.toByteArray()
        val operations = mutableListOf<Long>()
        var offset = 0
        while (offset < wire.size) {
            val header = AfcPacketCodec.decodeHeader(wire.copyOfRange(offset, offset + 40))
            operations += header.operation
            offset += header.entireLength.toInt()
        }
        assertEquals(listOf(0x0DL, 0x10L, 0x14L), operations)
    }


    @Test
    fun rejectsSourceLongerThanDeclaredSizeAndClosesRemoteHandle() {
        val responses = ByteArrayOutputStream().apply {
            write(response(1, 0x0E, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(17L).array()))
            write(response(2, 0x01, status(0)))
            write(response(3, 0x01, status(0)))
        }
        val requests = ByteArrayOutputStream()

        val error = assertThrows(IOException::class.java) {
            AfcClient(
                input = ByteArrayInputStream(responses.toByteArray()),
                output = requests
            ).use { client ->
                client.uploadFile(
                    "/PublicStaging/long.ipa",
                    ByteArrayInputStream(byteArrayOf(1, 2)),
                    expectedSize = 1
                )
            }
        }

        assertEquals("IPA source contains more bytes than expected size", error.message)
        assertEquals(listOf(0x0DL, 0x10L, 0x14L), requestOperations(requests.toByteArray()))
    }

    @Test
    fun reportsDeviceWriteFailureAndStillClosesRemoteHandle() {
        val responses = ByteArrayOutputStream().apply {
            write(response(1, 0x0E, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(23L).array()))
            write(response(2, 0x01, status(7)))
            write(response(3, 0x01, status(0)))
        }
        val requests = ByteArrayOutputStream()

        val error = assertThrows(IOException::class.java) {
            AfcClient(
                input = ByteArrayInputStream(responses.toByteArray()),
                output = requests
            ).use { client ->
                client.uploadFile(
                    "/PublicStaging/rejected.ipa",
                    ByteArrayInputStream(byteArrayOf(9)),
                    expectedSize = 1
                )
            }
        }

        assertEquals("AFC FileWrite failed with status 7", error.message)
        assertEquals(listOf(0x0DL, 0x10L, 0x14L), requestOperations(requests.toByteArray()))
    }

    @Test
    fun removesOnlyUuidNamedBoSideloadStagingIpa() {
        val remotePath = "/PublicStaging/BoSideload-12345678-abcd-1234-abcd-1234567890ab.ipa"
        val responses = ByteArrayInputStream(response(1, 0x01, status(0)))
        val requests = ByteArrayOutputStream()

        AfcClient(responses, requests).use { client ->
            client.removeStagedIpa(remotePath)
        }

        val wire = requests.toByteArray()
        assertEquals(listOf(0x08L), requestOperations(wire))
        val header = AfcPacketCodec.decodeHeader(wire.copyOfRange(0, AfcPacketCodec.HEADER_SIZE))
        val body = wire.copyOfRange(AfcPacketCodec.HEADER_SIZE, header.entireLength.toInt())
        assertEquals(remotePath + "\\u0000", String(body, Charsets.UTF_8))
    }

    @Test
    fun refusesToRemovePathOutsideBoSideloadStagingPattern() {
        val requests = ByteArrayOutputStream()
        val error = assertThrows(IOException::class.java) {
            AfcClient(ByteArrayInputStream(byteArrayOf()), requests).use { client ->
                client.removeStagedIpa("/PublicStaging/ImportantDocument.ipa")
            }
        }
        assertEquals("Refusing to remove a path not owned by Bo Sideload", error.message)
        assertEquals(0, requests.size())
    }

    @Test
    fun reportsDeviceFailureWhenRemovingStagedIpa() {
        val remotePath = "/PublicStaging/BoSideload-12345678-abcd-1234-abcd-1234567890ab.ipa"
        val responses = ByteArrayInputStream(response(1, 0x01, status(8)))
        val requests = ByteArrayOutputStream()

        val error = assertThrows(IOException::class.java) {
            AfcClient(responses, requests).use { client ->
                client.removeStagedIpa(remotePath)
            }
        }
        assertEquals("AFC RemovePath failed with status 8", error.message)
        assertEquals(listOf(0x08L), requestOperations(requests.toByteArray()))
    }

    private fun requestOperations(wire: ByteArray): List<Long> {
        val operations = mutableListOf<Long>()
        var offset = 0
        while (offset < wire.size) {
            val header = AfcPacketCodec.decodeHeader(
                wire.copyOfRange(offset, offset + AfcPacketCodec.HEADER_SIZE)
            )
            operations += header.operation
            offset += header.entireLength.toInt()
        }
        return operations
    }

    private fun response(packetNumber: Long, operation: Long, body: ByteArray): ByteArray =
        AfcPacketCodec.encode(packetNumber, operation, data = body)

    private fun status(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
}
