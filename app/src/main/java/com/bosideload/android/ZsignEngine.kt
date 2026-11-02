package com.bosideload.android

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Thin Android adapter around the upstream zsign command-line engine.
 * The binary is fetched by the verified GitHub Actions build and packaged as an app asset.
 */
internal class ZsignEngine(private val context: Context) {
    data class SignResult(
        val success: Boolean,
        val output: File?,
        val message: String
    )

    fun sign(
        inputIpa: File,
        p12File: File,
        provisioningProfile: File,
        password: String,
        outputIpa: File
    ): SignResult {
        if (!inputIpa.isFile || inputIpa.length() == 0L) {
            return SignResult(false, null, "Không tìm thấy IPA đầu vào hoặc tệp rỗng.")
        }
        if (!p12File.isFile || p12File.length() == 0L) {
            return SignResult(false, null, "Tệp chứng chỉ P12 không hợp lệ.")
        }
        if (!provisioningProfile.isFile || provisioningProfile.length() == 0L) {
            return SignResult(false, null, "Tệp provisioning profile không hợp lệ.")
        }

        return try {
            val binary = installEngine()
            outputIpa.parentFile?.mkdirs()
            outputIpa.delete()
            val command = mutableListOf(
                binary.absolutePath,
                "-f",
                "-k", p12File.absolutePath,
                "-p", password,
                "-m", provisioningProfile.absolutePath,
                "-o", outputIpa.absolutePath,
                inputIpa.absolutePath
            )
            val process = ProcessBuilder(command)
                .directory(context.cacheDir)
                .redirectErrorStream(true)
                .start()

            val log = StringBuilder()
            val reader = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(log) {
                            if (log.length < MAX_LOG_CHARS) {
                                log.append(line).append('\n')
                            }
                        }
                    }
                }
            }
            reader.start()

            val finished = process.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                reader.join(2_000)
                outputIpa.delete()
                return SignResult(false, null, "Quá thời gian ký IPA (10 phút). Đã dừng tiến trình.")
            }
            reader.join(2_000)
            val exitCode = process.exitValue()
            val outputExists = outputIpa.isFile && outputIpa.length() > 0L
            val details = synchronized(log) { log.toString().takeLast(4_000).trim() }

            if (exitCode == 0 && outputExists) {
                val verification = SignedIpaVerifier.verify(inputIpa, outputIpa)
                if (verification.valid) {
                    SignResult(
                        true,
                        outputIpa,
                        "zsign đã tạo IPA và kiểm tra cấu trúc đầu ra đạt. ${verification.message}\n$details"
                    )
                } else {
                    outputIpa.delete()
                    SignResult(
                        false,
                        null,
                        "zsign trả mã thành công nhưng IPA đầu ra không vượt qua kiểm tra hậu ký: ${verification.message}\n$details"
                    )
                }
            } else {
                outputIpa.delete()
                SignResult(false, null, "zsign không ký được IPA (mã thoát $exitCode).\n$details")
            }
        } catch (error: Exception) {
            outputIpa.delete()
            SignResult(false, null, "Không chạy được bộ ký trên thiết bị này: ${error.message ?: "lỗi không xác định"}")
        }
    }

    fun engineVersion(): String {
        return try {
            val binary = installEngine()
            val process = ProcessBuilder(binary.absolutePath, "-v")
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0) text
            else "Không xác định được phiên bản zsign."
        } catch (error: Exception) {
            "Bộ ký chưa sẵn sàng: ${error.message ?: "không rõ lỗi"}"
        }
    }

    private fun installEngine(): File {
        val abi = Build.SUPPORTED_ABIS.firstOrNull {
            it == "arm64-v8a" || it == "armeabi-v7a"
        } ?: throw IllegalStateException("Thiết bị không thuộc ABI ARM64/ARMv7 được đóng gói.")
        val destination = File(context.filesDir, "native/zsign")
        if (!destination.exists() || destination.length() == 0L) {
            destination.parentFile?.mkdirs()
            context.assets.open("zsign/$abi/zsign").use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
        }
        if (!destination.setExecutable(true, true) && !destination.canExecute()) {
            throw IllegalStateException("Android không cấp quyền thực thi bộ ký.")
        }
        return destination
    }

    private companion object {
        const val PROCESS_TIMEOUT_MINUTES = 10L
        const val MAX_LOG_CHARS = 32_000
    }
}
