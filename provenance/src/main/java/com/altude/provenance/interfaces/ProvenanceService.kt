package com.altude.provenance.interfaces

import com.altude.provenance.data.AttestRequest
import com.altude.provenance.data.CreateSchemaRequest
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

    /**
     * Broadcasts the signed `createSchema` transaction.
     * Called once per wallet — the SDK checks on-chain and in SharedPreferences
     * before calling this, so it is skipped on every subsequent attestation.
     */
    @POST("api/provenance/createSchema")
    fun createSchema(
        @Body body: CreateSchemaRequest
    ): Call<JsonElement>

    /**
     * Broadcasts the signed `createAttestation` transaction.
     * The backend adds the feePayer signature and submits to Solana.
     */
    @POST("api/provenance/attestImage")
    fun attest(
        @Body body: AttestRequest
    ): Call<JsonElement>

    /**
     * Broadcasts the signed `createCredential` transaction.
     * Called when creating a credential account on-chain.
     */
    @POST("api/provenance/createCredential")
    fun createCredential(
        @Body body: com.altude.provenance.data.CreateCredentialRequest
    ): Call<JsonElement>
}
