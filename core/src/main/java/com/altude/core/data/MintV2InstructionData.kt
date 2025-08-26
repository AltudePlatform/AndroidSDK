package com.altude.core.data

import foundation.metaplex.mplbubblegum.generated.bubblegum.Collection
import foundation.metaplex.mplbubblegum.generated.bubblegum.MetadataArgs
import foundation.metaplex.mplbubblegum.generated.bubblegum.TokenProgramVersion
import foundation.metaplex.mplbubblegum.generated.bubblegum.TokenStandard
import foundation.metaplex.mplbubblegum.generated.bubblegum.UseMethod
import foundation.metaplex.mplbubblegum.generated.bubblegum.Uses
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets


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
    fun encodeString(s: String): ByteArray {
        val utf8 = s.toByteArray(StandardCharsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + utf8.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(utf8.size)
        buf.put(utf8)
        return buf.array()
    }

    fun encodeBoolean(b: Boolean) = byteArrayOf(if (b) 1 else 0)

    fun encodeU16(v: UShort): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

    fun encodeU8(v: UByte) = byteArrayOf(v.toByte())

    fun encodeOptional(data: ByteArray?): ByteArray {
        return if (data == null) {
            byteArrayOf(0)
        } else {
            byteArrayOf(1) + data
        }
    }
    fun serializeMetadataArgsV2(meta: MetadataArgs): ByteArray {
        val out = ByteArrayOutputStream()

        out.write(encodeString(meta.name))
        out.write(encodeString(meta.symbol))
        out.write(encodeString(meta.uri))
        out.write(encodeU16(meta.sellerFeeBasisPoints))
        out.write(encodeBoolean(meta.primarySaleHappened))
        out.write(encodeBoolean(meta.isMutable))

        // editionNonce: Option<u8>
        out.write(encodeOptional(meta.editionNonce?.let { byteArrayOf(it.toByte()) }))

        // tokenStandard: Option<u8>
        out.write(encodeOptional(meta.tokenStandard?.let { byteArrayOf(it.toByte()) }))

        // collection: Option<Collection>
        out.write(encodeOptional(meta.collection?.let {
            ByteArrayOutputStream().apply {
                write(encodeBoolean(it.verified))
                write(it.key.publicKeyBytes)
            }.toByteArray()
        }))

        // uses: Option<Uses>
        out.write(encodeOptional(meta.uses?.let {
            ByteArrayOutputStream().apply {
                write(it.useMethod.toByte().toInt()) // enum as u8
                write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(it.remaining.toLong()).array())
                write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(it.total.toLong()).array())
            }.toByteArray()
        }))

        // creators: Option<Vec<Creator>>
        out.write(encodeOptional(
            if (meta.creators.isNotEmpty()) {
                ByteArrayOutputStream().apply {
                    write(encodeU32(meta.creators.size.toLong()))
                    meta.creators.forEach { c ->
                        write(c.address.publicKeyBytes)
                        write(encodeBoolean(c.verified))
                        write(encodeU8(c.share))
                    }
                }.toByteArray()
            } else null
        ))

        // tokenProgramVersion (u8)
        out.write(byteArrayOf(meta.tokenProgramVersion.toByte()))

        // tokenProgramType (u8)
        out.write(byteArrayOf(meta.tokenStandard?.toByte() ?: 0))

        return out.toByteArray()
    }
    fun encodeU32(value: Long): ByteArray {
        val buf = ByteBuffer.allocate(4)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt((value and 0xFFFFFFFFL).toInt())
        return buf.array()
    }
    fun TokenStandard.toByte(): Byte = when (this) {
        TokenStandard.NonFungible        -> 0
        TokenStandard.FungibleAsset      -> 1
        TokenStandard.Fungible           -> 2
        TokenStandard.NonFungibleEdition -> 3
    }.toByte()
    fun TokenProgramVersion.toByte(): Byte = when (this) {
        TokenProgramVersion.Original       -> 0
        TokenProgramVersion.Token2022      -> 1

    }.toByte()
    fun UseMethod.toByte(): Byte = when (this) {
        UseMethod.Burn        -> 0
        UseMethod.Multiple    -> 1
        UseMethod.Single      -> 2

    }.toByte()

    fun serializeOptionalCollection(collection: Collection?): ByteArray {
        val buffer = ByteArrayOutputStream()
        val writer = DataOutputStream(buffer)

        if (collection == null) {
            writer.writeByte(0) // None
        } else {
            writer.writeByte(1) // Some
            writer.writeByte(if (collection.verified) 1 else 0)
            writer.write(collection.key.toByteArray())
        }

        return buffer.toByteArray()
    }
    fun serialize(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(discriminator)
        out.write(serializeMetadataArgsV2(metadata))
        out.write(encodeOptional(assetData))
        out.write(encodeOptional(assetDataSchema))
        return out.toByteArray()
    }

    fun serializeString(s: String): ByteArray {
        val bytes = s.encodeToByteArray()
        return intToLeBytes(bytes.size) + bytes
    }

    fun shortToLeBytes(value: UShort): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

    fun intToLeBytes(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}




