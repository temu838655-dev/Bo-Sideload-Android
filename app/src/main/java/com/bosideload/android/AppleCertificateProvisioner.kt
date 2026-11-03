package com.bosideload.android

import org.json.JSONObject
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

/**
 * Explicit, user-initiated Apple certificate creation.
 * No certificate is created until the UI asks the user to confirm.
 */
internal object AppleCertificateProvisioner {
    data class Result(
        val ok: Boolean,
        val message: String,
        val certificate: AppleConnectCredentialVault.IssuedCertificate? = null
    )

    fun create(
        credentials: AppleConnectCredentialVault.Credentials,
        material: AppleConnectCredentialVault.SigningMaterial,
        certificateType: String,
        vault: AppleConnectCredentialVault
    ): Result {
        require(certificateType == "DEVELOPMENT" || certificateType == "DISTRIBUTION") {
            "Chỉ hỗ trợ Apple Development hoặc Apple Distribution."
        }
        require(material.csrPem.contains("-----BEGIN CERTIFICATE REQUEST-----")) {
            "CSR chưa đúng định dạng PKCS#10."
        }

        val csrContent = Base64.getEncoder().encodeToString(
            material.csrPem.toByteArray(StandardCharsets.US_ASCII)
        )
        val requestBody = JSONObject()
            .put("data", JSONObject()
                .put("type", "certificates")
                .put("attributes", JSONObject()
                    .put("certificateType", certificateType)
                    .put("csrContent", csrContent)))
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        val connection = URL("https://api.appstoreconnect.apple.com/v1/certificates")
            .openConnection() as HttpsURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 25000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer " + AppleConnectJwt.token(credentials))
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(requestBody) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code != 201) {
                val detail = runCatching {
                    JSONObject(body).optJSONArray("errors")?.optJSONObject(0)
                        ?.optString("detail").orEmpty()
                }.getOrDefault("")
                return Result(false, "Apple không tạo được chứng chỉ (HTTP $code)." +
                    if (detail.isNotBlank()) " $detail" else " " + body.take(350))
            }

            val data = JSONObject(body).getJSONObject("data")
            val id = data.getString("id")
            val attrs = data.getJSONObject("attributes")
            val actualType = attrs.optString("certificateType", certificateType)
            require(actualType == certificateType) {
                "Apple trả về loại chứng chỉ khác với loại đã chọn; không lưu chứng chỉ."
            }
            val derBase64 = attrs.optString("certificateContent")
            require(derBase64.isNotBlank()) { "Apple không trả certificateContent." }
            val der = Base64.getDecoder().decode(derBase64)
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            val expectedPublicKey = Base64.getDecoder().decode(material.publicKeyX509Base64)
            val matches = MessageDigest.isEqual(certificate.publicKey.encoded, expectedPublicKey)
            expectedPublicKey.fill(0)
            require(matches) {
                "Chứng chỉ Apple trả về không khớp khóa riêng đang lưu. Không lưu chứng chỉ này."
            }
            certificate.checkValidity()
            val saved = AppleConnectCredentialVault.IssuedCertificate(
                certificateId = id,
                certificateType = actualType,
                expirationDate = attrs.optString("expirationDate", ""),
                certificateDerBase64 = Base64.getEncoder().encodeToString(der)
            )
            der.fill(0)
            vault.saveIssuedCertificate(saved)
            Result(true,
                "Apple đã cấp chứng chỉ $actualType và ứng dụng xác nhận khóa công khai khớp cặp khóa CSR trên thiết bị. Chứng chỉ đã được lưu mã hóa. Bước P12 và provisioning profile vẫn chưa hoàn tất.",
                saved)
        } catch (error: Exception) {
            Result(false, "Không thể tạo/xác minh chứng chỉ: " +
                (error.message ?: error.javaClass.simpleName))
        } finally {
            requestBody.fill(0)
            connection.disconnect()
        }
    }
}
