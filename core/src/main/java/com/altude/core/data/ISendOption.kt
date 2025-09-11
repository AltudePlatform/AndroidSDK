package com.altude.core.data

import foundation.metaplex.rpc.Commitment

interface ISendOption {
    val account: String
    val toAddress: String
    val amount: Double
    val token: String
    val commitment: Commitment
}
