package com.altude.gasstation.data

import com.altude.gasstation.interfaces.ITransactionResponse
import kotlinx.serialization.Serializable

@Serializable
data class TransactionResponse(
    override val status: String,
    override val message: String,
    override val signature: String
) : ITransactionResponse
