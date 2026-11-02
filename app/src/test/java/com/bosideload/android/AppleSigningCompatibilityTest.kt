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
