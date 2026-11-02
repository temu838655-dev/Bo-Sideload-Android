package com.bosideload.android

import java.security.MessageDigest

/**
 * Structural Mach-O load-command inspection only. Finding LC_CODE_SIGNATURE does not
 * validate the CodeDirectory, CMS signature, certificate chain, or Apple trust.
 */
internal object MachOInspector {
    data class Result(
        val isMachO: Boolean,
        val hasCodeSignatureCommand: Boolean,
        val message: String,
        val hasCodeDirectory: Boolean = false
    )

    fun inspect(bytes: ByteArray, resourceSealBytes: ByteArray? = null, infoPlistBytes: ByteArray? = null): Result {
        if (bytes.size < 4) return Result(false, false, "Executable too small.")
        val magic = u32be(bytes, 0).toLong() and 0xffffffffL
        return when (magic) {
            0xFEEDFACE, 0xFEEDFACF, 0xCEFAEDFE, 0xCFFAEDFE ->
                inspectThin(bytes, 0, bytes.size, resourceSealBytes, infoPlistBytes)
            0xCAFEBABE, 0xCAFEBABF, 0xBEBAFECA, 0xBFBAFECA ->
                inspectFat(bytes, resourceSealBytes, infoPlistBytes)
            else -> Result(false, false, "Unknown Mach-O magic.")
        }
    }

    private fun inspectFat(bytes: ByteArray, resourceSealBytes: ByteArray?, infoPlistBytes: ByteArray?): Result {
        if (bytes.size < 8) return Result(false, false, "Truncated fat header.")
        val magic = u32be(bytes, 0).toLong() and 0xffffffffL
        val is64 = magic == 0xCAFEBABF || magic == 0xBFBAFECA
        val little = magic == 0xBEBAFECA || magic == 0xBFBAFECA
        val count = readU32(bytes, 4, little) ?: return Result(false, false, "Invalid architecture count.")
        if (count !in 1..64) return Result(false, false, "Invalid fat architecture count.")
        val entrySize = if (is64) 32 else 20
        if (8L + count.toLong() * entrySize > bytes.size) {
            return Result(false, false, "Truncated fat architecture table.")
        }
        var foundSignature = false
        var allSlicesHaveCodeDirectory = true
        var signedSliceCount = 0
        repeat(count) { index ->
            val base = 8 + index * entrySize
            val offset = if (is64) {
                readU64(bytes, base + 8, little) ?: return Result(false, false, "Invalid slice offset.")
            } else {
                readU32(bytes, base + 8, little)?.toLong() ?: return Result(false, false, "Invalid slice offset.")
            }
            val size = if (is64) {
                readU64(bytes, base + 16, little) ?: return Result(false, false, "Invalid slice size.")
            } else {
                readU32(bytes, base + 12, little)?.toLong() ?: return Result(false, false, "Invalid slice size.")
            }
            if (offset < 0 || size <= 0 || offset > Int.MAX_VALUE || size > Int.MAX_VALUE ||
                offset + size > bytes.size.toLong()) {
                return Result(false, false, "Mach-O slice is out of bounds.")
            }
            val slice = inspectThin(bytes, offset.toInt(), size.toInt(), resourceSealBytes, infoPlistBytes)
            if (!slice.isMachO) return slice
            if (slice.hasCodeSignatureCommand) {
                foundSignature = true
                signedSliceCount++
                if (!slice.hasCodeDirectory) allSlicesHaveCodeDirectory = false
            } else {
                allSlicesHaveCodeDirectory = false
            }
        }
        return Result(
            true, foundSignature,
            if (foundSignature) "Mach-O contains LC_CODE_SIGNATURE." else "Mach-O has no LC_CODE_SIGNATURE.",
            foundSignature && signedSliceCount == count && allSlicesHaveCodeDirectory
        )
    }

