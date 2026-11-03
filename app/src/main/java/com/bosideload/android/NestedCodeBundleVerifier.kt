package com.bosideload.android

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * Structural inspection for nested .appex and Watch .app bundles.
 * This does not perform CMS or Apple trust-chain verification.
 */
internal object NestedCodeBundleVerifier {
    data class Result(val valid: Boolean, val message: String, val checkedBundles: Int = 0)

    fun verify(ipa: File): Result {
        return try {
            ZipFile(ipa).use { zip ->
                val nestedInfoPaths = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith("/Info.plist") }
                    .map { it.name }
                    .filter { path ->
                        path.matches(Regex("^Payload/[^/]+\\.app/.+\\.(?:appex|app)/Info\\.plist$"))
                    }
                    .distinct()
                    .toList()

                for (infoPath in nestedInfoPaths) {
                    val prefix = infoPath.removeSuffix("Info.plist")
                    val info = readEntry(zip, infoPath, MAX_INFO_BYTES)
                        ?: return Result(false, "Không đọc được Info.plist của bundle lồng: $infoPath")
                    val metadata = IpaPlistReader.read(info)
                        ?: return Result(false, "Info.plist của bundle lồng không hợp lệ: $infoPath")
                    val bundleId = metadata.bundleId?.takeIf { it.isNotBlank() }
                        ?: return Result(false, "Bundle lồng thiếu CFBundleIdentifier: $infoPath")
                    val executable = metadata.executable?.takeIf { it.isNotBlank() }
                        ?: return Result(false, "Bundle $bundleId thiếu CFBundleExecutable.")
                    if (executable.contains("/") || executable.contains("\\") ||
                        executable == "." || executable == "..") {
                        return Result(false, "CFBundleExecutable không an toàn trong $bundleId.")
                    }

                    val resourcesPath = prefix + "_CodeSignature/CodeResources"
                    val resources = readEntry(zip, resourcesPath, MAX_RESOURCES_BYTES)
                        ?: return Result(false, "Bundle $bundleId thiếu hoặc có CodeResources không hợp lệ.")
                    val profileEntry = zip.getEntry(prefix + "embedded.mobileprovision")
                    if (profileEntry == null || profileEntry.isDirectory ||
                        profileEntry.size !in 1..MAX_PROFILE_BYTES) {
                        return Result(false, "Bundle $bundleId thiếu embedded.mobileprovision hợp lệ.")
                    }

                    val executableBytes = readEntry(zip, prefix + executable, MAX_EXECUTABLE_BYTES)
                        ?: return Result(false, "Không đọc được executable của bundle $bundleId.")
                    val machO = MachOInspector.inspect(executableBytes, resources, info)
                    if (!machO.isMachO || !machO.hasCodeSignatureCommand || !machO.hasCodeDirectory) {
                        return Result(
                            false,
                            "Chữ ký Mach-O cấu trúc của bundle $bundleId không đạt: ${machO.message}"
                        )
                    }
                }
                Result(true, "Đã kiểm tra cấu trúc chữ ký của ${nestedInfoPaths.size} bundle lồng.", nestedInfoPaths.size)
            }
        } catch (error: Exception) {
            Result(false, "Không thể kiểm tra bundle lồng: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun readEntry(
        zip: ZipFile,
        path: String,
        maxBytes: Long
    ): ByteArray? {
        val entry = zip.getEntry(path) ?: return null
        if (entry.isDirectory || entry.size !in 1..maxBytes) return null
        val output = ByteArrayOutputStream()
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > maxBytes) return null
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private const val MAX_INFO_BYTES = 1L * 1024L * 1024L
    private const val MAX_RESOURCES_BYTES = 16L * 1024L * 1024L
    private const val MAX_PROFILE_BYTES = 10L * 1024L * 1024L
    private const val MAX_EXECUTABLE_BYTES = 512L * 1024L * 1024L
}
