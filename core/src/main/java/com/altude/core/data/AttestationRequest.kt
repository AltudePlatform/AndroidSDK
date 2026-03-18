package com.altude.core.data

import kotlinx.serialization.Serializable

/**
 * Request body for POST api/attestation/createSchema
 */
@Serializable
data class CreateSchemaRequest(
    val SignedTransaction: String
)

/**
 * Request body for POST api/attestation/attest
 */
@Serializable
data class AttestRequest(
    val SignedTransaction: String
)

/**
 * Request body for POST api/attestation/revoke
 */
@Serializable
data class RevokeAttestationRequest(
    val SignedTransaction: String
)

