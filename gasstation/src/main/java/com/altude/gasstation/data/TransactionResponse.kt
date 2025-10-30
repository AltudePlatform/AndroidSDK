package com.altude.gasstation.data

import com.altude.gasstation.interfaces.ITransactionResponse
import kotlinx.serialization.Serializable

@Serializable
data class TransactionResponse(
    override val Status: String,
    override val Message: String,
    override val Signature: String
) : ITransactionResponse
