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
    fun verifiesCodeResourcesSpecialSlotHash() {
        val seal = "signed resource seal".toByteArray()
        val result = MachOInspector.inspect(
            thinMachO(includeSignature = true, resourceSealBytes = seal),
            resourceSealBytes = seal
        )
        assertTrue(result.isMachO)
        assertTrue(result.hasCodeDirectory)
    }

    @Test
    fun verifiesInfoPlistSpecialSlotHash() {
        val info = "main Info.plist".toByteArray()
        val seal = "signed resource seal".toByteArray()
        val result = MachOInspector.inspect(
            thinMachO(includeSignature = true, resourceSealBytes = seal, infoPlistBytes = info),
            resourceSealBytes = seal,
            infoPlistBytes = info
        )
        assertTrue(result.isMachO)
        assertTrue(result.hasCodeDirectory)
    }

    @Test
    fun rejectsInfoPlistSpecialSlotHashMismatch() {
        val signedInfo = "main Info.plist".toByteArray()
        val changedInfo = "modified Info.plist".toByteArray()
        val seal = "signed resource seal".toByteArray()
        val result = MachOInspector.inspect(
            thinMachO(includeSignature = true, resourceSealBytes = seal, infoPlistBytes = signedInfo),
            resourceSealBytes = seal,
            infoPlistBytes = changedInfo
        )
        assertTrue(result.isMachO)
        assertFalse(result.hasCodeDirectory)
    }

    @Test
    fun rejectsCodeResourcesSpecialSlotHashMismatch() {
        val signedSeal = "signed resource seal".toByteArray()
        val changedSeal = "modified resource seal".toByteArray()
        val result = MachOInspector.inspect(
            thinMachO(includeSignature = true, resourceSealBytes = signedSeal),
            resourceSealBytes = changedSeal
        )
        assertTrue(result.isMachO)
        assertFalse(result.hasCodeDirectory)
    }

    @Test
    fun verifiesEntitlementsSpecialSlotHash() {
        val entitlements = entitlementBlob()
        val result = MachOInspector.inspect(
            thinMachOWithEntitlements(entitlements),
        )
        assertTrue(result.isMachO)
        assertTrue(result.hasCodeDirectory)
    }

    @Test
    fun rejectsEntitlementsSpecialSlotHashMismatch() {
        val signedEntitlements = entitlementBlob()
        val bytes = thinMachOWithEntitlements(signedEntitlements)
        val blobOffset = 48 + 12 + 16 + 237
        bytes[blobOffset + 8] = (bytes[blobOffset + 8].toInt() xor 0x01).toByte()
        val result = MachOInspector.inspect(bytes)
        assertTrue(result.isMachO)
        assertFalse(result.hasCodeDirectory)
    }

    @Test
    fun rejectsMissingEntitlementsBlobWhenSpecialSlotHashIsNonZero() {
        val entitlements = entitlementBlob()
        val bytes = thinMachOWithEntitlements(entitlements)
        // Turn the embedded entitlements SuperBlob index into an unknown slot,
        // leaving the CodeDirectory's slot-5 digest untouched.
        putBe(bytes, 48 + 12, 7)
        val result = MachOInspector.inspect(bytes)
        assertTrue(result.isMachO)
        assertFalse(result.hasCodeDirectory)
    }

    @Test
    fun acceptsMissingEntitlementsBlobWhenSpecialSlotHashIsZero() {
        val bytes = thinMachOWithEntitlements(null)
        val result = MachOInspector.inspect(bytes)
        assertTrue(result.isMachO)
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
    fun rejectsCodeDirectoryOverlappingSuperBlobIndexTable() {
        val bytes = thinMachO(includeSignature = true)
        // The embedded-signature index table ends at relative offset 20.
        putBe(bytes, 48 + 16, 16)
        val result = MachOInspector.inspect(bytes)
        assertTrue(result.isMachO)
        assertFalse(result.hasCodeDirectory)
    }

    @Test
    fun rejectsOverlappingEmbeddedSignatureBlobs() {
        val bytes = thinMachOWithEntitlements(entitlementBlob())
        val superBlob = 48
        val entitlementIndex = superBlob + 20
        // Point the entitlement blob into the middle of the CodeDirectory blob.
        putBe(bytes, entitlementIndex + 4, 100)
        val result = MachOInspector.inspect(bytes)
        assertTrue(result.isMachO)
        assertFalse(result.hasCodeDirectory)
    }

    @Test
    fun rejectsDuplicateEmbeddedSignatureSlotTypes() {
        val bytes = thinMachOWithEntitlements(entitlementBlob())
        val superBlob = 48
        // Both index entries now claim CSSLOT_CODEDIRECTORY.
        putBe(bytes, superBlob + 20, 0)
        val result = MachOInspector.inspect(bytes)
        assertTrue(result.isMachO)
        assertFalse(result.hasCodeDirectory)
    }

    @Test
    fun rejectsEmbeddedSignatureBlobLengthOutsideSuperBlob() {
        val bytes = thinMachO(includeSignature = true)
        val codeDirectory = 48 + 20
        putBe(bytes, codeDirectory + 4, 0x7fffffff)
        val result = MachOInspector.inspect(bytes)
        assertTrue(result.isMachO)
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

    @Test
    fun rejectsFatMachOWhenOneSignedArchitectureHasInvalidCodeDirectory() {
        val first = thinMachO(includeSignature = true)
        val second = thinMachO(includeSignature = true)
        second[8] = (second[8].toInt() xor 0x01).toByte()

        val tableSize = 8 + 2 * 20
        val firstOffset = tableSize
        val secondOffset = firstOffset + first.size
        val bytes = ByteArray(secondOffset + second.size)
        putBe(bytes, 0, 0xCAFEBABE.toInt())
        putBe(bytes, 4, 2)
        putBe(bytes, 8, 0x0100000C); putBe(bytes, 12, 0)
        putBe(bytes, 16, firstOffset); putBe(bytes, 20, first.size); putBe(bytes, 24, 0)
        putBe(bytes, 28, 0x01000007); putBe(bytes, 32, 3)
        putBe(bytes, 36, secondOffset); putBe(bytes, 40, second.size); putBe(bytes, 44, 0)
        first.copyInto(bytes, firstOffset)
        second.copyInto(bytes, secondOffset)

        val result = MachOInspector.inspect(bytes)
        assertTrue(result.isMachO)
        assertTrue(result.hasCodeSignatureCommand)
        assertFalse(result.hasCodeDirectory)
    }

    @Test
    fun rejectsFatMachOWhenOneArchitectureIsUnsigned() {
        val signed = thinMachO(includeSignature = true)
        val unsigned = thinMachO(includeSignature = false)
        val tableSize = 8 + 2 * 20
        val signedOffset = tableSize
        val unsignedOffset = signedOffset + signed.size
        val bytes = ByteArray(unsignedOffset + unsigned.size)
        putBe(bytes, 0, 0xCAFEBABE.toInt())
        putBe(bytes, 4, 2)
        putBe(bytes, 8, 0x0100000C); putBe(bytes, 12, 0)
        putBe(bytes, 16, signedOffset); putBe(bytes, 20, signed.size); putBe(bytes, 24, 0)
        putBe(bytes, 28, 0x01000007); putBe(bytes, 32, 3)
        putBe(bytes, 36, unsignedOffset); putBe(bytes, 40, unsigned.size); putBe(bytes, 44, 0)
        signed.copyInto(bytes, signedOffset)
        unsigned.copyInto(bytes, unsignedOffset)

        val result = MachOInspector.inspect(bytes)
        assertTrue(result.isMachO)
        assertTrue(result.hasCodeSignatureCommand)
        assertFalse(result.hasCodeDirectory)
    }

    private fun entitlementBlob(): ByteArray {
        val blob = ByteArray(12)
        putBe(blob, 0, 0xfade7171.toInt())
        putBe(blob, 4, blob.size)
        putBe(blob, 8, 0)
        return blob
    }

    private fun thinMachOWithEntitlements(entitlements: ByteArray?): ByteArray {
        val headerSize = 32
        val commandSize = 16
        val codeDirectoryLength = 237
        val indexCount = if (entitlements != null) 2 else 1
        val indexBytes = indexCount * 8
        val cdOffset = 12 + indexBytes
        val entitlementOffset = cdOffset + codeDirectoryLength
        val signatureSize = if (entitlements != null) entitlementOffset + entitlements.size else entitlementOffset
        val bytes = ByteArray(headerSize + commandSize + signatureSize)
        putBe(bytes, 0, -17958193)
        putBe(bytes, 4, 0x0100000C)
        putBe(bytes, 12, 2)
        putBe(bytes, 16, 1)
        putBe(bytes, 20, commandSize)
        putBe(bytes, 32, 0x1d)
        putBe(bytes, 36, 16)
        putBe(bytes, 40, headerSize + commandSize)
        putBe(bytes, 44, signatureSize)

        val superBlob = headerSize + commandSize
        putBe(bytes, superBlob, 0xfade0cc0.toInt())
        putBe(bytes, superBlob + 4, signatureSize)
        putBe(bytes, superBlob + 8, indexCount)
        putBe(bytes, superBlob + 12, 0)
        putBe(bytes, superBlob + 16, cdOffset)
        if (entitlements != null) {
            putBe(bytes, superBlob + 20, 5)
            putBe(bytes, superBlob + 24, entitlementOffset)
            entitlements.copyInto(bytes, superBlob + entitlementOffset)
        }

        val cd = superBlob + cdOffset
        putBe(bytes, cd, 0xfade0c02.toInt())
        putBe(bytes, cd + 4, codeDirectoryLength)
        putBe(bytes, cd + 8, 0x20000)
        putBe(bytes, cd + 16, 205)
        putBe(bytes, cd + 20, 44)
        putBe(bytes, cd + 24, 5)
        putBe(bytes, cd + 28, 1)
        putBe(bytes, cd + 32, 32)
        bytes[cd + 36] = 32
        bytes[cd + 37] = 2
        bytes[cd + 39] = 12
        bytes[cd + 44] = 0
        if (entitlements != null) {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(entitlements)
            digest.copyInto(bytes, cd + 205 - 5 * 32)
        }
        val pageHash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(0, 32))
        pageHash.copyInto(bytes, cd + 205)
        return bytes
    }

    private fun thinMachO(includeSignature: Boolean, resourceSealBytes: ByteArray? = null, infoPlistBytes: ByteArray? = null): ByteArray {
        val headerSize = 32
        val commandSize = if (includeSignature) 16 else 0
        val hasSpecialHashes = resourceSealBytes != null || infoPlistBytes != null
        val codeDirectoryLength = if (hasSpecialHashes) 173 else 77
        val signatureSize = if (includeSignature) 20 + codeDirectoryLength else 0
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
            putBe(bytes, cd + 4, codeDirectoryLength)
            putBe(bytes, cd + 8, 0x20000) // version 2.0: 44-byte fixed header
            putBe(bytes, cd + 12, 0)
            putBe(bytes, cd + 16, if (hasSpecialHashes) 141 else 45) // hashOffset
            putBe(bytes, cd + 20, 44) // identifier offset
            putBe(bytes, cd + 24, if (hasSpecialHashes) 3 else 0) // special slots
            putBe(bytes, cd + 28, 1) // code slots
            putBe(bytes, cd + 32, 32) // codeLimit
            bytes[cd + 36] = 32
            bytes[cd + 37] = 2 // SHA-256
            bytes[cd + 38] = 0
            bytes[cd + 39] = 12 // 4 KB pages
            bytes[cd + 44] = 0 // empty identifier terminator
            if (resourceSealBytes != null) {
                val sealHash = java.security.MessageDigest.getInstance("SHA-256").digest(resourceSealBytes)
                sealHash.copyInto(bytes, cd + 45) // special slot 3 is stored before hashOffset
            }
            if (infoPlistBytes != null) {
                val infoHash = java.security.MessageDigest.getInstance("SHA-256").digest(infoPlistBytes)
                infoHash.copyInto(bytes, cd + 109) // special slot 1
            }
            val pageHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes.copyOfRange(0, 32))
            pageHash.copyInto(bytes, cd + if (hasSpecialHashes) 141 else 45)
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
