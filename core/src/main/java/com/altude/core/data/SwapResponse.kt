package com.altude.core.data
import  kotlinx.serialization.Serializable

@Serializable
data class JupiterSwapResponse(
    val otherInstructions: List<JupiterInstruction>? = null,
    val computeBudgetInstructions: List<JupiterInstruction>? = null,
    val setupInstructions: List<JupiterInstruction>? = null,
    val swapInstruction: JupiterInstruction? = null,
    val cleanupInstruction: JupiterInstruction? = null,
    val addressLookupTableAddresses: List<String>? = null
)

@Serializable
data class JupiterInstruction(
    val programId: String,
    val accounts: List<JupiterAccountMeta>,
    val data: String
)

@Serializable
data class JupiterAccountMeta(
    val pubkey: String,
    val isSigner: Boolean,
    val isWritable: Boolean
)
