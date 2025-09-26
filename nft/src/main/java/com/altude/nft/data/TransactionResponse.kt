package com.altude.nft.data

import com.altude.nft.interfaces.ITransactionResponse

data class TransactionResponse(
    override val status: String,
    override val message: String,
    override val signature: String
) : ITransactionResponse
