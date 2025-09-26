package com.altude.nft.data

import com.altude.core.api.ISendTransactionRequest

data class SendTransactionRequest(override val SignedTransaction: String): ISendTransactionRequest
