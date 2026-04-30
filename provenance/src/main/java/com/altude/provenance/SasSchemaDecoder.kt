package com.altude.provenance

import android.util.Base64

/**
 * SAS schema decoder moved out of ProvenanceManager into a dedicated helper.
 * This mirrors the prior in-file implementation but is reusable as a utility.
 */
internal data class SchemaField(val name: String, val type: String)

internal data class DecodedSasSchemaFixed(
    val discriminator: Int,
    val authority: ByteArray,
    val schemaName: String,
    val layout: ByteArray,
    val fields: List<SchemaField>
)

private fun readIntLE(bytes: ByteArray, offset: Int): Int {
    require(offset + 4 <= bytes.size) { "readIntLE out of bounds" }
    return (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}

internal fun decodeSasSchemaFixed(base64: String): DecodedSasSchemaFixed {
    val raw = Base64.decode(base64, Base64.DEFAULT)
    var i = 0

    require(i + 1 <= raw.size) { "unexpected EOF while reading discriminator" }
    val discriminator = raw[i++].toInt() and 0xFF

    require(i + 32 <= raw.size) { "unexpected EOF while reading authority" }
    val authority = raw.copyOfRange(i, i + 32)
    i += 32

    require(i + 4 <= raw.size) { "unexpected EOF while reading schemaNameLen" }
    val schemaNameLen = readIntLE(raw, i)
    i += 4
    require(schemaNameLen >= 0 && i + schemaNameLen <= raw.size) { "unexpected EOF while reading schemaName" }
    val schemaName = String(raw.copyOfRange(i, i + schemaNameLen), Charsets.UTF_8)
    // Match the original JS implementation: advance past the schema name and
    // an extra 4 bytes (legacy layout expectation).
    i += schemaNameLen

    val schemaDescLen = readIntLE(raw, i)
    i += 4

    val schemaDesc = String(raw.copyOfRange(i, i + schemaDescLen), Charsets.UTF_8)
    i += schemaDescLen
    require(i + 4 <= raw.size) { "unexpected EOF while reading layoutLen" }
    val layoutLen = readIntLE(raw, i)
    i += 4
    require(layoutLen >= 0 && i + layoutLen <= raw.size) { "unexpected EOF while reading layout" }
    val layout = raw.copyOfRange(i, i + layoutLen)
    i += layoutLen

    require(i + 4 <= raw.size) { "unexpected EOF while reading fieldBlobLen" }
    val fieldBlobLen = readIntLE(raw, i)
    i += 4
    require(fieldBlobLen >= 0 && i + fieldBlobLen <= raw.size) { "unexpected EOF while reading fieldBlob" }
    val fieldBlob = raw.copyOfRange(i, i + fieldBlobLen)
    i += fieldBlobLen

    val fields = parseFieldsBlob(fieldBlob, layout)

    return DecodedSasSchemaFixed(
        discriminator = discriminator,
        authority = authority,
        schemaName = schemaName,
        layout = layout,
        fields = fields
    )
}

private fun parseFieldsBlob(blob: ByteArray, layout: ByteArray): List<SchemaField> {
    var i = 0
    val fields = mutableListOf<SchemaField>()

    while (i < blob.size) {
        require(i + 4 <= blob.size) { "unexpected EOF while reading field name length" }
        val nameLen = readIntLE(blob, i)
        i += 4
        require(nameLen >= 0 && i + nameLen <= blob.size) { "unexpected EOF while reading field name" }
        val name = String(blob.copyOfRange(i, i + nameLen), Charsets.UTF_8)
        i += nameLen

        val typeByte = (layout.getOrNull(fields.size)?.toInt() ?: 0) and 0xFF
        fields.add(SchemaField(name = name, type = decodeType(typeByte)))
    }

    return fields
}

private fun decodeType(byte: Int): String {
    return when (byte) {
        0 -> "u8"
        1 -> "u16"
        2 -> "u32"
        3 -> "u64"
        4 -> "u128"

        5 -> "i8"
        6 -> "i16"
        7 -> "i32"
        8 -> "i64"
        9 -> "i128"

        10 -> "bool"
        11 -> "char"

        12 -> "string"

        13 -> "vec<u8>"
        14 -> "vec<u16>"
        15 -> "vec<u32>"
        16 -> "vec<u64>"
        17 -> "vec<u128>"

        18 -> "vec<i8>"
        19 -> "vec<i16>"
        20 -> "vec<i32>"
        21 -> "vec<i64>"
        22 -> "vec<i128>"

        23 -> "vec<bool>"
        24 -> "vec<char>"
        25 -> "vec<string>"

        else -> "unknown($byte)"
    }
}

/**
 * Serialize attestation data using the schema layout.
 * - `data` is a Map fieldName -> value (Any?).
 * - Null values: fallback defaults:
 *    - numbers -> 0
 *    - strings -> empty string
 *    - vecs -> empty vec
 *    - bool -> false
 *
 * Supported types: u8,u16,u32,u64,i8,i16,i32,i64,bool,string, vec<u8>, vec<string>
 */
internal fun serializeAttestationData(schema: DecodedSasSchemaFixed, data: Map<String, Any?>): ByteArray {
    val out = ByteArrayOutput()

    for ((index, field) in schema.fields.withIndex()) {
        val value = data[field.name]
        // layout uses decodeType mapping ordering; get raw type byte from layout if available
        val t = (schema.layout.getOrNull(index)?.toInt() ?: 0) and 0xFF
        when (t) {
            0 -> { // u8
                val v = toNumber(value).toInt() and 0xFF
                out.writeByte(v)
            }
            1 -> { // u16
                val vv = toNumber(value).toInt() and 0xFFFF
                out.writeU16LE(vv)
            }
            2 -> { // u32
                val vv = toNumber(value).toLong() and 0xFFFFFFFFL
                out.writeU32LE(vv.toInt())
            }
            3 -> { // u64
                val vv = toNumber(value).toLong()
                out.writeU64LE(vv)
            }
            5 -> { // i8
                val vv = toNumber(value).toInt()
                out.writeByte(vv and 0xFF)
            }
            6 -> { // i16
                val vv = toNumber(value).toInt()
                out.writeU16LE(vv and 0xFFFF)
            }
            7 -> { // i32
                val vv = toNumber(value).toInt()
                out.writeU32LE(vv)
            }
            8 -> { // i64
                val vv = toNumber(value).toLong()
                out.writeI64LE(vv)
            }
            10 -> { // bool
                val b = when (value) {
                    is Boolean -> value
                    is Number -> value.toInt() != 0
                    is String -> value.equals("true", true)
                    null -> false
                    else -> false
                }
                out.writeByte(if (b) 1 else 0)
            }
            12 -> { // string (Borsh-style: u32 len LE + UTF-8 bytes)
                val s = value?.toString() ?: ""
                val bytes = s.toByteArray(Charsets.UTF_8)
                out.writeU32LE(bytes.size)
                out.write(bytes)
            }
            13 -> { // vec<u8> - treat value as ByteArray or base64 string or List<Number>
                when (value) {
                    is ByteArray -> {
                        out.writeU32LE(value.size)
                        out.write(value)
                    }
                    is String -> {
                        val b = try {
                            Base64.decode(value, Base64.DEFAULT)
                        } catch (e: Exception) {
                            value.toByteArray(Charsets.UTF_8)
                        }
                        out.writeU32LE(b.size)
                        out.write(b)
                    }
                    is List<*> -> {
                        val bytes = value.map { (it as Number).toInt().toByte() }.toByteArray()
                        out.writeU32LE(bytes.size)
                        out.write(bytes)
                    }
                    null -> {
                        out.writeU32LE(0)
                    }
                    else -> {
                        val b = value.toString().toByteArray(Charsets.UTF_8)
                        out.writeU32LE(b.size)
                        out.write(b)
                    }
                }
            }
            25 -> { // vec<string>
                if (value == null) {
                    out.writeU32LE(0)
                } else if (value is List<*>) {
                    out.writeU32LE(value.size)
                    for (elem in value) {
                        val s = elem?.toString() ?: ""
                        val bs = s.toByteArray(Charsets.UTF_8)
                        out.writeU32LE(bs.size)
                        out.write(bs)
                    }
                } else {
                    val s = value.toString()
                    out.writeU32LE(1)
                    val bs = s.toByteArray(Charsets.UTF_8)
                    out.writeU32LE(bs.size)
                    out.write(bs)
                }
            }
            else -> {
                val s = value?.toString() ?: ""
                val bs = s.toByteArray(Charsets.UTF_8)
                out.writeU32LE(bs.size)
                out.write(bs)
            }
        }
    }

    return out.toByteArray()
}

/** Convert any numeric-like value to Long */
private fun toNumber(value: Any?): Long {
    return when (value) {
        null -> 0L
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        is Boolean -> if (value) 1L else 0L
        else -> 0L
    }
}

/** small dynamic byte collector with LE writes */
private class ByteArrayOutput {
    private var buf = ByteArray(256)
    private var pos = 0

    fun write(b: Int) {
        ensure(1)
        buf[pos++] = (b and 0xFF).toByte()
    }

    fun write(bytes: ByteArray) {
        ensure(bytes.size)
        System.arraycopy(bytes, 0, buf, pos, bytes.size)
        pos += bytes.size
    }

    fun writeByte(b: Int) = write(b)

    fun writeU16LE(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
    }

    fun writeU32LE(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr 24) and 0xFF)
    }

    fun writeU64LE(v: Long) {
        writeU32LE((v and 0xFFFFFFFFL).toInt())
        writeU32LE(((v ushr 32) and 0xFFFFFFFFL).toInt())
    }

    fun writeI64LE(v: Long) = writeU64LE(v)

    fun toByteArray(): ByteArray = buf.copyOf(pos)

    private fun ensure(n: Int) {
        if (pos + n > buf.size) {
            var newCap = buf.size * 2
            while (pos + n > newCap) newCap *= 2
            buf = buf.copyOf(newCap)
        }
    }
}

/** Example conversion from ImageHashPayload to Map */
fun buildPayloadMap(
    type: String,
    hash: String,
    assetHash: String? = null,
    attester: String? = null,
    certificateHash: String? = null,
    mime: String? = null,
    name: String? = null,
    timestamp: Long = 0L,
    expireAt: Long = 0L,
    recipient: String? = null
): Map<String, Any?> {
    return mapOf(
        "type" to type,
        "hash" to hash,
        "asset_hash" to (assetHash ?: ""),
        "attester" to (attester ?: ""),
        "certificate_hash" to (certificateHash ?: ""),
        "mime" to (mime ?: ""),
        "name" to (name ?: ""),
        "timestamp" to timestamp,
        "expireAt" to expireAt,
        "recipient" to (recipient ?: "")
    )
}

