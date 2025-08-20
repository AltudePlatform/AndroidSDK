package com.altude.core.helper

import com.altude.core.data.AccountData
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.util.encoders.Base64


object AccountDataPolymorphicSerializer : KSerializer<AccountData?> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("AccountDataPolymorphic")

    override fun deserialize(decoder: Decoder): AccountData? {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("Expected JsonDecoder")
        val element = input.decodeJsonElement()

        return when (element) {
            is JsonObject -> input.json.decodeFromJsonElement(AccountData.Parsed.serializer(), element)
            is JsonArray -> {
                // Base64 case ["<base64string>", "base64"]
                val base64Str = element[0].jsonPrimitive.content
                AccountData.Raw(Base64.decode(base64Str))
            }
            else -> null
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: AccountData?) {
        val jsonEncoder = encoder as? JsonEncoder ?: throw SerializationException("Expected JsonEncoder")
        when (value) {
            is AccountData.Parsed -> jsonEncoder.encodeSerializableValue(AccountData.Parsed.serializer(), value)
            is AccountData.Raw -> {
                val encoded = Base64.toBase64String(value.bytes)
                jsonEncoder.encodeSerializableValue(ListSerializer(String.serializer()), listOf(encoded, "base64"))
            }
            null -> encoder.encodeNull()
        }
    }
}