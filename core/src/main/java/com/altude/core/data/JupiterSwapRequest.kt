package com.altude.core.data
import  kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class JupiterSwapRequest(
    val userPublicKey: String,
    val quoteResponse: QuoteResponse,
    val prioritizationFeeLamports: PrioritizationFeeLamports,
    val dynamicComputeUnitLimit: Boolean = false,
    val wrapAndUnwrapSol: Boolean = true,
    val asLegacyTransaction: Boolean = false,
    val skipUserAccountsRpcCalls: Boolean = false,
    val dynamicSlippage: Boolean = false
)

@Serializable
data class SwapRequest(
    val UserPublicKey: String,
    val InputMint: String,
    val OutputMint: String,
    val Amount: Int,
    val SlippageBps: Int = 50,
)

@Serializable
data class QuoteResponse(
    val inputMint: String,
    val inAmount: String,
    val outputMint: String,
    val outAmount: String,
    val otherAmountThreshold: String,
    val swapMode: String,
    val slippageBps: Int,
    val platformFee: JsonElement? = null,
    val priceImpactPct: String,
    val routePlan: List<RoutePlan>
)

@Serializable
data class RoutePlan(
    val swapInfo: SwapInfo,
    val percent: Int
)

@Serializable
data class SwapInfo(
    val ammKey: String,
    val label: String,
    val inputMint: String,
    val outputMint: String,
    val inAmount: String,
    val outAmount: String,
    val feeAmount: String,
    val feeMint: String
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
