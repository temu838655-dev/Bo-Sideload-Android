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

    @Test
    fun detectsLittleEndianThinMachO() {
        val bytes = ByteArray(28)
        bytes[0] = 0xCE.toByte(); bytes[1] = 0xFA.toByte()
        bytes[2] = 0xED.toByte(); bytes[3] = 0xFE.toByte()
        putLe(bytes, 4, 7); putLe(bytes, 8, 3); putLe(bytes, 12, 2)
        putLe(bytes, 16, 0); putLe(bytes, 20, 0); putLe(bytes, 24, 0)
        val result = MachOInspector.inspect(bytes)
        assertTrue(result.isMachO)
        assertFalse(result.hasCodeSignatureCommand)
    }

    @Test
    fun detectsFatMachOWithSignedSlice() {
        val slice = thinMachO(includeSignature = true)
        val offset = 28
        val bytes = ByteArray(offset + slice.size)
        putBe(bytes, 0, 0xCAFEBABE.toInt()); putBe(bytes, 4, 1)
        putBe(bytes, 8, 0x0100000C); putBe(bytes, 12, 0)
        putBe(bytes, 16, offset); putBe(bytes, 20, slice.size); putBe(bytes, 24, 0)
        slice.copyInto(bytes, offset)
        val result = MachOInspector.inspect(bytes)
        assertTrue(result.isMachO)
        assertTrue(result.hasCodeSignatureCommand)
    }

    private fun thinMachO(includeSignature: Boolean): ByteArray {
        val headerSize = 32
        val commandSize = if (includeSignature) 16 else 0
        val bytes = ByteArray(headerSize + commandSize + 32)
        putBe(bytes, 0, -17958193)
        putBe(bytes, 4, 0x0100000C)
        putBe(bytes, 8, 0)
        putBe(bytes, 12, 2)
        putBe(bytes, 16, if (includeSignature) 1 else 0)
        putBe(bytes, 20, commandSize)
        putBe(bytes, 24, 0)
        putBe(bytes, 28, 0)
        if (includeSignature) {
            putBe(bytes, 32, 0x1d)
            putBe(bytes, 36, 16)
            putBe(bytes, 40, headerSize + commandSize)
            putBe(bytes, 44, 32)
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
