package com.altude.gasstation.data

import com.altude.gasstation.data.Commitment

data class CloseAccountOption (
    val account: String = "",
    val tokens: List<String>,
    val reference: String = "",
    val commitment: Commitment = Commitment.finalized
)