    private fun inspectThin(bytes: ByteArray, start: Int, length: Int, resourceSealBytes: ByteArray?, infoPlistBytes: ByteArray?): Result {
        if (length < 28 || start < 0 || start.toLong() + length > bytes.size) {
            return Result(false, false, "Truncated Mach-O header.")
        }
        val magic = u32be(bytes, start).toLong() and 0xffffffffL
        val little = magic == 0xCEFAEDFE || magic == 0xCFFAEDFE
        val is64 = magic == 0xFEEDFACF || magic == 0xCFFAEDFE
        if (magic != 0xFEEDFACE && magic != 0xFEEDFACF &&
            magic != 0xCEFAEDFE && magic != 0xCFFAEDFE) {
            return Result(false, false, "Invalid Mach-O slice magic.")
        }
        val headerSize = if (is64) 32 else 28
        if (length < headerSize) return Result(false, false, "Truncated Mach-O header.")
        val ncmds = readU32(bytes, start + 16, little)?.toLong()?.and(0xffffffffL)
            ?: return Result(false, false, "Invalid ncmds.")
        val sizeofcmds = readU32(bytes, start + 20, little)?.toLong()?.and(0xffffffffL)
            ?: return Result(false, false, "Invalid sizeofcmds.")
        if (ncmds > 65535L || sizeofcmds > (length - headerSize).toLong()) {
            return Result(false, false, "Mach-O load commands exceed slice bounds.")
        }
        var cursor = start + headerSize
        val commandsEnd = cursor + sizeofcmds.toInt()
        var hasCodeSignature = false
        var hasCodeDirectory = false
        repeat(ncmds.toInt()) {
            if (cursor + 8 > commandsEnd) return Result(false, false, "Truncated load command.")
            val cmd = readU32(bytes, cursor, little) ?: return Result(false, false, "Invalid load command.")
            val cmdSize = readU32(bytes, cursor + 4, little) ?: return Result(false, false, "Invalid load command size.")
            if (cmdSize < 8 || cursor.toLong() + cmdSize > commandsEnd) {
                return Result(false, false, "Invalid load command bounds.")
            }
            if (cmd == LC_CODE_SIGNATURE) {
                if (cmdSize < 16) return Result(false, false, "Invalid LC_CODE_SIGNATURE command.")
                val dataoff = readU32(bytes, cursor + 8, little) ?: return Result(false, false, "Invalid signature offset.")
                val datasize = readU32(bytes, cursor + 12, little) ?: return Result(false, false, "Invalid signature size.")
                if (datasize == 0 || dataoff.toLong() + datasize > length) {
                    return Result(false, false, "Code signature data is out of bounds.")
                }
                val directoryValid = containsCodeDirectory(
                    bytes, start + dataoff, datasize, start, length, resourceSealBytes, infoPlistBytes
                )
                hasCodeDirectory = if (hasCodeSignature) hasCodeDirectory && directoryValid else directoryValid
                hasCodeSignature = true
            }
            cursor += cmdSize
        }
        return Result(
            true, hasCodeSignature,
            if (!hasCodeSignature) "Mach-O has no LC_CODE_SIGNATURE."
            else if (hasCodeDirectory) "Mach-O contains a structurally valid CodeDirectory."
            else "LC_CODE_SIGNATURE exists, but no valid CodeDirectory was found.",
            hasCodeDirectory
        )
    }

