package com.altude.core.helper

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

object U64AsStringSerializer: KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("U64AsString", PrimitiveKind.STRING)



    override fun deserialize(decoder: Decoder): String {
        return when (val jsonDecoder = decoder as? JsonDecoder) {
            null -> decoder.decodeString()
            else -> {
                val element = jsonDecoder.decodeJsonElement()
                when (element) {
                    is JsonPrimitive -> {
                        if (element.isString) element.content else element.toString()
                    }
                    else -> element.toString()
                }
            }
        }
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}