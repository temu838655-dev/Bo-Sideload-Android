package com.bosideload.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleSigningCompatibilityTest {
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
