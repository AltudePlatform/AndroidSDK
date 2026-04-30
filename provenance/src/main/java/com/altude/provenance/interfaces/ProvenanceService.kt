package com.altude.provenance.interfaces

import com.altude.provenance.data.AttestRequest
// CreateSchema endpoint removed; attest-only service
import kotlinx.serialization.json.JsonElement
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit service for the Provenance backend endpoints.
 *
 * Two separate calls per attestation lifecycle:
 * 1. [createSchema] — called **once per wallet** when the schema PDA does not yet exist.
 * 2. [attest]       — called for every image attestation.
 */
interface ProvenanceService {

    // createSchema endpoint removed from service interface

    /**
     * Broadcasts the signed `createAttestation` transaction.
     * The backend adds the feePayer signature and submits to Solana.
     */
    @POST("api/provenance/attestImage")
    fun attest(
        @Body body: AttestRequest
    ): Call<JsonElement>

    // createCredential endpoint removed from service interface
}
