package com.altude.core.data

/**
 * Generic fixed-size byte serialization contract used by low-level Solana program
 * account/instruction (de)serialization helpers in [com.altude.core.Programs.Utility].
 */
interface KtSerializer<T> {
    fun serialize(value: T): ByteArray
    fun deserialize(data: ByteArray): T
    fun size(): Int // fixed size of serialized object
}
