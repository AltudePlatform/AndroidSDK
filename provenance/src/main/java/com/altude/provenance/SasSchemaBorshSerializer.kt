package com.altude.provenance

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Serializer/deserializer for attestation data according to SAS compact layout.
 * Operates on the decoded schema produced by `decodeSasSchemaFixed`.
 */
internal object SasSchemaBorshSerializer {

    /**
     * Serialize attestation data map into bytes according to schema layout.
     * Fields are serialized in the order present in schema.fields.
     */
    fun serializeAttestationData(schema: DecodedSasSchemaFixed, data: Map<String, Any?>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((index, field) in schema.fields.withIndex()) {
            val value = data[field.name]
            val typeByte = (schema.layout.getOrNull(index)?.toInt() ?: 12) and 0xFF
            writeValue(out, typeByte, value)
        }
        return out.toByteArray()
    }

    /**
     * Deserialize attestation bytes into a map according to schema.
     */
    fun deserializeAttestationData(schema: DecodedSasSchemaFixed, bytes: ByteArray): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        var offset = 0
        for ((index, field) in schema.fields.withIndex()) {
            val typeByte = (schema.layout.getOrNull(index)?.toInt() ?: 12) and 0xFF
            val (value, read) = readValue(bytes, offset, typeByte)
            out[field.name] = value
            offset += read
        }
        return out
    }

    private fun writeValue(out: ByteArrayOutputStream, typeByte: Int, value: Any?) {
        when (typeByte) {
            0 -> writeU8(out, (value as? Number)?.toInt() ?: 0)
            1 -> writeU16(out, (value as? Number)?.toInt() ?: 0)
            2 -> writeU32(out, (value as? Number)?.toInt() ?: 0)
            3 -> writeU64(out, (value as? Number)?.toLong() ?: 0L)
            4 -> writeU128(out, toBigInteger(value))

            5 -> writeI8(out, (value as? Number)?.toInt() ?: 0)
            6 -> writeI16(out, (value as? Number)?.toInt() ?: 0)
            7 -> writeI32(out, (value as? Number)?.toInt() ?: 0)
            8 -> writeI64(out, (value as? Number)?.toLong() ?: 0L)
            9 -> writeI128(out, toBigInteger(value))

            10 -> writeBool(out, (value as? Boolean) ?: false)
            11 -> writeChar(out, value)
            12 -> writeString(out, value as? String ?: "")

            // vec<...>
            13 -> writeVecU8(out, value)
            14 -> writeVecU16(out, value)
            15 -> writeVecU32(out, value)
            16 -> writeVecU64(out, value)
            17 -> writeVecU128(out, value)

            18 -> writeVecI8(out, value)
            19 -> writeVecI16(out, value)
            20 -> writeVecI32(out, value)
            21 -> writeVecI64(out, value)
            22 -> writeVecI128(out, value)

            23 -> writeVecBool(out, value)
            24 -> writeVecString(out, value)
            25 -> writeVecChar(out, value)

            else -> throw IllegalArgumentException("Unsupported schema type: $typeByte")
        }
    }

    // ---------- write primitives ----------
    private fun writeU8(out: ByteArrayOutputStream, v: Int) {
        out.write((v and 0xFF))
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Int) {
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
    }

    private fun writeU64(out: ByteArrayOutputStream, v: Long) {
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
    }

    private fun writeU128(out: ByteArrayOutputStream, v: BigInteger) {
        val b = toFixedLE(v, 16)
        out.write(b)
    }

    private fun writeI8(out: ByteArrayOutputStream, v: Int) {
        out.write((v and 0xFF))
    }

    private fun writeI16(out: ByteArrayOutputStream, v: Int) {
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
    }

    private fun writeI32(out: ByteArrayOutputStream, v: Int) {
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
    }

    private fun writeI64(out: ByteArrayOutputStream, v: Long) {
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
    }

    private fun writeI128(out: ByteArrayOutputStream, v: BigInteger) {
        writeU128(out, v)
    }

    private fun writeBool(out: ByteArrayOutputStream, v: Boolean) {
        out.write(if (v) 1 else 0)
    }

    private fun writeChar(out: ByteArrayOutputStream, v: Any?) {
        val bytes = (v as? String)?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        val padded = ByteArray(4)
        for (i in 0 until minOf(4, bytes.size)) padded[i] = bytes[i]
        out.write(padded)
    }

    private fun writeString(out: ByteArrayOutputStream, v: String) {
        val b = v.toByteArray(StandardCharsets.UTF_8)
        writeU32(out, b.size)
        out.write(b)
    }

    // ---------- write vectors ----------
    private fun writeVecU8(out: ByteArrayOutputStream, v: Any?) {
        val arr = toByteArray(v)
        writeU32(out, arr.size)
        out.write(arr)
    }

    private fun writeVecU16(out: ByteArrayOutputStream, v: Any?) {
        val list = toNumberList(v)
        writeU32(out, list.size)
        for (n in list) writeU16(out, n)
    }

    private fun writeVecU32(out: ByteArrayOutputStream, v: Any?) {
        val list = toNumberList(v)
        writeU32(out, list.size)
        for (n in list) writeU32(out, n)
    }

    private fun writeVecU64(out: ByteArrayOutputStream, v: Any?) {
        val list = toLongList(v)
        writeU32(out, list.size)
        for (n in list) writeU64(out, n)
    }

    private fun writeVecU128(out: ByteArrayOutputStream, v: Any?) {
        val list = toBigIntegerList(v)
        writeU32(out, list.size)
        for (n in list) writeU128(out, n)
    }

    private fun writeVecI8(out: ByteArrayOutputStream, v: Any?) {
        val list = toNumberList(v)
        writeU32(out, list.size)
        for (n in list) writeI8(out, n)
    }

    private fun writeVecI16(out: ByteArrayOutputStream, v: Any?) {
        val list = toNumberList(v)
        writeU32(out, list.size)
        for (n in list) writeI16(out, n)
    }

    private fun writeVecI32(out: ByteArrayOutputStream, v: Any?) {
        val list = toNumberList(v)
        writeU32(out, list.size)
        for (n in list) writeI32(out, n)
    }

    private fun writeVecI64(out: ByteArrayOutputStream, v: Any?) {
        val list = toLongList(v)
        writeU32(out, list.size)
        for (n in list) writeI64(out, n)
    }

    private fun writeVecI128(out: ByteArrayOutputStream, v: Any?) {
        val list = toBigIntegerList(v)
        writeU32(out, list.size)
        for (n in list) writeI128(out, n)
    }

    private fun writeVecBool(out: ByteArrayOutputStream, v: Any?) {
        val list = toBooleanList(v)
        writeU32(out, list.size)
        for (b in list) writeBool(out, b)
    }

    private fun writeVecString(out: ByteArrayOutputStream, v: Any?) {
        val list = toStringList(v)
        writeU32(out, list.size)
        for (s in list) writeString(out, s)
    }

    private fun writeVecChar(out: ByteArrayOutputStream, v: Any?) {
        val list = toStringList(v)
        writeU32(out, list.size)
        for (s in list) writeChar(out, s)
    }

    // ---------- readValue (returns Pair<value, bytesRead>) ----------
    private fun readValue(bytes: ByteArray, offset: Int, typeByte: Int): Pair<Any?, Int> {
        var pos = offset
        fun read(n: Int): ByteArray { val r = bytes.copyOfRange(pos, pos + n); pos += n; return r }
        return when (typeByte) {
            0 -> Pair(bytes[pos++].toInt() and 0xFF, 1)
            1 -> Pair(ByteBuffer.wrap(read(2)).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF, pos - offset)
            2 -> Pair(ByteBuffer.wrap(read(4)).order(ByteOrder.LITTLE_ENDIAN).int, pos - offset)
            3 -> Pair(ByteBuffer.wrap(read(8)).order(ByteOrder.LITTLE_ENDIAN).long, pos - offset)
            4 -> Pair(BigInteger(1, read(16).reversedArray()), pos - offset)

            5 -> Pair((bytes[pos++].toInt()), 1)
            6 -> Pair(ByteBuffer.wrap(read(2)).order(ByteOrder.LITTLE_ENDIAN).short.toInt(), pos - offset)
            7 -> Pair(ByteBuffer.wrap(read(4)).order(ByteOrder.LITTLE_ENDIAN).int, pos - offset)
            8 -> Pair(ByteBuffer.wrap(read(8)).order(ByteOrder.LITTLE_ENDIAN).long, pos - offset)
            9 -> Pair(BigInteger(1, read(16).reversedArray()), pos - offset)

            10 -> Pair(bytes[pos++].toInt() != 0, 1)
            11 -> Pair(String(read(4), StandardCharsets.UTF_8).trimEnd('\u0000'), pos - offset)
            12 -> {
                val len = ByteBuffer.wrap(read(4)).order(ByteOrder.LITTLE_ENDIAN).int
                val s = String(read(len), StandardCharsets.UTF_8)
                Pair(s, pos - offset)
            }

            // vec<T>
            in 13..17 -> {
                val vecLen = ByteBuffer.wrap(read(4)).order(ByteOrder.LITTLE_ENDIAN).int
                val list = mutableListOf<Any?>()
                for (i in 0 until vecLen) {
                    val (v, r) = when (typeByte) {
                        13 -> Pair(bytes[pos++].toInt() and 0xFF, 1)
                        14 -> Pair(ByteBuffer.wrap(read(2)).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF, 2)
                        15 -> Pair(ByteBuffer.wrap(read(4)).order(ByteOrder.LITTLE_ENDIAN).int, 4)
                        16 -> Pair(ByteBuffer.wrap(read(8)).order(ByteOrder.LITTLE_ENDIAN).long, 8)
                        17 -> Pair(BigInteger(1, read(16).reversedArray()), 16)
                        else -> throw IllegalStateException()
                    }
                    list.add(v)
                }
                Pair(list, pos - offset)
            }

            in 18..22 -> {
                val vecLen = ByteBuffer.wrap(read(4)).order(ByteOrder.LITTLE_ENDIAN).int
                val list = mutableListOf<Any?>()
                for (i in 0 until vecLen) {
                    val v = when (typeByte) {
                        18 -> bytes[pos++].toInt()
                        19 -> ByteBuffer.wrap(read(2)).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                        20 -> ByteBuffer.wrap(read(4)).order(ByteOrder.LITTLE_ENDIAN).int
                        21 -> ByteBuffer.wrap(read(8)).order(ByteOrder.LITTLE_ENDIAN).long
                        22 -> BigInteger(1, read(16).reversedArray())
                        else -> null
                    }
                    list.add(v)
                }
                Pair(list, pos - offset)
            }

            23 -> {
                val vecLen = ByteBuffer.wrap(read(4)).order(ByteOrder.LITTLE_ENDIAN).int
                val list = MutableList(vecLen) { bytes[pos++].toInt() != 0 }
                Pair(list, pos - offset)
            }
            24 -> {
                val vecLen = ByteBuffer.wrap(read(4)).order(ByteOrder.LITTLE_ENDIAN).int
                val list = mutableListOf<String>()
                for (i in 0 until vecLen) {
                    val l = ByteBuffer.wrap(read(4)).order(ByteOrder.LITTLE_ENDIAN).int
                    val s = String(read(l), StandardCharsets.UTF_8)
                    list.add(s)
                }
                Pair(list, pos - offset)
            }
            25 -> {
                val vecLen = ByteBuffer.wrap(read(4)).order(ByteOrder.LITTLE_ENDIAN).int
                val list = mutableListOf<String>()
                for (i in 0 until vecLen) {
                    val s = String(read(4), StandardCharsets.UTF_8).trimEnd('\u0000')
                    list.add(s)
                }
                Pair(list, pos - offset)
            }

            else -> throw IllegalArgumentException("Unsupported typeByte $typeByte at offset $offset")
        }
    }

    // ---------- helpers ----------
    private fun toBigInteger(value: Any?): BigInteger {
        return when (value) {
            is BigInteger -> value
            is Number -> BigInteger.valueOf(value.toLong())
            is String -> BigInteger(value)
            null -> BigInteger.ZERO
            else -> throw IllegalArgumentException("Cannot convert $value to BigInteger")
        }
    }

    private fun toFixedLE(v: BigInteger, size: Int): ByteArray {
        val raw = v.toByteArray() // big-endian two's complement
        // Convert to unsigned little-endian of fixed size
        val unsigned = if (raw.firstOrNull() == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        val le = unsigned.reversedArray()
        return if (le.size == size) le else if (le.size < size) le + ByteArray(size - le.size) else le.copyOf(size)
    }

    private fun toByteArray(v: Any?): ByteArray {
        return when (v) {
            is ByteArray -> v
            is List<*> -> v.map { (it as Number).toByte() }.toByteArray()
            is String -> v.toByteArray(StandardCharsets.UTF_8)
            null -> ByteArray(0)
            else -> throw IllegalArgumentException("Cannot convert to byte[]: $v")
        }
    }

    private fun toNumberList(v: Any?): List<Int> {
        return when (v) {
            is List<*> -> v.map { (it as Number).toInt() }
            is IntArray -> v.toList()
            is LongArray -> v.map { it.toInt() }
            null -> emptyList()
            else -> throw IllegalArgumentException("Cannot convert to Int list: $v")
        }
    }

    private fun toLongList(v: Any?): List<Long> {
        return when (v) {
            is List<*> -> v.map { (it as Number).toLong() }
            is LongArray -> v.toList()
            is IntArray -> v.map { it.toLong() }
            null -> emptyList()
            else -> throw IllegalArgumentException("Cannot convert to Long list: $v")
        }
    }

    private fun toBigIntegerList(v: Any?): List<BigInteger> {
        return when (v) {
            is List<*> -> v.map { toBigInteger(it!!) }
            null -> emptyList()
            else -> throw IllegalArgumentException("Cannot convert to BigInteger list: $v")
        }
    }

    private fun toBooleanList(v: Any?): List<Boolean> {
        return when (v) {
            is List<*> -> v.map { it as Boolean }
            null -> emptyList()
            else -> throw IllegalArgumentException("Cannot convert to Boolean list: $v")
        }
    }

    private fun toStringList(v: Any?): List<String> {
        return when (v) {
            is List<*> -> v.map { it.toString() }
            null -> emptyList()
            else -> throw IllegalArgumentException("Cannot convert to String list: $v")
        }
    }

}

