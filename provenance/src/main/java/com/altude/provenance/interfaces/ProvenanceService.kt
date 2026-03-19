package com.altude.provenance.interfaces

import com.altude.provenance.data.ImageHashRequest
import kotlinx.serialization.json.JsonElement
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

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
}
