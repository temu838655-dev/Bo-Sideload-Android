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
    fun recognizesNestedExtensionAndWatchBundleInfoPlistsForPreflightWarnings() {
        assertTrue(AppleSigningCompatibility.isNestedCodeBundleInfoPlistPath(
            "Payload/Example.app/PlugIns/Share.appex/Info.plist"
        ))
        assertTrue(AppleSigningCompatibility.isNestedCodeBundleInfoPlistPath(
            "Payload/Example.app/Watch/ExampleWatch.app/Info.plist"
        ))
        assertFalse(AppleSigningCompatibility.isNestedCodeBundleInfoPlistPath(
            "Payload/Example.app/Info.plist"
        ))
        assertFalse(AppleSigningCompatibility.isNestedCodeBundleInfoPlistPath(
            "Payload/Example.app/PlugIns/../Info.plist"
        ))
        assertFalse(AppleSigningCompatibility.isNestedCodeBundleInfoPlistPath(
            "Payload/Example.app/Resources/Info.plist"
        ))
    }

    @Test
    fun readsBundleIdentifiersFromNestedExtensionAndWatchPlists() {
        val ipa = java.io.File.createTempFile("nested-bundles", ".ipa")
        try {
            java.util.zip.ZipOutputStream(ipa.outputStream()).use { zip ->
                val plist = """<?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                    <plist version="1.0"><dict>
                    <key>CFBundleIdentifier</key><string>com.example.share</string>
                    <key>CFBundleExecutable</key><string>ShareExtension</string>
                    </dict></plist>""".trimIndent().toByteArray()
                zip.putNextEntry(java.util.zip.ZipEntry(
                    "Payload/Example.app/PlugIns/Share.appex/Info.plist"
                ))
                zip.write(plist)
                zip.closeEntry()
            }
            val nested = AppleSigningCompatibility.inspectNestedCodeBundles(ipa)
            assertTrue(nested.size == 1)
            assertTrue(nested.single().bundleId == "com.example.share")
            assertTrue(nested.single().executable == "ShareExtension")
        } finally {
            ipa.delete()
        }
    }

    @Test
    fun blocksSigningWhenNestedBundleProfilesAreNotSupported() {
        val base = AppleSigningCompatibility.Check(
            compatible = true,
            errors = emptyList(),
            warnings = listOf("Thông tin profile cơ bản đã đạt.")
        )
        val nested = listOf(
            AppleSigningCompatibility.NestedBundleInfo(
                path = "Payload/Example.app/PlugIns/Share.appex/Info.plist",
                bundleId = "com.example.share",
                executable = "ShareExtension"
            )
        )

        val result = AppleSigningCompatibility.rejectUnsupportedNestedBundles(base, nested)

        assertFalse(result.compatible)
        assertTrue(result.errors.any { it.contains("com.example.share") })
        assertTrue(result.errors.any { it.contains("bị chặn") })
        assertTrue(result.warnings.contains("Thông tin profile cơ bản đã đạt."))
    }

    @Test
    fun doesNotChangeCompatibilityWhenThereAreNoNestedBundles() {
        val base = AppleSigningCompatibility.Check(
            compatible = true,
            errors = emptyList(),
            warnings = emptyList()
        )

        val result = AppleSigningCompatibility.rejectUnsupportedNestedBundles(base, emptyList())

        assertTrue(result.compatible)
        assertTrue(result.errors.isEmpty())
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
    fun rejectsExpiredProfileAndMismatchedCertificateFingerprint() {
        val now = java.util.Date(10_000L)
        val profile = AppleProvisioningProfileReader.ProfileInfo(
            name = "Test",
            uuid = "PROFILE-ID",
            teamIdentifiers = listOf("ABCDE12345"),
            applicationIdentifierPrefixes = listOf("ABCDE12345"),
            bundleIdentifier = "com.example.app",
            teamBundleIdentifier = "ABCDE12345.com.example.app",
            platforms = listOf("iOS"),
            provisionedDevices = listOf("DEVICE-1"),
            provisionsAllDevices = false,
            creationDate = java.util.Date(0L),
            expirationDate = java.util.Date(9_000L),
            developerCertificateCount = 1,
            developerCertificateSha256 = listOf("AA:BB")
        )
        val certificate = AppleP12Reader.EntryInfo(
            alias = "test",
            hasPrivateKey = true,
            subject = "CN=Test",
            issuer = "CN=Apple",
            notBefore = java.util.Date(0L),
            notAfter = java.util.Date(20_000L),
            sha256Fingerprint = "CC:DD"
        )
        val result = AppleSigningCompatibility.check(
            ipa = IpaPlistReader.Metadata("Example", "com.example.app", "1.0", "1", "Example"),
            profile = profile,
            certificates = listOf(certificate),
            now = now,
            targetUdid = "DEVICE-1"
        )
        assertFalse(result.compatible)
        assertTrue(result.errors.any { it.contains("hết hạn") })
        assertTrue(result.errors.any { it.contains("fingerprint") })
    }

    @Test
    fun rejectsMalformedOrEmptyApplicationIdentifier() {
        assertFalse(AppleSigningCompatibility.matchesApplicationIdentifier("", "com.example.app"))
        assertFalse(AppleSigningCompatibility.matchesApplicationIdentifier("TEAM", "com.example.app"))
        assertFalse(AppleSigningCompatibility.matchesApplicationIdentifier("TEAM.", "com.example.app"))
    }
}
