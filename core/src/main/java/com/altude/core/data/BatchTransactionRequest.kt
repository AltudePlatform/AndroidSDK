package com.altude.core.data

import com.altude.core.api.IBatchTransactionRequest
import kotlinx.serialization.Serializable

@Serializable
data class BatchTransactionRequest(override val SignedTransaction: String): IBatchTransactionRequest