    /**
     * Validates the primary CodeDirectory structure and its code-page hashes.
     * This is not CMS/certificate-chain verification and does not validate alternate
     * CodeDirectories, resource seals, or Apple's trust policy.
     */
    private fun containsCodeDirectory(bytes: ByteArray, offset: Int, size: Int, codeStart: Int, codeLength: Int, resourceSealBytes: ByteArray?, infoPlistBytes: ByteArray?): Boolean {
        if (offset < 0 || size < 12 || offset.toLong() + size > bytes.size) return false
        if (readU32(bytes, offset, false)?.toLong()?.and(0xffffffffL) != CSMAGIC_EMBEDDED_SIGNATURE) return false
        val blobLength = readU32(bytes, offset + 4, false)?.toLong()?.and(0xffffffffL) ?: return false
        val count = readU32(bytes, offset + 8, false)?.toLong()?.and(0xffffffffL) ?: return false
        if (blobLength < 12 || blobLength > size || count > 4096 || 12L + count * 8L > blobLength) return false
        repeat(count.toInt()) { index ->
            val entry = offset + 12 + index * 8
            val type = readU32(bytes, entry, false)?.toLong()?.and(0xffffffffL) ?: return false
            val relative = readU32(bytes, entry + 4, false)?.toLong()?.and(0xffffffffL) ?: return false
            if (type == CSSLOT_CODEDIRECTORY) {
                if (relative + 44 > blobLength) return false
                val cd = offset + relative.toInt()
                val magic = readU32(bytes, cd, false)?.toLong()?.and(0xffffffffL) ?: return false
                val cdLength = readU32(bytes, cd + 4, false)?.toLong()?.and(0xffffffffL) ?: return false
                val version = readU32(bytes, cd + 8, false)?.toLong()?.and(0xffffffffL) ?: return false
                val hashOffset = readU32(bytes, cd + 16, false)?.toLong()?.and(0xffffffffL) ?: return false
                val identOffset = readU32(bytes, cd + 20, false)?.toLong()?.and(0xffffffffL) ?: return false
                val specialSlots = readU32(bytes, cd + 24, false)?.toLong()?.and(0xffffffffL) ?: return false
                val codeSlots = readU32(bytes, cd + 28, false)?.toLong()?.and(0xffffffffL) ?: return false
                var codeLimit = readU32(bytes, cd + 32, false)?.toLong()?.and(0xffffffffL) ?: return false
                val hashSize = bytes[cd + 36].toInt() and 0xff
                val hashType = bytes[cd + 37].toInt() and 0xff
                val pageSize = bytes[cd + 39].toInt() and 0xff
                if (magic != CSMAGIC_CODEDIRECTORY || cdLength < 44 || relative + cdLength > blobLength) return false
                if (hashSize == 0 || hashType !in 1..4 || pageSize > 30 || identOffset >= cdLength) return false
                if (specialSlots > 10_000 || codeSlots > 10_000_000) return false

                // CodeDirectory v=0x20300 adds a 64-bit codeLimit at offset 56.
                if (version >= 0x20300L) {
                    if (cdLength < 64) return false
                    val limit64 = readU64BigEndian(bytes, cd + 56) ?: return false
                    if (limit64 != 0L) codeLimit = limit64
                }
                if (codeLimit > Int.MAX_VALUE.toLong()) return false
                val pageBytes = if (pageSize == 0) codeLimit.coerceAtLeast(1L) else 1L shl pageSize
                val expectedSlots = if (codeLimit == 0L) 0L else (codeLimit + pageBytes - 1) / pageBytes
                if (expectedSlots != codeSlots) return false
                if (hashOffset > cdLength || codeSlots * hashSize > cdLength - hashOffset) return false
                if (specialSlots * hashSize > hashOffset) return false
                if (codeLimit > codeLength.toLong() || codeStart < 0 || codeStart.toLong() + codeLength > bytes.size) return false

                val algorithm = when (hashType) {
                    1 -> "SHA-1"
                    2, 3 -> "SHA-256"
                    4 -> "SHA-384"
                    else -> return false
                }
                val digestLength = MessageDigest.getInstance(algorithm).digestLength
                if (hashSize > digestLength || (hashType == 1 && hashSize > 20) ||
                    (hashType == 2 && hashSize != 32) || (hashType == 3 && hashSize > 32) ||
                    (hashType == 4 && hashSize > 48)) return false

                // CSSLOT_INFOSLOT is special slot 1. Verify the raw main Info.plist
                // bytes when supplied by the IPA verifier.
                if (infoPlistBytes != null) {
                    if (specialSlots < CSSLOT_INFOSLOT || hashOffset < CSSLOT_INFOSLOT * hashSize) return false
                    val infoHashAt = cd + hashOffset.toInt() - CSSLOT_INFOSLOT * hashSize
                    val infoDigest = MessageDigest.getInstance(algorithm).digest(infoPlistBytes)
                    for (i in 0 until hashSize) {
                        if (bytes[infoHashAt + i] != infoDigest[i]) return false
                    }
                }

                // CSSLOT_ENTITLEMENTS is special slot 5. The entitlement blob
                // is stored in the embedded-signature SuperBlob; authenticate its
                // complete encoded blob against the CodeDirectory special slot.
                if (specialSlots >= CSSLOT_ENTITLEMENTS) {
                    if (hashOffset < CSSLOT_ENTITLEMENTS * hashSize) return false
                    var entitlementsOffset = -1
                    var entitlementsLength = -1
                    repeat(count.toInt()) { blobIndex ->
                        val blobEntry = offset + 12 + blobIndex * 8
                        val blobType = readU32(bytes, blobEntry, false)?.toLong()?.and(0xffffffffL) ?: return false
                        if (blobType == CSSLOT_ENTITLEMENTS.toLong()) {
                            val relativeBlob = readU32(bytes, blobEntry + 4, false)?.toLong()?.and(0xffffffffL) ?: return false
                            if (relativeBlob + 8 > blobLength) return false
                            val absoluteBlob = offset + relativeBlob.toInt()
                            val blobMagic = readU32(bytes, absoluteBlob, false)?.toLong()?.and(0xffffffffL) ?: return false
                            val blobSize = readU32(bytes, absoluteBlob + 4, false)?.toLong()?.and(0xffffffffL) ?: return false
                            if (blobMagic != CSMAGIC_EMBEDDED_ENTITLEMENTS || blobSize < 8 || relativeBlob + blobSize > blobLength) return false
                            entitlementsOffset = absoluteBlob
                            entitlementsLength = blobSize.toInt()
                        }
                    }
                    if (entitlementsOffset < 0 || entitlementsLength < 0) return false
                    val entitlementHashAt = cd + hashOffset.toInt() - CSSLOT_ENTITLEMENTS * hashSize
                    val entitlementDigest = MessageDigest.getInstance(algorithm).digest(
                        bytes.copyOfRange(entitlementsOffset, entitlementsOffset + entitlementsLength)
                    )
                    for (i in 0 until hashSize) {
                        if (bytes[entitlementHashAt + i] != entitlementDigest[i]) return false
                    }
                }

                // CSSLOT_RESOURCEDIR is special slot 3. When the caller supplies
                // the bundle's CodeResources bytes, require the CodeDirectory's
                // backwards-indexed special-slot digest to match them.
                if (resourceSealBytes != null) {
                    if (specialSlots < CSSLOT_RESOURCEDIR || hashOffset < CSSLOT_RESOURCEDIR * hashSize) return false
                    val resourceHashAt = cd + hashOffset.toInt() - CSSLOT_RESOURCEDIR * hashSize
                    val resourceDigest = MessageDigest.getInstance(algorithm).digest(resourceSealBytes)
                    for (i in 0 until hashSize) {
                        if (bytes[resourceHashAt + i] != resourceDigest[i]) return false
                    }
                }

                val hashStart = cd + hashOffset.toInt()
                var pageOffset = 0L
                repeat(codeSlots.toInt()) { slot ->
                    val pageLength = minOf(pageBytes, codeLimit - pageOffset).toInt()
                    // Hash directly from the executable buffer. Avoid allocating a second
                    // byte array for every page (executables may be hundreds of MB).
                    val digestEngine = MessageDigest.getInstance(algorithm)
                    digestEngine.update(bytes, codeStart + pageOffset.toInt(), pageLength)
                    val digest = digestEngine.digest()
                    val expectedAt = hashStart + slot * hashSize
                    for (i in 0 until hashSize) {
                        if (bytes[expectedAt + i] != digest[i]) return false
                    }
                    pageOffset += pageLength
                }
                return true
            }
        }
        return false
    }

