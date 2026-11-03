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
                if (!verification.valid) {
                    outputIpa.delete()
                    SignResult(
                        false,
                        null,
                        "zsign trả mã thành công nhưng IPA đầu ra không vượt qua kiểm tra cấu trúc: ${verification.message}\\n$details"
                    )
                } else {
                    val certificateCheck = checkSignedCertificate(binary, outputIpa)
                    if (!certificateCheck.valid) {
                        outputIpa.delete()
                        SignResult(
                            false,
                            null,
                            "IPA được tạo nhưng kiểm tra certificate/OCSP không đạt: ${certificateCheck.message}\\n$details"
                        )
                    } else {
                        SignResult(
                            true,
                            outputIpa,
                            "IPA đã vượt qua kiểm tra cấu trúc. ${verification.message}\\n" +
                                "KIỂM TRA CERTIFICATE: ${certificateCheck.message}\\n$details"
                        )
                    }
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

    private data class CertificateCheck(val valid: Boolean, val message: String)

    /**
     * Ask zsign's own certificate checker to inspect the signed IPA and query
     * Apple's OCSP endpoint when network access is available. Structural
     * verification remains separate and is not represented as CMS verification.
     */
    private fun checkSignedCertificate(binary: File, ipa: File): CertificateCheck {
        val process = try {
            ProcessBuilder(binary.absolutePath, "-C", ipa.absolutePath)
                .directory(context.cacheDir)
                .redirectErrorStream(true)
                .start()
        } catch (error: Exception) {
            return CertificateCheck(false,
                "Không khởi chạy được kiểm tra zsign -C: ${error.message ?: "lỗi"}")
        }
        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(output) {
                        if (output.length < MAX_CERT_CHECK_LOG_CHARS) {
                            output.append(line).append('\n')
                        }
                    }
                }
            }
        }
        reader.start()
        val finished = try {
            process.waitFor(CERT_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { reader.join(1500) }
            return CertificateCheck(false, "Kiểm tra certificate quá thời gian chờ.")
        }
        runCatching { reader.join(2000) }
        val text = synchronized(output) { output.toString().trim() }
        val signed = Regex("Signed\\s*:\\s*Yes", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val revoked = Regex("OCSP\\s*:\\s*Revoked", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val invalid = Regex("(certificate|cert)\\s*:\\s*(invalid|expired|revoked)", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
        if (process.exitValue() != 0 || !signed || revoked || invalid) {
            return CertificateCheck(false,
                "zsign -C không xác nhận certificate hợp lệ (exit ${process.exitValue()}).\n" +
                    text.takeLast(2500))
        }
        val ocspValid = Regex("OCSP\\s*:\\s*Valid", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val ocspUnknown = Regex("OCSP\\s*:\\s*(unknown|unavailable|error|not checked)", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
        val note = when {
            ocspValid -> "Certificate được zsign nhận diện và OCSP báo Valid."
            ocspUnknown -> "Certificate được zsign nhận diện; OCSP chưa xác minh được."
            else -> "Certificate được zsign nhận diện; không có kết quả OCSP rõ ràng."
        }
        return CertificateCheck(true, note + "\n" + text.takeLast(1800))
    }

    fun engineVersion(): String {
        return try {
            val binary = installEngine()
            val process = ProcessBuilder(binary.absolutePath, "-v")
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.length < MAX_VERSION_OUTPUT_CHARS) {
                                output.append(line).append('\n')
                            }
                        }
                    }
                }
            }
            reader.start()

            val finished = process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.inputStream.close()
                reader.join(2_000)
                "Không xác định được phiên bản zsign (quá thời gian chờ)."
            } else {
                reader.join(2_000)
                if (process.exitValue() == 0) {
                    synchronized(output) { output.toString().trim() }
                        .ifEmpty { "zsign không trả về thông tin phiên bản." }
                } else {
                    "Không xác định được phiên bản zsign."
                }
            }
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
        const val VERSION_TIMEOUT_SECONDS = 15L
        const val MAX_VERSION_OUTPUT_CHARS = 4_000
        const val CERT_CHECK_TIMEOUT_SECONDS = 90L
        const val MAX_CERT_CHECK_LOG_CHARS = 12_000
    }
}
