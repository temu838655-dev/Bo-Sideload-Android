package com.bosideload.android

import java.io.*

internal data class PairRecord(
    val deviceId: String,
    val hostId: String,
    val systemBuid: String,
    val hostCertificate: ByteArray,
    val hostKeyMaterial: ByteArray,
    val deviceCertificate: ByteArray,
    val rootCertificate: ByteArray,
    val escrowBag: ByteArray? = null
) {
    fun validate() {
        require(deviceId.isNotBlank() && hostId.isNotBlank() && systemBuid.isNotBlank())
        listOf(hostCertificate, hostKeyMaterial, deviceCertificate, rootCertificate).forEach {
            require(it.isNotEmpty() && it.size <= PairRecordCodec.MAX_FIELD_BYTES)
        }
        require(escrowBag == null || escrowBag.size <= PairRecordCodec.MAX_FIELD_BYTES)
    }
}

internal object PairRecordCodec {
    private const val MAGIC = 0x42505231
    private const val VERSION = 1
    const val MAX_FIELD_BYTES = 1024 * 1024
    const val MAX_RECORD_BYTES = 4 * 1024 * 1024

    fun encode(record: PairRecord): ByteArray {
        record.validate()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(record.deviceId)
            out.writeUTF(record.hostId)
            out.writeUTF(record.systemBuid)
            writeBytes(out, record.hostCertificate)
            writeBytes(out, record.hostKeyMaterial)
            writeBytes(out, record.deviceCertificate)
            writeBytes(out, record.rootCertificate)
            out.writeBoolean(record.escrowBag != null)
            record.escrowBag?.let { writeBytes(out, it) }
        }
        return bytes.toByteArray().also {
            require(it.size <= MAX_RECORD_BYTES) { "record exceeds size limit" }
        }
    }

    fun decode(bytes: ByteArray): PairRecord {
        require(bytes.isNotEmpty() && bytes.size <= MAX_RECORD_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val record = try {
            require(input.readInt() == MAGIC) { "invalid record header" }
            require(input.readInt() == VERSION) { "unsupported record version" }
            PairRecord(
                input.readUTF(),
                input.readUTF(),
                input.readUTF(),
                readBytes(input),
                readBytes(input),
                readBytes(input),
                readBytes(input),
                if (input.readBoolean()) readBytes(input) else null
            )
        } catch (error: IOException) {
            throw IllegalArgumentException("truncated record", error)
        }
        require(input.available() == 0) { "unexpected trailing record data" }
        record.validate()
        return record
    }

    private fun writeBytes(out: DataOutputStream, value: ByteArray) {
        require(value.size in 1..MAX_FIELD_BYTES)
        out.writeInt(value.size)
        out.write(value)
    }

    private fun readBytes(input: DataInputStream): ByteArray {
        val size = input.readInt()
        require(size in 1..MAX_FIELD_BYTES) { "invalid field length" }
        return ByteArray(size).also { input.readFully(it) }
    }
}
