package com.altude.core.data


import foundation.metaplex.solanapublickeys.PublicKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder


object PublicKeyAsBase58Serializer : KSerializer<PublicKey> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("PublicKey", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: PublicKey) {
        encoder.encodeString(value.toBase58())
    }

    override fun deserialize(decoder: Decoder): PublicKey {
        return PublicKey(decoder.decodeString())
    }
}
object PublicKeyListAsBase58Serializer : KSerializer<List<PublicKey>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("PublicKeyList", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: List<PublicKey>) {
        encoder.encodeString(value.joinToString(",") { it.toBase58() })
    }

    override fun deserialize(decoder: Decoder): List<PublicKey> {
        return decoder.decodeString()
            .split(",")
            .filter { it.isNotEmpty() }
            .map { PublicKey(it) }
    }
}

@Serializable
class ChangeLog(
    @Serializable(with = PublicKeyAsBase58Serializer::class)
    val root: PublicKey,
    @Serializable(with = PublicKeyListAsBase58Serializer::class)
    val pathNodes: List<PublicKey>,
    val index: Int
)
