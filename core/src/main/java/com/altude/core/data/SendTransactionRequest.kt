package com.altude.core.data

import com.altude.core.api.ISendTransactionRequest
import kotlinx.serialization.Serializable

@Serializable
data class SendTransactionRequest(override val SignedTransaction: String): ISendTransactionRequest