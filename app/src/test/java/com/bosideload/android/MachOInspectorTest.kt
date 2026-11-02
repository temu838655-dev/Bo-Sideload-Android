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
    fun detectsStructurallyValidCodeDirectory() {
        val result = MachOInspector.inspect(thinMachO(includeSignature = true))
        assertTrue(result.isMachO)
        assertTrue(result.hasCodeSignatureCommand)
        assertTrue(result.hasCodeDirectory)
    }

    @Test
    fun rejectsCodePageHashMismatch() {
        val bytes = thinMachO(includeSignature = true)
        bytes[8] = (bytes[8].toInt() xor 0x01).toByte()
        val result = MachOInspector.inspect(bytes)
        assertTrue(result.isMachO)
        assertTrue(result.hasCodeSignatureCommand)
        assertFalse(result.hasCodeDirectory)
    }

    @Test
    fun rejectsCodeDirectoryOutsideSuperBlob() {
        val bytes = thinMachO(includeSignature = true)
        val signatureStart = 48
        putBe(bytes, signatureStart + 16, 0x7fffffff)
        val result = MachOInspector.inspect(bytes)
        assertTrue(result.isMachO)
        assertTrue(result.hasCodeSignatureCommand)
        assertFalse(result.hasCodeDirectory)
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

    @Test
    fun detectsFat64MachOWithSignedSlice() {
        val slice = thinMachO(includeSignature = true)
        val offset = 40
        val bytes = ByteArray(offset + slice.size)
        putBe(bytes, 0, 0xCAFEBABF.toInt())
        putBe(bytes, 4, 1)
        putBe(bytes, 8, 0x0100000C)
        putBe(bytes, 12, 0)
        putBe64(bytes, 16, offset.toLong())
        putBe64(bytes, 24, slice.size.toLong())
        putBe(bytes, 32, 0)
        putBe(bytes, 36, 0)
        slice.copyInto(bytes, offset)

        val result = MachOInspector.inspect(bytes)
        assertTrue(result.isMachO)
        assertTrue(result.hasCodeSignatureCommand)
    }

    private fun thinMachO(includeSignature: Boolean): ByteArray {
        val headerSize = 32
        val commandSize = if (includeSignature) 16 else 0
        val signatureSize = if (includeSignature) 97 else 0
        val bytes = ByteArray(headerSize + commandSize + signatureSize)
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
            putBe(bytes, 44, signatureSize)
            val blob = headerSize + commandSize
            // Embedded-signature SuperBlob with one structurally valid CodeDirectory.
            putBe(bytes, blob, 0xfade0cc0.toInt())
            putBe(bytes, blob + 4, signatureSize)
            putBe(bytes, blob + 8, 1)
            putBe(bytes, blob + 12, 0) // CSSLOT_CODEDIRECTORY
            putBe(bytes, blob + 16, 20) // CodeDirectory starts after SuperBlob index.
            val cd = blob + 20
            putBe(bytes, cd, 0xfade0c02.toInt())
            putBe(bytes, cd + 4, 77)
            putBe(bytes, cd + 8, 0x20000) // version 2.0: 44-byte fixed header
            putBe(bytes, cd + 12, 0)
            putBe(bytes, cd + 16, 45) // hashOffset
            putBe(bytes, cd + 20, 44) // identifier offset
            putBe(bytes, cd + 24, 0) // special slots
            putBe(bytes, cd + 28, 1) // code slots
            putBe(bytes, cd + 32, 32) // codeLimit
            bytes[cd + 36] = 32
            bytes[cd + 37] = 2 // SHA-256
            bytes[cd + 38] = 0
            bytes[cd + 39] = 12 // 4 KB pages
            bytes[cd + 44] = 0 // empty identifier terminator
            val pageHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes.copyOfRange(0, 32))
            pageHash.copyInto(bytes, cd + 45)
        }
        return bytes
    }

    private fun putBe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun putBe64(bytes: ByteArray, offset: Int, value: Long) {
        putBe(bytes, offset, (value ushr 32).toInt())
        putBe(bytes, offset + 4, value.toInt())
    }

    private fun putLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }
}
