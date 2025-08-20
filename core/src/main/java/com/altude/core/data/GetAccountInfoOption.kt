package com.altude.core.data

import com.solana.rpc.Encoding

data class GetAccountInfoOption(
    val account: String = "",
    val useBase64: Boolean = false
)
