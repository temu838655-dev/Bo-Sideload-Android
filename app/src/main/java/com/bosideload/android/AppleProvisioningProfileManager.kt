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

    data class DeviceRegistrationResult(
        val ok: Boolean,
        val message: String,
        val deviceResourceId: String? = null
    )

    /** Registers a verified iPhone UDID with the Apple Developer team. */
    fun registerDevice(
        credentials: AppleConnectCredentialVault.Credentials,
        name: String,
        udid: String
    ): DeviceRegistrationResult {
        require(name.isNotBlank() && name.length <= 50) {
            "Tên thiết bị phải có từ 1 đến 50 ký tự."
        }
        val normalizedUdid = udid.trim().uppercase()
        require(normalizedUdid.matches(Regex("[0-9A-F]{40}"))) {
            "UDID iPhone không hợp lệ. Cần UDID 40 ký tự hex lấy từ kết nối thiết bị đã xác minh."
        }
        val existing = findDeviceByUdid(credentials, normalizedUdid)
        if (existing != null) {
            val status = existing.optJSONObject("attributes")?.optString("status").orEmpty()
            val id = existing.optString("id")
            return if (status.equals("ENABLED", true)) {
                DeviceRegistrationResult(true,
                    "iPhone này đã được đăng ký trước đó và đang ENABLED. Không tạo bản ghi trùng. Device resource ID: $id",
                    id)
            } else {
                DeviceRegistrationResult(false,
                    "iPhone đã có trong tài khoản Apple nhưng trạng thái là $status. Không tạo bản ghi trùng; hãy kiểm tra trạng thái thiết bị trong Apple Developer.")
            }
        }
        val body = JSONObject().put("data", JSONObject()
            .put("type", "devices")
            .put("attributes", JSONObject()
                .put("name", name.trim())
                .put("platform", "IOS")
                .put("udid", normalizedUdid)))
            .toString().toByteArray(Charsets.UTF_8)
        val connection = URL("https://api.appstoreconnect.apple.com/v1/devices")
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
                return DeviceRegistrationResult(false,
                    "Apple không đăng ký được thiết bị (HTTP $code)." +
                        if (detail.isNotBlank()) " $detail" else " " + response.take(300))
            }
            val data = JSONObject(response).getJSONObject("data")
            val attrs = data.optJSONObject("attributes") ?: JSONObject()
            val id = data.getString("id")
            require(attrs.optString("udid").equals(normalizedUdid, true)) {
                "Apple trả về UDID khác với thiết bị đã xác nhận; dừng sử dụng resource."
            }
            DeviceRegistrationResult(true,
                "Đã đăng ký thiết bị iOS với Apple. Device resource ID: $id. Bạn có thể dùng ID này để tạo Development/Ad Hoc profile.",
                id)
        } catch (error: Exception) {
            DeviceRegistrationResult(false,
                "Không thể đăng ký thiết bị: " + (error.message ?: error.javaClass.simpleName))
        } finally {
            body.fill(0)
            connection.disconnect()
        }
    }

    private fun findDeviceByUdid(
        credentials: AppleConnectCredentialVault.Credentials,
        udid: String
    ): JSONObject? {
        val url = URL("https://api.appstoreconnect.apple.com/v1/devices" +
            "?filter%5Budid%5D=$udid&limit=10&fields%5Bdevices%5D=name,udid,platform,status")
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
            if (code !in 200..299) {
                val detail = runCatching {
                    JSONObject(response).optJSONArray("errors")?.optJSONObject(0)
                        ?.optString("detail").orEmpty()
                }.getOrDefault("")
                throw IllegalStateException("Không kiểm tra được UDID đã tồn tại (HTTP $code)." +
                    if (detail.isNotBlank()) " $detail" else "")
            }
            val data = JSONObject(response).optJSONArray("data") ?: JSONArray()
            (0 until data.length()).asSequence()
                .mapNotNull { data.optJSONObject(it) }
                .firstOrNull { item ->
                    val attrs = item.optJSONObject("attributes") ?: JSONObject()
                    attrs.optString("udid").equals(udid, true) &&
                        attrs.optString("platform").equals("IOS", true)
                }
        } finally {
            connection.disconnect()
        }
    }

    /** Read-only discovery so users can obtain the Apple resource IDs required by profile creation. */
    fun listResources(credentials: AppleConnectCredentialVault.Credentials): Result {
        val endpoints = listOf(
            "App IDs" to "/v1/bundleIds?limit=100&fields%5BbundleIds%5D=identifier,name,platform",
            "Certificates" to "/v1/certificates?limit=100&fields%5Bcertificates%5D=displayName,certificateType,serialNumber,expirationDate",
            "Devices" to "/v1/devices?limit=100&fields%5Bdevices%5D=name,udid,platform,status"
        )
        val sections = mutableListOf<String>()
        return try {
            for ((label, path) in endpoints) {
                val connection = URL("https://api.appstoreconnect.apple.com" + path)
                    .openConnection() as HttpsURLConnection
                val response = try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 15000
                    connection.readTimeout = 25000
                    connection.setRequestProperty("Authorization", "Bearer " + AppleConnectJwt.token(credentials))
                    connection.setRequestProperty("Accept", "application/json")
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) {
                        val detail = runCatching {
                            JSONObject(body).optJSONArray("errors")?.optJSONObject(0)
                                ?.optString("detail").orEmpty()
                        }.getOrDefault("")
                        throw IllegalStateException("$label: HTTP $code " + detail.ifBlank { body.take(180) })
                    }
                    JSONObject(body)
                } finally {
                    connection.disconnect()
                }
                val data = response.optJSONArray("data") ?: JSONArray()
                val lines = mutableListOf<String>()
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val attrs = item.optJSONObject("attributes") ?: JSONObject()
                    val id = item.optString("id")
                    val detail = when (label) {
                        "App IDs" -> attrs.optString("identifier") + " — " + attrs.optString("name") +
                            " — " + attrs.optString("platform")
                        "Certificates" -> attrs.optString("displayName") + " — " +
                            attrs.optString("certificateType") + " — hết hạn " + attrs.optString("expirationDate")
                        else -> attrs.optString("name") + " — " + attrs.optString("udid") +
                            " — " + attrs.optString("platform") + " — " + attrs.optString("status")
                    }
                    if (id.isNotBlank()) lines += "ID: $id\n$detail"
                }
                sections += if (lines.isEmpty()) "$label: không có dữ liệu trong 100 mục đầu tiên."
                    else "$label:\n" + lines.joinToString("\n\n")
            }
            Result(true, sections.joinToString("\n\n"))
        } catch (error: Exception) {
            Result(false, "Không đọc được danh sách tài nguyên Apple: " +
                (error.message ?: error.javaClass.simpleName) +
                if (sections.isNotEmpty()) "\n\n" + sections.joinToString("\n\n") else "")
        }
    }

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

        validateRelationships(credentials, request)

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

    /**
     * Validate the exact resource IDs before creating a profile. This prevents
     * accidental use of a macOS/tvOS App ID, development certificate in an
     * Ad Hoc/App Store profile, disabled device, or unrelated resource ID.
     */
    private fun validateRelationships(
        credentials: AppleConnectCredentialVault.Credentials,
        request: Request
    ) {
        val bundle = getResource(credentials,
            "/v1/bundleIds/${request.bundleIdResourceId}?fields%5BbundleIds%5D=identifier,name,platform")
        val bundleAttrs = bundle.optJSONObject("attributes") ?: JSONObject()
        val platform = bundleAttrs.optString("platform")
        require(platform.equals("IOS", true) || platform.equals("UNIVERSAL", true)) {
            "App ID đã chọn không phải App ID iOS (platform=$platform)."
        }
        val identifier = bundleAttrs.optString("identifier")
        require(identifier.isNotBlank() && identifier.contains('.')) {
            "Apple App ID không trả Bundle Identifier hợp lệ."
        }

        val expectedCertType = if (request.type == Type.DEVELOPMENT) {
            "IOS_DEVELOPMENT"
        } else {
            "IOS_DISTRIBUTION"
        }
        request.certificateResourceIds.distinct().forEach { id ->
            val cert = getResource(credentials,
                "/v1/certificates/$id?fields%5Bcertificates%5D=certificateType,expirationDate,displayName")
            val attrs = cert.optJSONObject("attributes") ?: JSONObject()
            val actual = attrs.optString("certificateType")
            require(actual == expectedCertType) {
                "Certificate $id có loại $actual; profile ${request.type.apiValue} cần $expectedCertType."
            }
            val expiry = attrs.optString("expirationDate")
            if (expiry.isNotBlank()) {
                val parsed = runCatching {
                    java.time.OffsetDateTime.parse(expiry).toInstant()
                }.getOrNull()
                require(parsed == null || parsed.isAfter(java.time.Instant.now())) {
                    "Certificate $id đã hết hạn."
                }
            }
        }

        request.deviceResourceIds.distinct().forEach { id ->
            val device = getResource(credentials,
                "/v1/devices/$id?fields%5Bdevices%5D=name,udid,platform,status")
            val attrs = device.optJSONObject("attributes") ?: JSONObject()
            val devicePlatform = attrs.optString("platform")
            val status = attrs.optString("status")
            require(devicePlatform.equals("IOS", true)) {
                "Thiết bị $id không phải thiết bị iOS (platform=$devicePlatform)."
            }
            require(status.equals("ENABLED", true)) {
                "Thiết bị $id không ở trạng thái ENABLED (status=$status)."
            }
        }
    }

    private fun getResource(
        credentials: AppleConnectCredentialVault.Credentials,
        path: String
    ): JSONObject {
        val connection = URL("https://api.appstoreconnect.apple.com$path")
            .openConnection() as HttpsURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 25000
            connection.setRequestProperty("Authorization", "Bearer " + AppleConnectJwt.token(credentials))
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching {
                    JSONObject(body).optJSONArray("errors")?.optJSONObject(0)
                        ?.optString("detail").orEmpty()
                }.getOrDefault("")
                throw IllegalStateException("Apple không đọc được tài nguyên (HTTP $code)." +
                    if (detail.isNotBlank()) " $detail" else "")
            }
            JSONObject(body).getJSONObject("data")
        } finally {
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
