package com.bosideload.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class LockdownPlistCodecTest {
    @Test
    fun roundTripsLockdownDictionaryValues() {
        val input = linkedMapOf<String, Any?>(
            "Label" to "Bo Sideload",
            "Request" to "GetValue",
            "Enabled" to true,
            "Count" to 42L,
            "Ratio" to 1.5,
            "Payload" to byteArrayOf(1, 2, 3),
            "Nested" to listOf("one", false, 9L)
        )
        val output = LockdownPlistCodec.decode(LockdownPlistCodec.encode(input))
        assertEquals("Bo Sideload", output["Label"])
        assertEquals("GetValue", output["Request"])
        assertEquals(true, output["Enabled"])
        assertEquals(42L, output["Count"])
        assertEquals(1.5, output["Ratio"])
        assertArrayEquals(byteArrayOf(1, 2, 3), output["Payload"] as ByteArray)
        assertEquals(listOf("one", false, 9L), output["Nested"])
    }

    @Test
    fun decodesAppleStyleXmlDictionary() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0"><dict>
            <key>Request</key><string>GetValue</string>
            <key>PairingDialogResponse</key><true/>
            </dict></plist>""".trimIndent().toByteArray(StandardCharsets.UTF_8)
        val decoded = LockdownPlistCodec.decode(xml)
        assertEquals("GetValue", decoded["Request"])
        assertEquals(true, decoded["PairingDialogResponse"])
    }

    @Test
    fun escapesXmlSpecialCharacters() {
        val value = "A&B <device> \"trusted\""
        val decoded = LockdownPlistCodec.decode(LockdownPlistCodec.encode(mapOf("Name" to value)))
        assertEquals(value, decoded["Name"])
    }

    @Test
    fun rejectsNonDictionaryRootAndMalformedDictionary() {
        assertThrows(IllegalArgumentException::class.java) {
            LockdownPlistCodec.decode("<plist><string>not a dict</string></plist>".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            LockdownPlistCodec.decode("<plist><dict><key>missing value</key></dict></plist>".toByteArray())
        }
    }

    @Test
    fun acceptsAppleDoctypeWithoutResolvingExternalEntities() {
        val xml = """<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
            "http://www.apple.com/DTDs/PropertyList-1.0.dtd"
            [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <plist version="1.0"><dict><key>Value</key><string>&x;</string></dict></plist>"""
        val decoded = LockdownPlistCodec.decode(xml.toByteArray())
        assertEquals("", decoded["Value"])
    }
    @Test
    fun rejectsOversizedPayloadsAndUnsupportedValues() {
        assertThrows(IllegalArgumentException::class.java) {
            LockdownPlistCodec.decode("<plist><dict/></plist>".toByteArray(), maxBytes = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LockdownPlistCodec.encode(mapOf("Unsupported" to Any()))
        }
    }

    @Test
    fun rejectsDuplicateDictionaryKeys() {
        val xml = """<plist><dict><key>A</key><string>one</string>
            <key>A</key><string>two</string></dict></plist>"""
        assertThrows(IllegalArgumentException::class.java) {
            LockdownPlistCodec.decode(xml.toByteArray())
        }
    }
}
