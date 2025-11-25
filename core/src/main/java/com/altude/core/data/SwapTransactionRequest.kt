package com.altude.core.data

import com.altude.core.api.ISendTransactionRequest
import kotlinx.serialization.Serializable

@Serializable
data class SwapTransactionRequest(override val SignedTransaction: String): ISendTransactionRequest