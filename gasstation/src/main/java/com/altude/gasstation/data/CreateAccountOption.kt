package com.altude.gasstation.data

import foundation.metaplex.rpc.Commitment

data class CreateAccountOption (
    val account: String = "",
    val tokens: List<String>,
    val reference: String = "",
    val commitment: Commitment = Commitment.finalized
)