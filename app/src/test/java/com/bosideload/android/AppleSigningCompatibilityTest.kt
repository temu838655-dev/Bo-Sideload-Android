package com.bosideload.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleSigningCompatibilityTest {
    @Test
    fun deviceEligibilityRequiresMatchingUdidForDevelopmentProfiles() {
        val devices = listOf("00008030-001C195E0E91802E", "AABBCCDD")
        assertTrue(AppleSigningCompatibility.isDeviceProvisioned(
            devices, false, "00008030-001C195E0E91802E"
        ) == true)
        assertTrue(AppleSigningCompatibility.isDeviceProvisioned(
            devices, false, "00008030-001C195E0E91802e"
        ) == true)
        assertTrue(AppleSigningCompatibility.isDeviceProvisioned(
            devices, false, "NOT-REGISTERED"
        ) == false)
    }

    @Test
    fun allDevicesProfilesDoNotRequireUdid() {
        assertTrue(AppleSigningCompatibility.isDeviceProvisioned(
            emptyList(), true, null
        ) == true)
    }

    @Test
    fun missingDeviceOrProfileListRemainsUnknown() {
        assertTrue(AppleSigningCompatibility.isDeviceProvisioned(
            listOf("DEVICE-1"), false, null
        ) == null)
        assertTrue(AppleSigningCompatibility.isDeviceProvisioned(
            emptyList(), false, "DEVICE-1"
        ) == null)
    }

    @Test
    fun recognizesOnlyTheMainApplicationInfoPlistPath() {
        assertTrue(AppleSigningCompatibility.isAppInfoPlistPath("Payload/Example.app/Info.plist"))
        assertFalse(AppleSigningCompatibility.isAppInfoPlistPath("Payload/Example.app/PlugIns/Widget.appex/Info.plist"))
        assertFalse(AppleSigningCompatibility.isAppInfoPlistPath("Payload/Example.app/InfoXplist"))
        assertFalse(AppleSigningCompatibility.isAppInfoPlistPath("../Payload/Example.app/Info.plist"))
    }

    @Test
    fun profileApplicationIdentifierMustUseKnownTeamPrefix() {
        assertTrue(AppleSigningCompatibility.hasKnownProfilePrefix(
            "ABCDE12345.com.example.app",
            listOf("ABCDE12345"),
            emptyList()
        ))
        assertTrue(AppleSigningCompatibility.hasKnownProfilePrefix(
            "OLDPREFIX.com.example.app",
            emptyList(),
            listOf("OLDPREFIX")
        ))
        assertFalse(AppleSigningCompatibility.hasKnownProfilePrefix(
            "WRONGTEAM.com.example.app",
            listOf("ABCDE12345"),
            listOf("OLDPREFIX")
        ))
    }

    @Test
    fun certificateMustBeInsideItsValidityWindow() {
        val now = java.util.Date(1_000_000L)
        assertTrue(AppleSigningCompatibility.isCertificateValidAt(
            java.util.Date(900_000L), java.util.Date(1_100_000L), now
        ))
        assertFalse(AppleSigningCompatibility.isCertificateValidAt(
            java.util.Date(1_100_000L), java.util.Date(1_200_000L), now
        ))
        assertFalse(AppleSigningCompatibility.isCertificateValidAt(
            java.util.Date(800_000L), java.util.Date(900_000L), now
        ))
    }

    @Test
    fun matchesExactBundleIdentifier() {
        assertTrue(AppleSigningCompatibility.matchesApplicationIdentifier(
            "ABCDE12345.com.example.app", "com.example.app"
        ))
    }

    @Test
    fun matchesWildcardBundleIdentifierOnlyForDescendant() {
        assertTrue(AppleSigningCompatibility.matchesApplicationIdentifier(
            "ABCDE12345.com.example.*", "com.example.app"
        ))
        assertFalse(AppleSigningCompatibility.matchesApplicationIdentifier(
            "ABCDE12345.com.example.*", "com.example"
        ))
        assertFalse(AppleSigningCompatibility.matchesApplicationIdentifier(
            "ABCDE12345.com.example.*", "com.examples.app"
        ))
    }

    @Test
    fun rejectsMalformedOrEmptyApplicationIdentifier() {
        assertFalse(AppleSigningCompatibility.matchesApplicationIdentifier("", "com.example.app"))
        assertFalse(AppleSigningCompatibility.matchesApplicationIdentifier("TEAM", "com.example.app"))
        assertFalse(AppleSigningCompatibility.matchesApplicationIdentifier("TEAM.", "com.example.app"))
    }
}
