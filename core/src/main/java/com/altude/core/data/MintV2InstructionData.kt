package com.altude.core.data

import foundation.metaplex.mplbubblegum.generated.bubblegum.MetadataArgs
import kotlinx.serialization.Serializable



// The MintV2 instruction data
@Serializable
data class MintV2InstructionData(
    val discriminator: ByteArray,  // 8 bytes
    val metadata: MetadataArgs,
    val assetData: ByteArray?,      // optional
    val assetDataSchema: ByteArray? // optional
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MintV2InstructionData

        if (!discriminator.contentEquals(other.discriminator)) return false
        if (metadata != other.metadata) return false
        if (!assetData.contentEquals(other.assetData)) return false
        if (!assetDataSchema.contentEquals(other.assetDataSchema)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = discriminator.contentHashCode()
        result = 31 * result + metadata.hashCode()
        result = 31 * result + (assetData?.contentHashCode() ?: 0)
        result = 31 * result + (assetDataSchema?.contentHashCode() ?: 0)
        return result
    }

    fun serialize(): ByteArray {
        var buffer = mutableListOf<Byte>()

        // 1. Add the discriminator (8 bytes)
        buffer += discriminator.toList()

        // 2. Serialize MetadataArgsV2 manually
        buffer += serializeString(metadata.name).toList()
        buffer += serializeString(metadata.symbol).toList()
        buffer += serializeString(metadata.uri).toList()
        buffer += metadata.sellerFeeBasisPoints.toByte()
        buffer += byteArrayOf(if (metadata.primarySaleHappened) 1 else 0).toList()
        buffer += byteArrayOf(if (metadata.isMutable) 1 else 0).toList()

        // 3. Optionally serialize creators
        buffer += metadata.creators.size.toByte()
        metadata.creators.forEach { creator ->
            buffer += creator.address.toByteArray().toList()
            buffer += byteArrayOf(if (creator.verified) 1 else 0).toList()
            buffer += creator.share.toByte()
        }

        // 4. Serialize optional assetData
        buffer += assetData?.size?.toByte() ?: 0
        if (assetData != null) buffer += assetData.toList()

        // 5. Serialize optional assetDataSchema
        buffer += assetDataSchema?.size?.toByte() ?: 0
        if (assetDataSchema != null) buffer += assetDataSchema.toList()

        return buffer.toByteArray()
    }

    fun serializeString(s: String): ByteArray {
        val bytes = s.encodeToByteArray()
        val length = bytes.size.toByte()
        return byteArrayOf(length) + bytes
    }
}