package com.bosideload.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MachOInspectorTest {
    @Test
    fun detectsThinMachOWithCodeSignatureCommand() {
        val bytes = thinMachO(includeSignature = true)
        val result = MachOInspector.inspect(bytes)
        assertTrue(result.isMachO)
        assertTrue(result.hasCodeSignatureCommand)
    }

    @Test
    fun detectsThinMachOWithoutCodeSignatureCommand() {
        val result = MachOInspector.inspect(thinMachO(includeSignature = false))
        assertTrue(result.isMachO)
        assertFalse(result.hasCodeSignatureCommand)
    }

    @Test
    fun rejectsTruncatedLoadCommand() {
        val bytes = thinMachO(includeSignature = true).copyOf(40)
        val result = MachOInspector.inspect(bytes)
        assertFalse(result.isMachO)
    }

    private fun thinMachO(includeSignature: Boolean): ByteArray {
        val headerSize = 32
        val commandSize = if (includeSignature) 16 else 0
        val bytes = ByteArray(headerSize + commandSize + 32)
        putBe(bytes, 0, 0xFEEDFACF)
        putLe(bytes, 4, 0x0100000C)
        putLe(bytes, 8, 0)
        putLe(bytes, 12, 2)
        putLe(bytes, 16, if (includeSignature) 1 else 0)
        putLe(bytes, 20, commandSize)
        putLe(bytes, 24, 0)
        putLe(bytes, 28, 0)
        if (includeSignature) {
            putLe(bytes, 32, 0x1d)
            putLe(bytes, 36, 16)
            putLe(bytes, 40, headerSize + commandSize)
            putLe(bytes, 44, 32)
        }
        return bytes
    }

    private fun putBe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun putLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }
}
