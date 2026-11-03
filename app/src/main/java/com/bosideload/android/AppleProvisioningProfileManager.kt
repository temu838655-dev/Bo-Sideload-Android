package com.bosideload.android

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

/**
 * Explicit App Store Connect provisioning-profile operations.
 *
 * Creation changes the user's Apple Developer account. Call only from a UI
 * action after the user has reviewed and confirmed the exact profile request.
 */
internal object AppleProvisioningProfileManager {
    enum class Type(val apiValue: String) {
        DEVELOPMENT("IOS_APP_DEVELOPMENT"),
        AD_HOC("IOS_APP_ADHOC"),
        APP_STORE("IOS_APP_STORE")
    }

    data class Request(
        val name: String,
        val type: Type,
        val bundleIdResourceId: String,
        val certificateResourceIds: List<String>,
        val deviceResourceIds: List<String> = emptyList()
    )

    data class Result(
        val ok: Boolean,
        val message: String,
        val profileId: String? = null,
        val profileType: String? = null,
        val profileContent: ByteArray? = null
    )

    fun create(
        credentials: AppleConnectCredentialVault.Credentials,
        request: Request
    ): Result {
        require(request.name.isNotBlank() && request.name.length <= 100) {
            "Tên provisioning profile phải có từ 1 đến 100 ký tự."
        }
        require(validResourceId(request.bundleIdResourceId)) { "App ID resource ID không hợp lệ." }
        require(request.certificateResourceIds.isNotEmpty()) {
            "Cần chọn ít nhất một certificate resource ID."
        }
        require(request.certificateResourceIds.all(::validResourceId)) {
            "Certificate resource ID không hợp lệ."
        }
        require(request.deviceResourceIds.all(::validResourceId)) {
            "Device resource ID không hợp lệ."
        }
        when (request.type) {
            Type.DEVELOPMENT, Type.AD_HOC -> require(request.deviceResourceIds.isNotEmpty()) {
                "Development và Ad Hoc profile cần ít nhất một thiết bị đã đăng ký."
            }
            Type.APP_STORE -> require(request.deviceResourceIds.isEmpty()) {
                "App Store profile không nhận danh sách UDID thiết bị."
            }
        }

        val certificates = JSONArray()
        request.certificateResourceIds.distinct().forEach {
            certificates.put(JSONObject().put("type", "certificates").put("id", it))
        }
        val relationships = JSONObject()
            .put("bundleId", JSONObject().put("data",
                JSONObject().put("type", "bundleIds").put("id", request.bundleIdResourceId)))
            .put("certificates", JSONObject().put("data", certificates))
        if (request.deviceResourceIds.isNotEmpty()) {
            val devices = JSONArray()
            request.deviceResourceIds.distinct().forEach {
                devices.put(JSONObject().put("type", "devices").put("id", it))
            }
            relationships.put("devices", JSONObject().put("data", devices))
        }

        val body = JSONObject().put("data", JSONObject()
            .put("type", "profiles")
            .put("attributes", JSONObject()
                .put("name", request.name)
                .put("profileType", request.type.apiValue))
            .put("relationships", relationships))
            .toString().toByteArray(Charsets.UTF_8)

        val connection = URL("https://api.appstoreconnect.apple.com/v1/profiles")
            .openConnection() as HttpsURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer " + AppleConnectJwt.token(credentials))
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code != 201) {
                val detail = runCatching {
                    JSONObject(response).optJSONArray("errors")?.optJSONObject(0)
                        ?.optString("detail").orEmpty()
                }.getOrDefault("")
                return Result(false, "Apple không tạo được provisioning profile (HTTP $code)." +
                    if (detail.isNotBlank()) " $detail" else " " + response.take(350))
            }
            val data = JSONObject(response).getJSONObject("data")
            val id = data.getString("id")
            val attributes = data.optJSONObject("attributes") ?: JSONObject()
            val actualType = attributes.optString("profileType", request.type.apiValue)
            require(actualType == request.type.apiValue) {
                "Apple trả về profileType khác với loại đã yêu cầu."
            }
            val base64 = attributes.optString("profileContent")
                .takeIf { it.isNotBlank() } ?: fetchProfileContent(credentials, id)
            val normalized = base64.filterNot { it.isWhitespace() }
            require(normalized.isNotBlank()) { "Apple không trả profileContent." }
            val bytes = Base64.getDecoder().decode(normalized)
            Result(true, "Apple đã tạo provisioning profile. Tệp đã tải về bộ nhớ ứng dụng; hãy lưu an toàn và kiểm tra bundle ID, chứng chỉ, thiết bị trước khi ký IPA.",
                id, actualType, bytes)
        } catch (error: Exception) {
            Result(false, "Không thể tạo/tải provisioning profile: " +
                (error.message ?: error.javaClass.simpleName))
        } finally {
            body.fill(0)
            connection.disconnect()
        }
    }

    private fun fetchProfileContent(
        credentials: AppleConnectCredentialVault.Credentials,
        profileId: String
    ): String {
        require(validResourceId(profileId)) { "Apple trả về profile ID không hợp lệ." }
        val url = URL("https://api.appstoreconnect.apple.com/v1/profiles/$profileId" +
            "?fields%5Bprofiles%5D=profileContent,profileType,name")
        val connection = url.openConnection() as HttpsURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 25000
            connection.setRequestProperty("Authorization", "Bearer " + AppleConnectJwt.token(credentials))
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code != 200) {
                val detail = runCatching {
                    JSONObject(response).optJSONArray("errors")?.optJSONObject(0)
                        ?.optString("detail").orEmpty()
                }.getOrDefault("")
                throw IllegalStateException("Không tải được profile từ Apple (HTTP $code)." +
                    if (detail.isNotBlank()) " $detail" else "")
            }
            JSONObject(response).getJSONObject("data").getJSONObject("attributes")
                .optString("profileContent")
                .also { require(it.isNotBlank()) { "Apple không trả profileContent khi đọc profile." } }
        } finally {
            connection.disconnect()
        }
    }

    private fun validResourceId(value: String): Boolean =
        value.isNotBlank() && value.length <= 128 &&
            value.matches(Regex("[A-Za-z0-9_-]+"))
}
