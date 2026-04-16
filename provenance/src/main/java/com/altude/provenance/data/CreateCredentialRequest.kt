package com.altude.provenance.data

import kotlinx.serialization.Serializable

@Serializable
data class CreateCredentialRequest(
    val SignedTransaction: String,
    val SolanaClusterId: Int = 1
)

