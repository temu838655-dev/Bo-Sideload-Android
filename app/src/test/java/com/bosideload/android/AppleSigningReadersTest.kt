package com.bosideload.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleSigningReadersTest {
    @Test
    fun readsProvisioningProfilePlistMetadataAndDeveloperCertificateHash() {
        val plist = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict>
              <key>Name</key><string>Bo Test Profile</string>
              <key>UUID</key><string>TEST-UUID</string>
              <key>TeamIdentifier</key><array><string>ABCDE12345</string></array>
              <key>ApplicationIdentifierPrefix</key><array><string>ABCDE12345</string></array>
              <key>Entitlements</key><dict>
                <key>application-identifier</key><string>ABCDE12345.com.example.bo</string>
              </dict>
              <key>Platform</key><array><string>iOS</string></array>
              <key>DeveloperCertificates</key><array><data>AQID</data></array>
              <key>ProvisionedDevices</key><array><string>DEVICE-UDID</string></array>
              <key>ProvisionsAllDevices</key><false/>
            </dict></plist>
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val info = AppleProvisioningProfileReader.read(plist)

        assertEquals("Bo Test Profile", info.name)
        assertEquals("TEST-UUID", info.uuid)
        assertEquals(listOf("ABCDE12345"), info.teamIdentifiers)
        assertEquals("com.example.bo", info.bundleIdentifier)
        assertEquals("ABCDE12345.com.example.bo", info.teamBundleIdentifier)
        assertEquals(listOf("iOS"), info.platforms)
        assertEquals(1, info.provisionedDevices.size)
        assertEquals(1, info.developerCertificateCount)
        assertEquals(
            "03:90:58:C6:F2:C0:CB:49:2C:53:3B:0A:4D:14:EF:77:CC:0F:78:AB:CC:CE:D5:28:7D:84:A1:A2:01:1C:FB:81",
            info.developerCertificateSha256.single()
        )
    }

    @Test
    fun rejectsEmptyProvisioningProfile() {
        val error = runCatching { AppleProvisioningProfileReader.read(byteArrayOf()) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun rejectsMalformedProvisioningProfile() {
        val error = runCatching {
            AppleProvisioningProfileReader.read("not-a-profile".toByteArray())
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun rejectsEmptyP12() {
        val error = runCatching { AppleP12Reader.read(byteArrayOf(), charArrayOf()) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun rejectsMalformedP12OrWrongPassword() {
        val error = runCatching {
            AppleP12Reader.read("not-a-pkcs12-file".toByteArray(), "wrong".toCharArray())
        }.exceptionOrNull()
        assertTrue(error != null)
    }

    @Test
    fun rejectsOversizedP12BeforeParsing() {
        val error = runCatching {
            AppleP12Reader.read(ByteArray(10 * 1024 * 1024 + 1), charArrayOf())
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
