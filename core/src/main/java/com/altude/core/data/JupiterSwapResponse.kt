package com.altude.core.data
import  kotlinx.serialization.Serializable

@Serializable
data class JupiterSwapResponse(
    val OtherInstructions: List<JupiterInstruction>? = null,
    val ComputeBudgetInstructions: List<JupiterInstruction>? = null,
    val SetupInstructions: List<JupiterInstruction>? = null,
    val SwapInstruction: JupiterInstruction? = null,
    val CleanupInstruction: JupiterInstruction? = null,
    val AddressLookupTableAddresses: List<String>? = null
)

@Serializable
data class JupiterInstruction(
    val ProgramId: String,
    val Accounts: List<JupiterAccountMeta>,
    val Data: String
)

@Serializable
data class JupiterAccountMeta(
    val Pubkey: String,
    val IsSigner: Boolean,
    val IsWritable: Boolean
)
