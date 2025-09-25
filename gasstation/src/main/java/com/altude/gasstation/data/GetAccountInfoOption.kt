package com.altude.gasstation.data

data class GetAccountInfoOption(
    val account: String = "",
    val useBase64: Boolean = false
)