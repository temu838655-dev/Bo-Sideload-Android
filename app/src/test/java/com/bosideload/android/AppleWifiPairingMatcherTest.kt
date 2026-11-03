package com.bosideload.android

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleWifiPairingMatcherTest {
    @Test
    fun derivesAppleMobdev2AuthTagFromHostIdAndIdentifier() {
        val actual = AppleWifiPairingMatcher.deriveAuthTag(
            "00000000-0000-0000-0000-000000000001",
            "2BE6E510-0325-4365-923E-B14C6F57DB3A"
        )
        assertEquals("DSc62y1VXy0=", Base64.getEncoder().encodeToString(actual))
    }

    @Test
    fun matchesOnlyThePairRecordWhoseAuthTagIsAdvertised() {
        val record = PairRecord(
            deviceId = "device-udid-1",
            hostId = "00000000-0000-0000-0000-000000000001",
            systemBuid = "system-buid",
            hostCertificate = byteArrayOf(1),
            hostKeyMaterial = byteArrayOf(2),
            deviceCertificate = byteArrayOf(3),
            rootCertificate = byteArrayOf(4),
            rootKeyMaterial = byteArrayOf(5)
        )
        val match = AppleWifiPairingMatcher.findMatch(
            mapOf(
                "identifier" to "2BE6E510-0325-4365-923E-B14C6F57DB3A",
                "authTag" to "DSc62y1VXy0="
            ),
            listOf(record)
        )
        assertEquals("device-udid-1", match?.deviceId)
    }

    @Test
    fun doesNotMatchUnknownOrMalformedAdvertisements() {
        val record = PairRecord(
            deviceId = "device-udid-1",
            hostId = "00000000-0000-0000-0000-000000000001",
            systemBuid = "system-buid",
            hostCertificate = byteArrayOf(1),
            hostKeyMaterial = byteArrayOf(2),
            deviceCertificate = byteArrayOf(3),
            rootCertificate = byteArrayOf(4),
            rootKeyMaterial = byteArrayOf(5)
        )
        assertNull(
            AppleWifiPairingMatcher.findMatch(
                mapOf("identifier" to "different-id", "authTag" to "DSc62y1VXy0="),
                listOf(record)
            )
        )
        assertNull(
            AppleWifiPairingMatcher.findMatch(
                mapOf("identifier" to "identifier-only"),
                listOf(record)
            )
        )
    }
}
