package com.altude.core.data
import  kotlinx.serialization.Serializable
import kotlin.String

@Serializable
data class SwapResponse(
    val otherInstructions: List<SwapInstruction>? = null,
    val computeBudgetInstructions: List<SwapInstruction>? = null,
    val setupInstructions: List<SwapInstruction>? = null,
    val swapInstruction: SwapInstruction? = null,
    val cleanupInstruction: SwapInstruction? = null,
    val addressLookupTableAddresses: List<String>? = null,
    val swapTransaction: String? = null,
    val error: String? = null
){
    val isError: Boolean
        get() = error != null && error != ""
}

@Serializable
data class SwapInstruction(
    val programId: String,
    val accounts: List<SwapAccountMeta>,
    val data: String
)

@Serializable
data class SwapAccountMeta(
    val pubkey: String,
    val isSigner: Boolean,
    val isWritable: Boolean
)
