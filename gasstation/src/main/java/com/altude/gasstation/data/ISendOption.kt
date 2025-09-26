package com.altude.gasstation.data

interface ISendOption {
    val account: String
    val toAddress: String
    val amount: Double
    val token: String
    val commitment: Commitment
}