package com.altude.gasstation.data

data class GetBalanceOption(
    val account: String ="",
    val token: String = Token.USDC.mint()
)