package com.altude.gasstation.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject

object AccountDataPolymorphicSerializer : KSerializer<AccountData?> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("AccountDataPolymorphic")

    override fun deserialize(decoder: Decoder): AccountData? {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("Expected JsonDecoder")
        val element = input.decodeJsonElement()

        return when (element) {
            is JsonObject -> input.json.decodeFromJsonElement(AccountData.serializer(), element)
            is JsonArray -> null // Ignore string[] like ["", "base64"]
            else -> throw SerializationException("Unexpected JSON for AccountData: $element")
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: AccountData?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            val jsonEncoder = encoder as? JsonEncoder
                ?: throw SerializationException("Expected JsonEncoder")
            val jsonElement = jsonEncoder.json.encodeToJsonElement(AccountData.serializer(), value)
            jsonEncoder.encodeJsonElement(jsonElement)
        }
    }
}