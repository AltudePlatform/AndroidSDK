package com.altude.core.Programs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class AttestationProgramEncodingTest {

    private fun u32le(i: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(i).array()

    private fun str(s: String): ByteArray {
        val b = s.toByteArray(StandardCharsets.UTF_8)
        return u32le(b.size) + b
    }

    @Test
    fun `string helpers encode UTF-8 strings with little-endian length prefixes`() {
        val name = "my_schema"
        val desc = "desc"

        val expectedName = byteArrayOf(
            9, 0, 0, 0,
            'm'.code.toByte(),
            'y'.code.toByte(),
            '_'.code.toByte(),
            's'.code.toByte(),
            'c'.code.toByte(),
            'h'.code.toByte(),
            'e'.code.toByte(),
            'm'.code.toByte(),
            'a'.code.toByte()
        )
        val expectedDesc = byteArrayOf(
            4, 0, 0, 0,
            'd'.code.toByte(),
            'e'.code.toByte(),
            's'.code.toByte(),
            'c'.code.toByte()
        )

        assertArrayEquals(expectedName, str(name))
        assertArrayEquals(expectedDesc, str(desc))

        val combined = str(name) + str(desc)
        val expectedCombined = expectedName + expectedDesc

        assertArrayEquals(expectedCombined, combined)
        assertEquals(9, ByteBuffer.wrap(combined, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(4, ByteBuffer.wrap(combined, expectedName.size, 4).order(ByteOrder.LITTLE_ENDIAN).int)
    }
}

