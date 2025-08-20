package com.altude.core.data

import kotlinx.serialization.json.JsonObject

sealed class AccountDataResult {
    data class JsonParsed(val data: JsonObject) : AccountDataResult() // For ATAs
    data class RawBytes(val data: ByteArray) : AccountDataResult()   // For PDAs / custom programs
    {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as RawBytes

            if (!data.contentEquals(other.data)) return false

            return true
        }

        override fun hashCode(): Int {
            return data.contentHashCode()
        }
    }
}
