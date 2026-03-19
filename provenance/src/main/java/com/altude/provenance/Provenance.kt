package com.altude.provenance

import com.altude.core.config.SdkConfig
import com.altude.provenance.data.ImageHashPayload
import com.altude.provenance.data.ImageHashRequest
import com.altude.provenance.data.ImageHashResponse
import com.altude.provenance.interfaces.ProvenanceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import retrofit2.await

/**
 * Public entry point for the Provenance module.
 *
 * Attests image data on-chain using the Solana Attestation Service (SAS) program.
 * Internally handles schema creation and attestation in a single call.
 *
 * Usage:
 * ```kotlin
 * // Option A — let the SDK compute the SHA-256 hash for you
 * val payload = ImageHashPayload.from(
 *     imageBytes = pngByteArray,
 *     mime       = "image/png",
 *     name       = "photo.png"
 * )
 * val result = Provenance.attestImageHash(payload)
 * val attestationId = result.getOrThrow().attestationId
 *
 * // Option B — supply a pre-computed hash
 * val payload = ImageHashPayload(hash = myHexHash, mime = "image/jpeg")
 * val result  = Provenance.attestImageHash(payload)
 * ```
 */
object Provenance {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun service(): ProvenanceService = SdkConfig.createService(ProvenanceService::class.java)

    // ── Image-hash attestation ────────────────────────────────────────────────

    /**
     * Attests an image by its SHA-256 hash on-chain.
     *
     * Internally this call:
     * 1. Builds and signs a `createSchema` transaction (idempotent — same authority + name
     *    always resolves to the same on-chain Schema PDA).
     * 2. Builds and signs a `createAttestation` transaction with the structured
     *    image-hash JSON payload.
     * 3. Sends both signed transactions to the backend for broadcasting.
     *
     * @return [ImageHashResponse] containing the transaction [ImageHashResponse.Signature]
     *         and the on-chain Attestation PDA in [ImageHashResponse.attestationId].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun attestImageHash(
        payload: ImageHashPayload
    ): Result<ImageHashResponse> = withContext(Dispatchers.IO) {
        try {
            // 1. Build & sign createSchema tx
            val schemaResult = ProvenanceManager.createSchema(payload)
            if (schemaResult.isFailure)
                return@withContext Result.failure(schemaResult.exceptionOrNull()!!)

            // 2. Derive the Schema PDA so we can pass it to createAttestation
            val schemaPda = ProvenanceManager.deriveSchemaAddress(payload.account)

            // 3. Build & sign createAttestation tx
            val attestResult = ProvenanceManager.attest(payload, schemaPda)
            if (attestResult.isFailure)
                return@withContext Result.failure(attestResult.exceptionOrNull()!!)

            // 4. Send both signed transactions to the backend
            val request = ImageHashRequest(
                type               = payload.type,
                hash               = payload.hash,
                mime               = payload.mime,
                name               = payload.name,
                timestamp          = payload.timestamp,
                account            = payload.account,
                recipient          = payload.recipient,
                expireAt           = payload.expireAt,
                signedSchemaTx     = schemaResult.getOrThrow(),
                signedAttestationTx = attestResult.getOrThrow()
            )

            val res      = service().attestImageHash(request).await()
            val response = decodeJson<ImageHashResponse>(res)

            Result.success(response)
        } catch (e: Throwable) {
            Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private inline fun <reified T> decodeJson(element: JsonElement): T =
        json.decodeFromJsonElement(element)
}
