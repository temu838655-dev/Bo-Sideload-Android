package com.bosideload.android

/**
 * Structural Mach-O load-command inspection only. Finding LC_CODE_SIGNATURE does not
 * validate the CodeDirectory, CMS signature, certificate chain, or Apple trust.
 */
internal object MachOInspector {
    data class Result(val isMachO: Boolean, val hasCodeSignatureCommand: Boolean, val message: String)

    fun inspect(bytes: ByteArray): Result {
        if (bytes.size < 4) return Result(false, false, "Executable too small.")
        val magic = u32be(bytes, 0).toLong() and 0xffffffffL
        return when (magic) {
            0xFEEDFACE, 0xFEEDFACF, 0xCEFAEDFE, 0xCFFAEDFE ->
                inspectThin(bytes, 0, bytes.size)
            0xCAFEBABE, 0xCAFEBABF, 0xBEBAFECA, 0xBFBAFECA ->
                inspectFat(bytes)
            else -> Result(false, false, "Unknown Mach-O magic.")
        }
    }

    private fun inspectFat(bytes: ByteArray): Result {
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
            val slice = inspectThin(bytes, offset.toInt(), size.toInt())
            if (!slice.isMachO) return slice
            foundSignature = foundSignature || slice.hasCodeSignatureCommand
        }
        return Result(true, foundSignature, if (foundSignature) "Mach-O contains LC_CODE_SIGNATURE." else "Mach-O has no LC_CODE_SIGNATURE.")
    }

    private fun inspectThin(bytes: ByteArray, start: Int, length: Int): Result {
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
                hasCodeSignature = true
            }
            cursor += cmdSize
        }
        return Result(true, hasCodeSignature, if (hasCodeSignature) "Mach-O contains LC_CODE_SIGNATURE." else "Mach-O has no LC_CODE_SIGNATURE.")
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
}
