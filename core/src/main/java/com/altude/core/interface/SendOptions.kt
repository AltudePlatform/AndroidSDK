package com.altude.core.`interface`

import foundation.metaplex.solanaeddsa.Keypair

interface SendOptions {
    val owner: Keypair
    val destination: String
    val amount: Double
    val mint: String
}
