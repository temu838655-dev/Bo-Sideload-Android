package com.bosideload.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IpaPlistReaderTest {
    @Test
    fun readsCommonXmlPlistMetadata() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
              <dict>
                <key>CFBundleDisplayName</key><string>Demo App</string>
                <key>CFBundleIdentifier</key><string>com.example.demo</string>
                <key>CFBundleShortVersionString</key><string>2.4.1</string>
                <key>CFBundleVersion</key><string>42</string>
              </dict>
            </plist>
        """.trimIndent().toByteArray()

        val result = IpaPlistReader.read(xml)
        assertEquals("Demo App", result?.appName)
        assertEquals("com.example.demo", result?.bundleId)
        assertEquals("2.4.1", result?.shortVersion)
        assertEquals("42", result?.buildVersion)
    }

    @Test
    fun fallsBackToBundleName() {
        val xml = """
            <plist><dict>
              <key>CFBundleName</key><string>Fallback Name</string>
            </dict></plist>
        """.trimIndent().toByteArray()

        assertEquals("Fallback Name", IpaPlistReader.read(xml)?.appName)
    }

    @Test
    fun rejectsMalformedOrNonXmlPlist() {
        assertNull(IpaPlistReader.read("not plist".toByteArray()))
    }
}
