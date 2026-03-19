package com.altude.provenance.data

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * Structured payload for an image-hash attestation.
 *
 * The `hash` field is computed automatically from the raw bytes when you call
 * [ImageHashPayload.from]; you never need to compute it yourself.
 *
 * @param type      Always `"image_hash"`.
 * @param hash      Hex-encoded SHA-256 digest of the image bytes.
 * @param mime      MIME type of the image (e.g. `"image/png"`).
 * @param name      Optional human-readable file name (e.g. `"my-image.png"`).
 * @param timestamp Unix epoch (seconds) when the attestation was created.
 * @param account   Attester wallet address (Base58). Blank = stored default wallet.
 * @param recipient The account being attested (Base58 pubkey). Defaults to attester if blank.
 * @param expireAt  Unix timestamp (seconds) when the attestation expires. 0 = no expiry.
 * @param commitment Finality commitment to wait for.
 */
data class ImageHashPayload(
    val type: String = "image_hash",
    val hash: String,
    val mime: String = "image/png",
    val name: String = "",
    val timestamp: Long = System.currentTimeMillis() / 1000,
    // SDK routing fields (not serialised in the JSON body)
    val account: String = "",
    val recipient: String = "",
    val expireAt: Long = 0L,
    val commitment: Commitment = Commitment.finalized
) {
    companion object {
        /**
         * Convenience factory — computes the SHA-256 hash of [imageBytes] for you.
         *
         * ```kotlin
         * val payload = ImageHashPayload.from(
         *     imageBytes = pngBytes,
         *     mime       = "image/png",
         *     name       = "my-photo.png"
         * )
         * val result = Provenance.attestImageHash(payload)
         * ```
         */
        fun from(
            imageBytes: ByteArray,
            mime: String = "image/png",
            name: String = "",
            account: String = "",
            recipient: String = "",
            expireAt: Long = 0L,
            commitment: Commitment = Commitment.finalized
        ): ImageHashPayload {
            val hashBytes = MessageDigest.getInstance("SHA-256").digest(imageBytes)
            val hashHex   = hashBytes.joinToString("") { "%02x".format(it) }
            return ImageHashPayload(
                hash       = hashHex,
                mime       = mime,
                name       = name,
                timestamp  = System.currentTimeMillis() / 1000,
                account    = account,
                recipient  = recipient,
                expireAt   = expireAt,
                commitment = commitment
            )
        }
    }
}

// ── Wire types (serialised to/from backend) ───────────────────────────────────

/**
 * The JSON body sent to `POST api/provenance/attestImageHash`.
 *
 * Carries both the image-hash metadata and the two client-signed Solana
 * transactions (createSchema + createAttestation) for the backend to broadcast.
 */
@Serializable
data class ImageHashRequest(
    /** Always `"image_hash"`. */
    val type: String,
    /** Hex-encoded SHA-256 of the image. */
    val hash: String,
    /** MIME type (e.g. `"image/png"`). */
    val mime: String,
    /** Optional file name. */
    val name: String = "",
    /** Unix epoch (seconds). */
    val timestamp: Long,
    /** Attester wallet (Base58). Empty = server uses the key associated with the API key. */
    val account: String = "",
    /** Recipient wallet (Base58). Empty = attester self-attests. */
    val recipient: String = "",
    /** Expiry unix timestamp (seconds). 0 = no expiry. */
    val expireAt: Long = 0L,
    /** Base64-encoded, partially-signed `createSchema` transaction. */
    val signedSchemaTx: String,
    /** Base64-encoded, partially-signed `createAttestation` transaction. */
    val signedAttestationTx: String
)

/**
 * Response from `POST api/provenance/attestImageHash`.
 */
@Serializable
data class ImageHashResponse(
    /** `"success"` or `"error"`. */
    val Status: String,
    /** Human-readable message. */
    val Message: String,
    /** Solana transaction signature. */
    val Signature: String = "",
    /** On-chain Attestation PDA (Base58). */
    val attestationId: String = ""
)

