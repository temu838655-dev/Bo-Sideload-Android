package com.bosideload.android

import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Runs the ordinary Lockdown discovery + Pair exchange on an already-open
 * Lockdown channel. The caller is responsible for obtaining the device's
 * explicit trust approval and for supplying its stable host SystemBUID.
 *
 * Private key material is persisted only after the device returns a successful
 * Pair response. Pass EncryptedPairRecordStore.save as the persistence callback.
 */
internal class AppleLockdownPairingClient(
    private val persistEncrypted: (PairRecord) -> Unit
) {
    @Throws(IOException::class)
    fun pair(
        channel: LockdownSessionChannel,
        deviceId: String,
        systemBuid: String,
        hostId: String
    ): PairRecord {
        require(deviceId.isNotBlank()) { "device ID must not be blank" }
        require(systemBuid.isNotBlank()) { "SystemBUID must not be blank" }
        require(hostId.isNotBlank()) { "HostID must not be blank" }

        // Read the UDID from Lockdown itself and ensure the usbmux serial used
        // as the device key refers to this same physical device.
        val udidResponse = channel.exchange(LockdownRequests.getValue(key = DEVICE_UDID_KEY))
        val udidSuccess = udidResponse as? LockdownResponseParser.Response.Success
            ?: throw IOException("iPhone refused the UniqueDeviceID request")
        if (udidSuccess.request != "GetValue") {
            throw IOException("Lockdown returned an unexpected response to UniqueDeviceID")
        }
        val lockdowmUdid = (udidSuccess.value as? String)?.trim()
            ?: throw IOException("Lockdown UniqueDeviceID was missing or had an unsupported type")
        if (!isPlausibleUdid(lockdowmUdid)) {
            throw IOException("Lockdown returned an invalid UniqueDeviceID")
        }
        if (!lockdowmUdid.equals(deviceId.trim(), ignoreCase = true)) {
            throw IOException("USB serial does not match the iPhone UDID reported by Lockdown; reconnect the correct device")
        }

        val publicKeyResponse = channel.exchange(LockdownRequests.getValue(key = DEVICE_PUBLIC_KEY_KEY))
        val publicKeySuccess = publicKeyResponse as? LockdownResponseParser.Response.Success
            ?: throw IOException("iPhone refused the DevicePublicKey request")
        if (publicKeySuccess.request != "GetValue") {
            throw IOException("Lockdown returned an unexpected response to DevicePublicKey")
        }
        val publicKeyPem = when (val value = publicKeySuccess.value) {
            is ByteArray -> value
            is String -> value.toByteArray(StandardCharsets.US_ASCII)
            else -> throw IOException("Lockdown DevicePublicKey was missing or had an unsupported type")
        }
        if (publicKeyPem.isEmpty() || publicKeyPem.size > MAX_DEVICE_PUBLIC_KEY_BYTES) {
            throw IOException("Lockdown DevicePublicKey is empty or exceeds the size limit")
        }

        val pending = try {
            AppleLockdownPairing.prepare(
                deviceId = deviceId,
                devicePublicKeyPem = publicKeyPem,
                systemBuid = systemBuid,
                hostId = hostId
            )
        } catch (error: Exception) {
            throw IOException("Could not prepare Apple pairing certificates", error)
        }

        val pairResponse = channel.exchange(pending.requestPayload)
        return try {
            AppleLockdownPairing.complete(pending, pairResponse, persistEncrypted)
        } catch (error: Exception) {
            throw IOException("iPhone did not complete the Lockdown Pair exchange", error)
        }
    }

    internal fun isPlausibleUdid(value: String): Boolean =
        value.length in 16..64 && value.all { it.isLetterOrDigit() || it == '-' }

    private companion object {
        const val DEVICE_UDID_KEY = "UniqueDeviceID"
        const val DEVICE_PUBLIC_KEY_KEY = "DevicePublicKey"
        const val MAX_DEVICE_PUBLIC_KEY_BYTES = 64 * 1024
    }
}
