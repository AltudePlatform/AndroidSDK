package com.altude.core.data
import  kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SwapInstructionRequest(
    val userPublicKey: String,
    val payer: String,
    val quoteResponse: QuoteResponse,
    val prioritizationFeeLamports: PrioritizationFeeLamports? = null,
    val dynamicComputeUnitLimit: Boolean = false,
    val wrapAndUnwrapSol: Boolean = true,
    val asLegacyTransaction: Boolean = false,
    val skipUserAccountsRpcCalls: Boolean = false,
    val dynamicSlippage: Boolean = false
)

@Serializable
data class SwapRequest(
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
    val swapMode: String = "ExactIn",

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
    val dynamicSlippage: Boolean = false
)

fun SwapRequest.toQueryMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>(
        "inputMint" to inputMint,
        "outputMint" to outputMint,
        "amount" to amount,
        "slippageBps" to slippageBps,
        "swapMode" to swapMode,
        "restrictIntermediateTokens" to restrictIntermediateTokens,
        "onlyDirectRoutes" to onlyDirectRoutes,
        "asLegacyTransaction" to asLegacyTransaction,
        "maxAccounts" to maxAccounts,
        "instructionVersion" to instructionVersion
    )

    dexes?.takeIf { it.isNotEmpty() }?.let { map["dexes"] = it.joinToString(",") }
    excludeDexes?.takeIf { it.isNotEmpty() }?.let { map["excludeDexes"] = it.joinToString(",") }
    platformFeeBps?.let { map["platformFeeBps"] = it }
    if (dynamicSlippage) map["dynamicSlippage"] = dynamicSlippage

    return map
}

@Serializable
data class QuoteResponse(
    val inputMint: String? = null,
    val inAmount: String? = null,
    val outputMint: String? = null,
    val outAmount: String? = null,
    val otherAmountThreshold: String? = null,
    val swapMode: String? = null,
    val slippageBps: Int? = null,
    val platformFee: JsonElement? = null,
    val priceImpactPct: String? = null,
    val routePlan: List<RoutePlan>? = null,
    val error: String? = null
) {
    val isError: Boolean
        get() = error != null && error != ""
}

@Serializable
data class RoutePlan(
    val swapInfo: SwapInfo,
    val percent: Int
)

@Serializable
data class SwapInfo(
    val ammKey: String,
    val label: String? = null,
    val inputMint: String,
    val outputMint: String,
    val inAmount: String,
    val outAmount: String,
    val feeAmount: String? = null,
    val feeMint: String? = null
)

@Serializable
data class PrioritizationFeeLamports(
    val priorityLevelWithMaxLamports: PriorityLevelWithMaxLamports
)

@Serializable
data class PriorityLevelWithMaxLamports(
    val maxLamports: Long,
    val priorityLevel: String,
    val global: Boolean
)
