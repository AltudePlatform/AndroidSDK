package com.altude.core.helper

class BitWriter {
    private val bits = mutableListOf<Boolean>()

    fun write(data: ByteArray) {
        for (byte in data) {
            for (i in 7 downTo 0) {
                bits.add((byte.toInt() shr i) and 1 == 1)
            }
        }
    }

    fun write(data: ByteArray, bitCount: Int) {
        var count = 0
        outer@ for (byte in data) {
            for (i in 7 downTo 0) {
                if (count >= bitCount) break@outer
                bits.add((byte.toInt() shr i) and 1 == 1)
                count++
            }
        }
    }

    fun toIntegers(): IntArray {
        val result = mutableListOf<Int>()
        val totalGroups = bits.size / 11

        for (i in 0 until totalGroups) {
            var value = 0
            for (j in 0 until 11) {
                if (bits[i * 11 + j]) {
                    value = value or (1 shl (10 - j))
                }
            }
            result.add(value)
        }

        return result.toIntArray()
    }
}
