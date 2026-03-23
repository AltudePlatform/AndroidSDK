package com.altude.provenance.interfaces

import com.altude.provenance.data.ImageHashRequest
import kotlinx.serialization.json.JsonElement
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Retrofit service for the Provenance backend endpoints.
 */
interface ProvenanceService {

    /**
     * Submits both the signed `createSchema` and `createAttestation` transactions
     * along with the structured image-hash metadata to the backend.
     * The backend broadcasts both transactions and returns the attestation result.
     */
    @POST("api/provenance/attestImageHash")
    fun attestImageHash(
        @Body body: ImageHashRequest
    ): Call<JsonElement>

    /**
     * Queries the backend for an attestation by its manifest hash
     * ([C2paManifest.manifestHash] — what is stored on-chain).
     *
     * Backend returns the on-chain hash and the stored [ProvenanceCertificate] JSON.
     */
    @GET("api/provenance/verify")
    fun verifyByHash(
        @Query("hash") hash: String
    ): Call<JsonElement>

    /**
     * Queries the backend for an attestation by its on-chain Attestation PDA (Base58).
     * Use when you have the `attestationId` from a previous [attestImageHash] call.
     */
    @GET("api/provenance/verify")
    fun verifyByAttestationId(
        @Query("attestationId") attestationId: String
    ): Call<JsonElement>
}
