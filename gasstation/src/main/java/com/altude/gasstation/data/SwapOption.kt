package com.altude.gasstation.data

data class SwapOption(
    val account: String,
    val inputMint: String,
    val outputMint: String,
    val amount: Int,
    val slippageBps: Int = 50,
    val commitment: Commitment
)
