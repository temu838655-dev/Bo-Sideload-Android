package com.bosideload.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NestedCodeBundleVerifierTest {
    @Test
    fun acceptsIpaWithoutNestedCodeBundles() {
        val ipa = createIpa(emptyMap())
        try {
            val result = NestedCodeBundleVerifier.verify(ipa)
            assertTrue(result.valid)
            assertEquals(0, result.checkedBundles)
        } finally {
            ipa.delete()
        }
    }

    @Test
    fun rejectsExtensionMissingItsOwnCodeResources() {
        val prefix = "Payload/Demo.app/PlugIns/Share.appex/"
        val entries = mapOf(
            prefix + "Info.plist" to infoPlist("com.example.demo.share", "Share"),
            prefix + "embedded.mobileprovision" to byteArrayOf(1, 2, 3),
            prefix + "Share" to byteArrayOf(0, 0, 0, 0)
        )
        val ipa = createIpa(entries)
        try {
            val result = NestedCodeBundleVerifier.verify(ipa)
            assertFalse(result.valid)
            assertTrue(result.message.contains("com.example.demo.share"))
            assertTrue(result.message.contains("CodeResources"))
        } finally {
            ipa.delete()
        }
    }

    @Test
    fun rejectsNestedBundleWhenItsInfoPlistIsMissing() {
        val prefix = "Payload/Demo.app/PlugIns/Share.appex/"
        val ipa = createIpa(mapOf(prefix + "Share" to byteArrayOf(0, 0, 0, 0)))
        try {
            val result = NestedCodeBundleVerifier.verify(ipa)
            assertFalse(result.valid)
            assertTrue(result.message.contains("Info.plist"))
        } finally {
            ipa.delete()
        }
    }

    @Test
    fun inspectsWatchAppBundleExecutableInsteadOfIgnoringIt() {
        val prefix = "Payload/Demo.app/Watch/DemoWatch.app/"
        val entries = mapOf(
            prefix + "Info.plist" to infoPlist("com.example.demo.watch", "DemoWatch"),
            prefix + "_CodeSignature/CodeResources" to byteArrayOf(1, 2, 3),
            prefix + "embedded.mobileprovision" to byteArrayOf(1, 2, 3),
            prefix + "DemoWatch" to byteArrayOf(0, 0, 0, 0)
        )
        val ipa = createIpa(entries)
        try {
            val result = NestedCodeBundleVerifier.verify(ipa)
            assertFalse(result.valid)
            assertTrue(result.message.contains("com.example.demo.watch"))
            assertTrue(result.message.contains("Mach-O"))
        } finally {
            ipa.delete()
        }
    }

    private fun createIpa(entries: Map<String, ByteArray>): File {
        val file = File.createTempFile("nested-bundle-", ".ipa")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun infoPlist(bundleId: String, executable: String): ByteArray =
        """<?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict>
            <key>CFBundleIdentifier</key><string>$bundleId</string>
            <key>CFBundleExecutable</key><string>$executable</string>
            </dict></plist>""".trimIndent().toByteArray(Charsets.UTF_8)
}