    private fun readU64BigEndian(bytes: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset.toLong() + 8 > bytes.size) return null
        val high = readU32(bytes, offset, false)?.toLong()?.and(0xffffffffL) ?: return null
        val low = readU32(bytes, offset + 4, false)?.toLong()?.and(0xffffffffL) ?: return null
        if (high > 0x7fffffffL) return null
        return (high shl 32) or low
    }

    private fun readU32(bytes: ByteArray, offset: Int, little: Boolean): Int? {
        if (offset < 0 || offset.toLong() + 4 > bytes.size) return null
        val a = bytes[offset].toInt() and 0xff
        val b = bytes[offset + 1].toInt() and 0xff
        val c = bytes[offset + 2].toInt() and 0xff
        val d = bytes[offset + 3].toInt() and 0xff
        return if (little) a or (b shl 8) or (c shl 16) or (d shl 24)
        else (a shl 24) or (b shl 16) or (c shl 8) or d
    }

    private fun readU64(bytes: ByteArray, offset: Int, little: Boolean): Long? {
        if (offset < 0 || offset.toLong() + 8 > bytes.size) return null
        val low = readU32(bytes, offset + if (little) 0 else 4, little)?.toLong() ?: return null
        val high = readU32(bytes, offset + if (little) 4 else 0, little)?.toLong() ?: return null
        if (high > 0x7fffffffL) return null
        return (high shl 32) or low
    }

    private fun u32be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private const val LC_CODE_SIGNATURE = 0x1d
    private const val CSMAGIC_EMBEDDED_SIGNATURE = 0xfade0cc0L
    private const val CSMAGIC_CODEDIRECTORY = 0xfade0c02L
    private const val CSMAGIC_EMBEDDED_ENTITLEMENTS = 0xfade7171L
    private const val CSSLOT_CODEDIRECTORY = 0L
    private const val CSSLOT_INFOSLOT = 1
    private const val CSSLOT_RESOURCEDIR = 3
    private const val CSSLOT_ENTITLEMENTS = 5
}
