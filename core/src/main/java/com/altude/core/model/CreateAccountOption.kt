package com.altude.core.model

import foundation.metaplex.solanaeddsa.Keypair

data class CreateAccountOption (
    val owner: Keypair,
    val mint: String
)
