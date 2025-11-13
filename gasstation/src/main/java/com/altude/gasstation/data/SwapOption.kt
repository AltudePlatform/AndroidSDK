package com.altude.gasstation.data

data class SwapOption(
    val account: String,
    /**
     * The input token mint address.
     */
    val inputMint: String = "",

    /**
     * The output token mint address.
     */
    val outputMint: String = "",

    /**
     * Raw amount to swap (before decimals).
     * Input amount if swapMode = ExactIn.
     * Output amount if swapMode = ExactOut.
     */
    val amount: Long,

    /**
     * Slippage threshold in basis points. Default is 50 (0.5%).
     */
    val slippageBps: Int = 50,

    /**
     * Swap mode: "ExactIn" or "ExactOut".
     */
    val swapMode: String = SwapMode.ExactIn.name,

    /**
     * List of DEX names to include.
     * Example: ["Raydium", "Orca+V2"]
     */
    val dexes: List<String>? = null,

    /**
     * List of DEX names to exclude.
     * Example: ["Raydium", "Meteora+DLMM"]
     */
    val excludeDexes: List<String>? = null,

    /**
     * Restrict intermediate tokens to stable tokens.
     * Default is true.
     */
    val restrictIntermediateTokens: Boolean = true,

    /**
     * Limit to direct (single-hop) routes only.
     * Default is false.
     */
    val onlyDirectRoutes: Boolean = false,

    /**
     * Use legacy (non-versioned) transaction.
     * Default is false.
     */
    val asLegacyTransaction: Boolean = false,

    /**
     * Platform fee in basis points. Optional.
     */
    val platformFeeBps: Int? = null,

    /**
     * Maximum number of accounts used in the quote.
     * Default is 64.
     */
    val maxAccounts: Int = 64,

    /**
     * Instruction version: "V1" or "V2".
     * Default is "V1".
     */
    val instructionVersion: String = "V1",

    /**
     * No longer applicable (used for /swap only). Default false.
     */
    val dynamicSlippage: Boolean = false,
    val priorityLevelWithMaxLamports : PriorityLevelWithMaxLamports? = null,
    val commitment: Commitment
)

enum class SwapMode{
    ExactIn,
    ExactOut
}
data class PriorityLevelWithMaxLamports(
    val maxLamports: Long,
    val priorityLevel: String,
    val global: Boolean
)

