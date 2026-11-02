package com.bosideload.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IpaArchiveValidatorTest {
    @Test
    fun acceptsBasicIpaLayout() {
        val result = IpaArchiveValidator.validate(zipOf("Payload/Demo.app/Info.plist" to "plist"))
        assertTrue(result.valid)
    }

    @Test
    fun rejectsZipWithoutInfoPlist() {
        val result = IpaArchiveValidator.validate(zipOf("Payload/Demo.app/readme.txt" to "x"))
        assertFalse(result.valid)
    }

    @Test
    fun rejectsMultipleAppInfoPlists() {
        val result = IpaArchiveValidator.validate(
            zipOf(
                "Payload/First.app/Info.plist" to "first",
                "Payload/Second.app/Info.plist" to "second"
            )
        )
        assertFalse(result.valid)
        assertTrue(result.message.contains("nhiều Info.plist"))
    }
    @Test
    fun rejectsDuplicateZipEntryPaths() {
        val result = IpaArchiveValidator.validate(
            zipOf(
                "Payload/Demo.app/Info.plist" to "first",
                "Payload/Demo.app/Info.plist" to "second"
            )
        )
        assertFalse(result.valid)
        assertTrue(result.message.contains("đường dẫn bị lặp"))
    }

    @Test
    fun rejectsPathTraversal() {
        val result = IpaArchiveValidator.validate(zipOf("../outside.txt" to "x"))
        assertFalse(result.valid)
        assertTrue(result.message.contains("không an toàn"))
    }

    @Test
    fun rejectsTooManyEntries() {
        val result = IpaArchiveValidator.validate(
            zipOf("one.txt" to "1", "two.txt" to "2"),
            maxEntries = 1
        )
        assertFalse(result.valid)
        assertTrue(result.message.contains("quá nhiều"))
    }

    @Test
    fun rejectsOversizedExpandedContent() {
        val result = IpaArchiveValidator.validate(
            zipOf("Payload/Demo.app/Info.plist" to "123456"),
            maxUncompressedBytes = 5
        )
        assertFalse(result.valid)
        assertTrue(result.message.contains("vượt giới hạn"))
    }

    @Test
    fun rejectsEmptyArchive() {
        val result = IpaArchiveValidator.validate(ByteArrayInputStream(ByteArray(0)))
        assertFalse(result.valid)
    }

    private fun zipOf(vararg files: Pair<String, String>): ByteArrayInputStream {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, contents) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents.toByteArray())
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(output.toByteArray())
    }
}
