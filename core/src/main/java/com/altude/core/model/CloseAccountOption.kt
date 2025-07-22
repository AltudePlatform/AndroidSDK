package com.altude.core.model

import foundation.metaplex.solanaeddsa.Keypair

data class CloseAccountOption (
    val owner: Keypair,
    val mint: String
)
