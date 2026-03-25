package com.altude.gasstation.data

data class GetHistoryOption (
    val account: String = "",
    val limit: Int = 10,
    val offset: Int = 0,
    val walletAddress: String = ""
)