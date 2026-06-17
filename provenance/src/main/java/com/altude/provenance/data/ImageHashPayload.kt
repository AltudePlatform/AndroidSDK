package com.altude.provenance.data

import android.content.ContentResolver
import android.net.Uri
import kotlinx.serialization.Serializable
import android.graphics.BitmapFactory
import java.security.MessageDigest

/**
 * Flexible payload for attestation with user-defined schema data.
 *
 * SDK users can construct this with any JSON schema format:
 * ```kotlin
 * val payload = ImageHashPayload.create(
 *     schemaData = mapOf(
 *         "image_hash" to imageHash,
 *         "producer" to walletAddress,
 *         "custom_field" to "custom_value"
 *     ),
 *     account = walletAddress
 * )
 * val result = Provenance.attestImageHash(payload)
 * ```
 *
 * The payload accepts arbitrary JSON data, allowing SDK users to define
 * their own schema formats for different use cases.
 */
@ConsistentCopyVisibility
data class ImageHashPayload internal constructor(
    /** User-defined schema data as a map (will be serialized to JSON) */
    val schemaData: Map<String, Any>,
    /** Hash of the schema data (SHA-256 hex) — computed automatically */
    val dataHash: String = "",
    /** Attester wallet address (Base58). Blank = stored default wallet. */
    val account: String = "",
    /** Recipient wallet (Base58). Defaults to attester if blank. */
    val recipient: String = "",
    /** Unix timestamp (seconds) when attestation expires. 0 = no expiry. */
    val expireAt: Long = 0L,
    /** Finality commitment to wait for. */
    val commitment: Commitment = Commitment.finalized,
    /** Pre-built certificate (set internally after offline signing). */
    internal val certificate: ProvenanceCertificate? = null,
    /** Optional SHA-256 hex of the certificate JSON — stored on-chain as `certificate_hash`. */
    val certificateHash: String = "",
    // Schema data fields extracted for convenient access
    val imageHash: ByteArray = ByteArray(0),
    val parentHash: ByteArray? = null,
    val hashAlgorithm: String = "sha256",
    val mimeType: String = "image/png",
    val width: Int = 0,
    val height: Int = 0,
    val fileSize: Long = 0L,
    val filename: String = "",
    val owner: String = "",
    val timestamp: Long = System.currentTimeMillis() / 1000
) {
    companion object {
        /**
         * Create payload from user-defined schema data.
         * Accepts any JSON-serializable map of data.
         *
         * Example:
         * ```kotlin
         * val payload = ImageHashPayload.create(
         *     schemaData = mapOf(
         *         "image_hash" to imageHashHex,
         *         "mime_type" to "image/png",
         *         "producer" to walletAddress,
         *         "timestamp" to System.currentTimeMillis() / 1000
         *     ),
         *     account = walletAddress
         * )
         * ```
         */
        fun create(
            schemaData: Map<String, Any>,
            account: String = "",
            recipient: String = "",
            expireAt: Long = 0L,
            commitment: Commitment = Commitment.finalized
        ): ImageHashPayload {
            val dataHash = computeDataHash(schemaData)
            
            // Extract fields from schemaData with sensible defaults
            val imageHashBytes = when (val v = schemaData["image_hash"] ?: schemaData["imageHash"]) {
                is ByteArray -> v
                is String -> v.chunked(2).mapNotNull { if (it.length == 2) it.toByteOrNull(16) else null }.toByteArray()
                else -> ByteArray(0)
            }
            
            val parentHashBytes = when (val v = schemaData["parent_hash"] ?: schemaData["parentHash"]) {
                is ByteArray -> v
                is String -> v.chunked(2).mapNotNull { if (it.length == 2) it.toByteOrNull(16) else null }.toByteArray()
                else -> null
            }
            
            val hashAlg = ((schemaData["hash_algorithm"] ?: schemaData["hashAlgorithm"]) as? String) ?: "sha256"
            val mimeType = ((schemaData["mime_type"] ?: schemaData["mime"]) as? String) ?: "image/png"
            val width = when (val w = schemaData["width"]) {
                is Number -> w.toInt()
                is String -> w.toIntOrNull() ?: 0
                else -> 0
            }
            val height = when (val h = schemaData["height"]) {
                is Number -> h.toInt()
                is String -> h.toIntOrNull() ?: 0
                else -> 0
            }
            val fileSize = when (val f = schemaData["file_size"] ?: schemaData["fileSize"]) {
                is Number -> f.toLong()
                is String -> f.toLongOrNull() ?: 0L
                else -> 0L
            }
            val filename = ((schemaData["filename"] ?: schemaData["file_name"]) as? String) ?: ""
            val owner = ((schemaData["owner"] ?: schemaData["producer"]) as? String) ?: ""
            val timestamp = when (val t = schemaData["timestamp"]) {
                is Number -> t.toLong()
                is String -> t.toLongOrNull() ?: (System.currentTimeMillis() / 1000)
                else -> System.currentTimeMillis() / 1000
            }
            
            return ImageHashPayload(
                schemaData = schemaData,
                dataHash = dataHash,
                account = account,
                recipient = recipient,
                expireAt = expireAt,
                commitment = commitment,
                imageHash = imageHashBytes,
                parentHash = parentHashBytes,
                hashAlgorithm = hashAlg,
                mimeType = mimeType,
                width = width,
                height = height,
                fileSize = fileSize,
                filename = filename,
                owner = owner,
                timestamp = timestamp
            )
        }

        /**
         * Compatibility overload for file-based payload construction.
         * The resulting payload still centers on [schemaData].
         */
        fun create(
            filePath: String,
            mime: String = "image/png",
            filename: String = java.io.File(filePath).name,
            producer: String = "",
            account: String = "",
            recipient: String = "",
            expireAt: Long = 0L,
            commitment: Commitment = Commitment.finalized
        ): ImageHashPayload {
            val file = java.io.File(filePath)
            require(file.exists()) { "File does not exist: $filePath" }
            val imageBytes = file.readBytes()
            return createFromBytes(
                imageBytes = imageBytes,
                mime = mime,
                filename = filename,
                producer = producer,
                account = account,
                recipient = recipient,
                expireAt = expireAt,
                commitment = commitment
            )
        }

        /**
         * Compatibility overload for raw image bytes.
         */
        fun createFromBytes(
            imageBytes: ByteArray,
            mime: String = "image/png",
            filename: String = "",
            producer: String = "",
            account: String = "",
            recipient: String = "",
            expireAt: Long = 0L,
            commitment: Commitment = Commitment.finalized
        ): ImageHashPayload {
            val schemaData = linkedMapOf<String, Any>(
                "image_hash" to imageBytes,
                "hash_algorithm" to "sha256",
                "mime_type" to mime,
                "filename" to filename,
                "owner" to producer,
                "timestamp" to (System.currentTimeMillis() / 1000)
            )
            return create(
                schemaData = schemaData,
                account = account,
                recipient = recipient,
                expireAt = expireAt,
                commitment = commitment
            ).copy(
                imageHash = imageBytes,
                hashAlgorithm = "sha256",
                mimeType = mime,
                filename = filename,
                owner = producer
            )
        }

        /**
         * Helper to compute image file hash from file path.
         * Returns SHA-256 hex string of the file bytes.
         */
        fun computeImageHash(filePath: String): String {
            val file = java.io.File(filePath)
            require(file.exists()) { "File does not exist: $filePath" }
            return file.inputStream().use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        }

        /**
         * Helper to compute image hash from raw bytes.
         * Returns SHA-256 hex string.
         */
        fun computeImageHashFromBytes(imageBytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(imageBytes)
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Compute SHA-256 hash of the schema data map.
         * Serializes the map to canonical JSON and hashes it.
         */
        private fun computeDataHash(data: Map<String, Any>): String {
            val jsonString = canonicalJsonString(data)
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(jsonString.toByteArray(Charsets.UTF_8))
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun canonicalJsonString(value: Any?): String = when (value) {
            null -> "null"
            is String -> org.json.JSONObject.quote(value)
            is Number, is Boolean -> value.toString()
            is ByteArray -> org.json.JSONObject.quote(value.joinToString(separator = "") { "%02x".format(it) })
            is Map<*, *> -> value.entries
                .sortedBy { it.key?.toString().orEmpty() }
                .joinToString(prefix = "{", postfix = "}") { (key, entryValue) ->
                    "${org.json.JSONObject.quote(key?.toString().orEmpty())}:${canonicalJsonString(entryValue)}"
                }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { canonicalJsonString(it) }
            is Array<*> -> canonicalJsonString(value.toList())
            else -> org.json.JSONObject.quote(value.toString())
        }
    }
}

// ── Wire types ────────────────────────────────────────────────────────────────

// CreateSchemaRequest removed — schema creation is handled out-of-band

/**
 * JSON body sent to `POST api/provenance/attest`.
 * Sent for every image attestation after the schema is confirmed.
 */
@Serializable
data class AttestRequest(
    /** Base64-encoded signed `createAttestation` Solana transaction. */
    val signedTransaction: String
)

/**
 * Response from both `POST api/provenance/createSchema` and `POST api/provenance/attest`.
 * Backend returns the same flexible structure for both endpoints with optional fields.
 *
 * Example response:
 * ```json
 * {
 *   "Signature": "5Xq...",
 *   "Message": "Attestation created successfully",
 *   "Status": "Queued",
 *   "SchemaId": "",
 *   "AttestationId": ""
 * }
 * ```
 */
@Serializable
data class ProvenanceResponse(
    val Status: String,
    val Message: String,
    val Signature: String = "",
    val SchemaId: String = "",
    val AttestationId: String = ""
)

// Backward compatibility alias
typealias ImageHashResponse = ProvenanceResponse

/**
 * Returned to the SDK user after a successful attestation.
 *
 * Contains:
 * - [response]       — the on-chain result (attestation PDA, tx signature)
 * - [dataHash]       — hash of the attested schema data
 * - [certificate]    — signed [ProvenanceCertificate] with device metadata
 *
 * ```kotlin
 * val pr = Provenance.attestImageHash(payload, credentialName, schemaName).getOrThrow()
 *
 * // On-chain
 * pr.response.attestationId       // Solana Attestation PDA
 * pr.response.Signature           // tx signature
 *
 * // Data hash
 * pr.dataHash                     // SHA-256 of schema data — what's on-chain
 * ```
 */
data class ProvenanceResult(
    /**
     * On-chain attestation result from the backend.
     * `null` when the device was offline and the attestation has been queued locally —
     * check [isQueued] to distinguish this case.
     */
    val response: ProvenanceResponse? = null,
    /** Hash of the attested schema data (SHA-256 hex). */
    val dataHash: String = "",
    /**
     * Solana Attestation PDA (Base58) — derived deterministically client-side
     * from seeds ["attestation", credential, schema, nonce] before the tx is sent.
     * Use this with [com.altude.provenance.Provenance.verifyOnChain].
     * Also stored inside the sidecar `.json` and embedded image metadata.
     */
    val attestationId: String = "",
    /**
     * Signed [ProvenanceCertificate] — contains the ED25519 signature over the 
     * schema data, device metadata, and optional GPS coordinates.
     */
    val certificate: ProvenanceCertificate? = null,
    /**
     * `true` when the device was offline at attestation time.
     * Call [com.altude.provenance.Provenance.submitPending] when back online.
     */
    val isQueued: Boolean = false,
    /**
     * Queue entry UUID. Non-null when [isQueued] is `true`.
     */
    val queueId: String? = null
)

/**
 * Per-image result emitted by [com.altude.provenance.Provenance.attestBatch].
 *
 * @param index  Zero-based position in the original list passed to `attestBatch`.
 * @param name   Original filename — use to match back to your UI list.
 * @param hash   The data hash stored on-chain.
 * @param result Success carries [ProvenanceResult]; failure carries the error.
 */
data class AttestationResult(
    val index:  Int,
    val name:   String,
    val hash:   String,
    val result: Result<ProvenanceResult>
)

// ── Verification ──────────────────────────────────────────────────────────────

/**
 * Raw JSON response from `GET api/provenance/verify`.
 * Deserialised internally — SDK users receive [VerifyResult] instead.
 */
@Serializable
internal data class VerifyResponse(
    /** `"verified"`, `"not_found"`, or `"tampered"`. */
    val Status: String,
    val Message: String,
    /** On-chain attestation PDA (Base58). */
    val attestationId: String = "",
    /** Hash stored on-chain — compare with local dataHash. */
    val onChainHash: String = "",
    /**
     * [ProvenanceCertificate] JSON as stored by the backend at attestation time.
     * Parsed into [VerifyResult.certificate] automatically.
     */
    val certificate: String = ""
)

/**
 * Result returned by [com.altude.provenance.Provenance.verifyByHash] and
 * [com.altude.provenance.Provenance.verifyByAttestationId].
 *
 * **Online verification flow:**
 * 1. The backend is queried with the data hash or attestation PDA.
 * 2. The backend returns the on-chain hash and the stored certificate.
 * 3. The SDK parses the certificate and returns this object.
 *
 * **What to check after receiving a [VerifyResult]:**
 * ```kotlin
 * val v = Provenance.verifyByHash(payload.dataHash).getOrThrow()
 *
 * // 1. On-chain status
 * check(v.isVerified) { v.message }
 *
 * // 2. Hash matches what you computed locally
 * check(v.onChainHash == payload.dataHash)
 *
 * // 3. Certificate signer is the expected wallet
 * check(v.certificate?.signerAddress == expectedWallet)
 *
 * // 4. Optional: offline re-verify the ED25519 signature without network
 * //    See ProvenanceCertificate KDoc for snippet
 * ```
 *
 * @property isVerified     `true` when [Status] == `"verified"`.
 * @property status         Raw status string from the backend.
 * @property message        Human-readable description.
 * @property attestationId  On-chain Attestation PDA (Base58).
 * @property onChainHash    The data hash stored on Solana.
 * @property certificate    Parsed [ProvenanceCertificate], or `null` if absent/malformed.
 */
data class VerifyResult(
    val isVerified:    Boolean,
    val status:        String,
    val message:       String,
    val attestationId: String,
    val onChainHash:   String,
    val certificate:   ProvenanceCertificate?
)

// ── Offline queue results ─────────────────────────────────────────────────────

/**
 * Returned by [com.altude.provenance.Provenance.attestOffline].
 *
 * The data has been signed and queued locally — call
 * [com.altude.provenance.Provenance.submitPending] when back online to submit
 * everything to the Solana chain in one efficient batch.
 *
 * @property queueId           UUID identifying this entry in the pending queue.
 * @property certificate       Signed [ProvenanceCertificate] — already tamper-evident
 *                             even before on-chain submission.
 */
data class OfflineAttestResult(
    val queueId:           String,
    val certificate:       ProvenanceCertificate
)

/**
 * Per-image result emitted by [com.altude.provenance.Provenance.submitPending].
 *
 * @property queueId   The ID that was removed from the pending queue on success.
 * @property name      Original filename — use to match back to your UI list.
 * @property hash      [C2paManifest.manifestHash] stored on-chain.
 * @property result    Success carries [ProvenanceResult]; failure carries the error.
 *                     On failure the item **remains in the queue** for the next retry.
 */
data class SubmitResult(
    val queueId: String,
    val name:    String,
    val hash:    String,
    val result:  Result<ProvenanceResult>
)




