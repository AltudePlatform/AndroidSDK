package com.altude.core.data

import kotlinx.serialization.Serializable
import java.util.UUID

// Example model for blockhash
@Serializable
data class RpcResponse<T>(
    val jsonrpc: String = "2.0",
    val id: String? = null,
    val result: T? = null,
    val error: RpcError? = null // must be nullable
)

@Serializable
data class BlockhashResult(
    val context: BlockhashContext,
    val value: BlockhashValue
)

@Serializable
data class BlockhashContext(
    val apiVersion: String,
    val slot: Long
)

@Serializable
data class BlockhashValue(
    val blockhash: String,
    val lastValidBlockHeight: Long
)

@Serializable
data class RentExemptionResponse(
    val jsonrpc: String,
    val id: Int? = null,
    val result: Long
)


@Serializable
data class RpcError(
    val code: Int,
    val message: String
)

@Serializable
data class CommitmentParam(val commitment: String)

@Serializable
data class JsonRpc20Request<T>(
    val jsonrpc: String,
    val id: String = UUID.randomUUID().toString(),
    val method: String,
    val params: List<T> = emptyList()
)