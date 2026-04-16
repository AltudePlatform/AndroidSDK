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
    fun `SIMPLE_STRUCT_NAME_DESC_FIELDS_ID1_TYPED encodes name,desc,fields then id`() {
        // Arrange
        AttestationProgram.schemaEncodingMode =
            AttestationProgram.SchemaEncodingMode.SIMPLE_STRUCT_NAME_DESC_FIELDS_ID1_TYPED

        val name = "my_schema"
        val desc = "desc"
        val fields = listOf(
            AttestationProgram.FieldDefinition("f1", AttestationProgram.SchemaFieldType.STRING),
            AttestationProgram.FieldDefinition("f2", AttestationProgram.SchemaFieldType.U8)
        )

        // Act: call private buildInstructionData via createSchemaWithFields data preview path
        // We can't access buildInstructionData directly, so we reproduce expected bytes and
        // verify the low-level layout by calling createSchemaWithFields and checking its `data`.
        // To do that without Solana dependencies here, we validate the encoder helpers instead.

        // Expected encoding:
        // name: String
        // description: String
        // fields: Vec<FieldDefinition>
        // instruction_id: u8 (1)
        val expected = ByteArray(0)
            .plus(str(name))
            .plus(str(desc))
            .plus(u32le(fields.size))
            .plus(str("f1"))
            .plus(byteArrayOf(AttestationProgram.SchemaFieldType.STRING.value.toByte()))
            .plus(str("f2"))
            .plus(byteArrayOf(AttestationProgram.SchemaFieldType.U8.value.toByte()))
            .plus(byteArrayOf(1))

        // Assert
        // Build the actual bytes by reusing AttestationProgram's public API pieces.
        // There is no direct public encoder method, so we use reflection only within tests.
        val buildMethod = AttestationProgram::class.java.getDeclaredMethod(
            "buildInstructionData",
            String::class.java,
            String::class.java,
            List::class.java,
            Boolean::class.javaPrimitiveType
        )
        buildMethod.isAccessible = true
        val actual = buildMethod.invoke(AttestationProgram, name, desc, fields, false) as ByteArray

        assertArrayEquals(expected, actual)
        assertEquals(1, actual.last().toInt() and 0xFF)
    }
}

