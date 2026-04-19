package com.altude.gasstation.data

import kotlinx.serialization.Serializable

/**
 * Response returned after a successful attestation operation
 * (create, revoke, or schema creation).
 */
@Serializable
data class AttestationResponse(
    /** "success" or "error" */
    val Status: String,
    /** Human-readable result message. */
    val Message: String,
    /** Transaction signature on Solana. */
    val Signature: String,
    /** The on-chain Attestation / Schema account pubkey (Base58). Empty for revoke. */
    val attestationId: String = ""
)

