package com.altude.provenance.data

import com.altude.provenance.interfaces.ITransactionResponse
import kotlinx.serialization.Serializable

/**
 * Generic transaction response from the Altude backend.
 */
@Serializable
data class TransactionResponse(
    override val Status: String = "",
    override val Message: String = "",
    override val Signature: String = ""
) : ITransactionResponse

