package com.altude.gasstation.data

data class GetHistoryOption (
    val account: String,
    val limit: Int,
    val offset: Int,
    val walletAddress: String
)