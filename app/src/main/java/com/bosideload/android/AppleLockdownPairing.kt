package com.bosideload.android

import java.util.UUID

/**
 * Coordinates cryptographic preparation and completion of Apple's ordinary
 * Lockdown Pair exchange. The caller sends requestPayload through an active
 * Lockdown channel and allows iOS to display and handle its trust prompt.
 *
 * This class does not open USB, perform TLS, or claim pairing until a successful
 * Pair response has been received.
 */
internal object AppleLockdownPairing {
    data class Pending(
        val deviceId: String,
        val hostId: String,
        val systemBuid: String,
        val material: ApplePairingMaterialGenerator.Material,
        val requestPayload: ByteArray
    )

    fun prepare(
        deviceId: String,
        devicePublicKeyPem: ByteArray,
        systemBuid: String = UUID.randomUUID().toString().uppercase(),
        hostId: String = UUID.randomUUID().toString().uppercase()
    ): Pending {
        require(deviceId.isNotBlank()) { "device ID must not be blank" }
        require(systemBuid.isNotBlank()) { "SystemBUID must not be blank" }
        require(hostId.isNotBlank()) { "HostID must not be blank" }

        val material = ApplePairingMaterialGenerator.generate(devicePublicKeyPem)
        val request = LockdownRequests.pair(
            deviceCertificate = material.deviceCertificatePem,
            hostCertificate = material.hostCertificatePem,
            rootCertificate = material.rootCertificatePem,
            hostId = hostId,
            systemBuid = systemBuid
        )
        return Pending(deviceId, hostId, systemBuid, material, request)
    }

    /**
     * Converts only a successful Pair response into a persistable record.
     * The persistence callback should use EncryptedPairRecordStore.
     */
    fun complete(
        pending: Pending,
        response: LockdownResponseParser.Response,
        persistEncrypted: (PairRecord) -> Unit
    ): PairRecord {
        val success = response as? LockdownResponseParser.Response.Success
            ?: throw IllegalStateException("Apple rejected pairing or returned an invalid response")
        check(success.request == "Pair") { "expected a successful Pair response" }

        val record = PairRecord(
            deviceId = pending.deviceId,
            hostId = pending.hostId,
            systemBuid = pending.systemBuid,
            hostCertificate = pending.material.hostCertificatePem,
            hostKeyMaterial = pending.material.hostPrivateKeyPem,
            deviceCertificate = pending.material.deviceCertificatePem,
            rootCertificate = pending.material.rootCertificatePem,
            rootKeyMaterial = pending.material.rootPrivateKeyPem,
            escrowBag = success.escrowBag
        )
        record.validate()
        persistEncrypted(record)
        return record
    }
}
