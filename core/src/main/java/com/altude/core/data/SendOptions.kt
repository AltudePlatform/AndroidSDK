package com.altude.core.data

import foundation.metaplex.rpc.Commitment
import foundation.metaplex.solanaeddsa.Keypair

interface SendOptions {
    val account: String
    val toAddress: String
    val amount: Double
    val token: String
    val commitment: Commitment
}
