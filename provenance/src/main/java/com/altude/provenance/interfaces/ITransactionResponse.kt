package com.altude.provenance.interfaces

/**
 * Common contract for all transaction responses from the Altude backend.
 */
interface ITransactionResponse {
    val Status: String
    val Message: String
    val Signature: String
}

