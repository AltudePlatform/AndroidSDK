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
     * to the backend. The backend broadcasts both transactions and returns the result.
     *
     * Verification is done trustlessly via [Provenance.verifyOnChain] — no backend
     * endpoint needed since nothing is stored server-side.
     */
    @POST("api/provenance/attestImageHash")
    fun attestImageHash(
        @Body body: ImageHashRequest
    ): Call<JsonElement>
}
