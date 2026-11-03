package com.bosideload.android

import org.junit.Assert.*
import org.junit.Test

class PairRecordCodecTest {
    private fun sample() = PairRecord(
        "device-123", "host-123", "buid-123",
        byteArrayOf(1, 2), byteArrayOf(3, 4),
        byteArrayOf(5, 6), byteArrayOf(7, 8), byteArrayOf(8, 9), byteArrayOf(9)
    )

    @Test
    fun roundTripsFields() {
        val a = sample()
        val b = PairRecordCodec.decode(PairRecordCodec.encode(a))
        assertEquals(a.deviceId, b.deviceId)
        assertEquals(a.hostId, b.hostId)
        assertEquals(a.systemBuid, b.systemBuid)
        assertArrayEquals(a.hostCertificate, b.hostCertificate)
        assertArrayEquals(a.hostKeyMaterial, b.hostKeyMaterial)
        assertArrayEquals(a.deviceCertificate, b.deviceCertificate)
        assertArrayEquals(a.rootCertificate, b.rootCertificate)
        assertArrayEquals(a.rootKeyMaterial, b.rootKeyMaterial)
        assertArrayEquals(a.escrowBag, b.escrowBag)
    }

    @Test
    fun acceptsMissingOptionalEscrowBag() {
        val b = PairRecordCodec.decode(PairRecordCodec.encode(sample().copy(escrowBag = null)))
        assertNull(b.escrowBag)
    }

    @Test
    fun rejectsMissingRequiredBytes() {
        assertThrows(IllegalArgumentException::class.java) {
            PairRecordCodec.encode(sample().copy(hostCertificate = byteArrayOf()))
        }
    }

    @Test
    fun rejectsTruncatedAndTrailingData() {
        val bytes = PairRecordCodec.encode(sample())
        assertThrows(IllegalArgumentException::class.java) {
            PairRecordCodec.decode(bytes.copyOf(bytes.size - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairRecordCodec.decode(bytes + byteArrayOf(0))
        }
    }
}
