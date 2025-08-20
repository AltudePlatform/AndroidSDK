package com.altude.core.data

import foundation.metaplex.solanapublickeys.PublicKey
import kotlinx.serialization.Serializer

data class ConcurrentMerkleTreeHeaderData(
    val maxBufferSize: UInt,
    val maxDepth: UInt,
    val authority: PublicKey,
    val creationSlot: ULong,
    val isBatchInitialized: Boolean,
    val padding: ByteArray // size 5
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ConcurrentMerkleTreeHeaderData

        if (isBatchInitialized != other.isBatchInitialized) return false
        if (maxBufferSize != other.maxBufferSize) return false
        if (maxDepth != other.maxDepth) return false
        if (authority != other.authority) return false
        if (creationSlot != other.creationSlot) return false
        if (!padding.contentEquals(other.padding)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = isBatchInitialized.hashCode()
        result = 31 * result + maxBufferSize.hashCode()
        result = 31 * result + maxDepth.hashCode()
        result = 31 * result + authority.hashCode()
        result = 31 * result + creationSlot.hashCode()
        result = 31 * result + padding.contentHashCode()
        return result
    }
}

interface KtSerializer<T> {
    fun serialize(value: T): ByteArray
    fun deserialize(data: ByteArray): T
    fun size(): Int // fixed size of serialized object
}
object ConcurrentMerkleTreeHeaderDataSerializer : KtSerializer<ConcurrentMerkleTreeHeaderData> {

    override fun serialize(value: ConcurrentMerkleTreeHeaderData): ByteArray {
        val buffer = ByteArray(4 + 4 + 32 + 8 + 1 + 5)
        var offset = 0

        offset = buffer.writeUInt32LE(offset, value.maxBufferSize)
        offset = buffer.writeUInt32LE(offset, value.maxDepth)
        offset = buffer.writeBytes(offset, value.authority.toByteArray())
        offset = buffer.writeULong64LE(offset, value.creationSlot)
        offset = buffer.writeBoolean(offset, value.isBatchInitialized)
        offset = buffer.writeBytes(offset, value.padding)

        return buffer
    }

    override fun deserialize(data: ByteArray): ConcurrentMerkleTreeHeaderData {
        var offset = 0

        val maxBufferSize = data.readUInt32LE(offset).also { offset += 4 }
        val maxDepth = data.readUInt32LE(offset).also { offset += 4 }
        val authority = PublicKey(data.copyOfRange(offset, offset + 32)).also { offset += 32 }
        val creationSlot = data.readULong64LE(offset).also { offset += 8 }
        val isBatchInitialized = data.readBoolean(offset).also { offset += 1 }
        val padding = data.copyOfRange(offset, offset + 5)

        return ConcurrentMerkleTreeHeaderData(
            maxBufferSize, maxDepth, authority, creationSlot, isBatchInitialized, padding
        )
    }

    override fun size(): Int {
        return 4 + 4 + 32 + 8 + 1 + 5 // maxBufferSize + maxDepth + authority + creationSlot + isBatchInitialized + padding
    }

    // Helpers for reading/writing
    private fun ByteArray.writeUInt32LE(offset: Int, value: UInt): Int {
        this[offset] = (value.toInt() and 0xFF).toByte()
        this[offset + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        this[offset + 2] = ((value.toInt() shr 16) and 0xFF).toByte()
        this[offset + 3] = ((value.toInt() shr 24) and 0xFF).toByte()
        return offset + 4
    }

    private fun ByteArray.writeULong64LE(offset: Int, value: ULong): Int {
        for (i in 0..7) this[offset + i] = ((value shr (8 * i)) and 0xFFUL).toByte()
        return offset + 8
    }

    private fun ByteArray.writeBoolean(offset: Int, value: Boolean): Int {
        this[offset] = if (value) 1 else 0
        return offset + 1
    }

    private fun ByteArray.writeBytes(offset: Int, value: ByteArray): Int {
        System.arraycopy(value, 0, this, offset, value.size)
        return offset + value.size
    }

    private fun ByteArray.readUInt32LE(offset: Int): UInt =
        ((this[offset].toUInt() and 0xFFu) or
                ((this[offset + 1].toUInt() and 0xFFu) shl 8) or
                ((this[offset + 2].toUInt() and 0xFFu) shl 16) or
                ((this[offset + 3].toUInt() and 0xFFu) shl 24))

    private fun ByteArray.readULong64LE(offset: Int): ULong {
        var result = 0UL
        for (i in 0..7) {
            result = result or ((this[offset + i].toULong() and 0xFFUL) shl (8 * i))
        }
        return result
    }

    private fun ByteArray.readBoolean(offset: Int): Boolean = this[offset] != 0.toByte()
}
