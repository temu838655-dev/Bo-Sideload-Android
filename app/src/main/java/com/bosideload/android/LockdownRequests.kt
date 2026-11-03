package com.bosideload.android

/**
 * Builders for non-mutating Lockdown discovery requests and the authenticated
 * StartSession request. Pairing material is deliberately not generated here.
 */
internal object LockdownRequests {
    private const val LABEL = "Bo-Sideload Android"

    fun queryType(): ByteArray = request("QueryType")

    fun getValue(domain: String? = null, key: String? = null): ByteArray {
        require(domain == null || domain.isNotBlank()) { "domain must not be blank" }
        require(key == null || key.isNotBlank()) { "key must not be blank" }
        return request("GetValue", buildMap {
            if (domain != null) put("Domain", domain)
            if (key != null) put("Key", key)
        })
    }

    /**
     * Builds the user-authorized Apple Pair request. Private keys are never
     * included in the PairRecord sent over Lockdown.
     */
    fun pair(
        deviceCertificate: ByteArray,
        hostCertificate: ByteArray,
        rootCertificate: ByteArray,
        hostId: String,
        systemBuid: String
    ): ByteArray {
        require(deviceCertificate.isNotEmpty()) { "DeviceCertificate must not be empty" }
        require(hostCertificate.isNotEmpty()) { "HostCertificate must not be empty" }
        require(rootCertificate.isNotEmpty()) { "RootCertificate must not be empty" }
        require(hostId.isNotBlank()) { "HostID must not be blank" }
        require(systemBuid.isNotBlank()) { "SystemBUID must not be blank" }
        return request("Pair", linkedMapOf(
            "ProtocolVersion" to "2",
            "PairRecord" to linkedMapOf(
                "DeviceCertificate" to deviceCertificate,
                "HostCertificate" to hostCertificate,
                "HostID" to hostId,
                "RootCertificate" to rootCertificate,
                "SystemBUID" to systemBuid
            )
        ))
    }

    fun startSession(hostId: String, systemBuid: String): ByteArray {
        require(hostId.isNotBlank()) { "HostID must not be blank" }
        require(systemBuid.isNotBlank()) { "SystemBUID must not be blank" }
        return request("StartSession", linkedMapOf(
            "HostID" to hostId,
            "SystemBUID" to systemBuid
        ))
    }

    private fun request(name: String, fields: Map<String, Any?> = emptyMap()): ByteArray {
        require(name.isNotBlank()) { "request name must not be blank" }
        val message = linkedMapOf<String, Any?>(
            "Label" to LABEL,
            "Request" to name
        )
        message.putAll(fields)
        return LockdownPlistCodec.encode(message)
    }
}
