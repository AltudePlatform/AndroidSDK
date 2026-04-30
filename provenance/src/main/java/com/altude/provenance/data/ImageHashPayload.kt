package com.altude.provenance.data

import android.content.ContentResolver
import android.net.Uri
import kotlinx.serialization.Serializable

/**
 * Structured payload for an image-hash attestation.
 *
 * SDK users should NOT construct this directly — use the factory:
 * ```kotlin
 * val payload = ImageHashPayload.create(
 *     filePath  = file.absolutePath,
 *     mime      = "image/png",
 *     producer  = walletAddress,
 *     account   = walletAddress
 * )
 * val result = Provenance.attestImageHash(payload)
 * ```
 *
 * Internally, [create] builds a [C2paManifest] from the file path — the hash
 * and manifest JSON are computed automatically. The user never touches raw bytes
 * or SHA-256 directly.
 */
@ConsistentCopyVisibility
data class ImageHashPayload internal constructor(
    // Fixed schema fields (SAS devschema03)
    /** image_hash — raw bytes (vec<u8>) of the image SHA-256 digest. */
    val imageHash: ByteArray,
    /** parent_hash — optional raw bytes (vec<u8>) of parent asset. */
    val parentHash: ByteArray? = null,
    /** hash_algorithm — e.g. "sha256" */
    val hashAlgorithm: String = "sha256",
    /** mime_type — e.g. "image/png" */
    val mimeType: String = "image/png",
    /** width — u32 pixels */
    val width: Int = 0,
    /** height — u32 pixels */
    val height: Int = 0,
    /** file_size — u64 bytes */
    val fileSize: Long = 0L,
    /** filename — display filename */
    val filename: String = "",
    /** owner — owner address or identifier */
    val owner: String = "",
    /** timestamp — u64 epoch seconds */
    val timestamp: Long = System.currentTimeMillis() / 1000,

    // Legacy / SDK metadata (kept for internal flows)
    /** The canonical manifest hash (SHA-256 hex) stored on-chain. */
    val manifestHash: String = "",
    /** Original C2PA manifest object (optional, used when creating sidecars). */
    internal val c2paManifest: C2paManifest? = null,
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
    val certificateHash: String = ""
) {
    companion object {
        /** Build from file path (computes manifest and image hash). */
        fun create(
            filePath: String,
            mime: String = "image/png",
            producer: String = "",
            account: String = "",
            recipient: String = "",
            expireAt: Long = 0L,
            commitment: Commitment = Commitment.finalized
        ): ImageHashPayload {
            val manifest = C2paManifest.build(
                filePath = filePath,
                mimeType = mime,
                producer = producer
            )
            val imageHashBytes = try { hexToByteArray(manifest.assetHash) } catch (_: Exception) { manifest.assetHash.toByteArray(Charsets.UTF_8) }
            val fileSize = java.io.File(filePath).length()
            return ImageHashPayload(
                imageHash = imageHashBytes,
                parentHash = null,
                hashAlgorithm = "sha256",
                mimeType = manifest.mimeType,
                width = 0,
                height = 0,
                fileSize = fileSize,
                filename = manifest.filename,
                owner = producer,
                timestamp = manifest.timestamp,
                manifestHash = manifest.manifestHash,
                c2paManifest = manifest,
                account = account,
                recipient = recipient,
                expireAt = expireAt,
                commitment = commitment
            )
        }

        /** Build from raw bytes (camera buffer or content URI read bytes). */
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
            val manifest = C2paManifest.buildFromBytes(
                imageBytes = imageBytes,
                mimeType = mime,
                filename = filename,
                producer = producer
            )
            val imageHashBytes = try { hexToByteArray(manifest.assetHash) } catch (_: Exception) { manifest.assetHash.toByteArray(Charsets.UTF_8) }
            return ImageHashPayload(
                imageHash = imageHashBytes,
                parentHash = null,
                hashAlgorithm = "sha256",
                mimeType = manifest.mimeType,
                width = 0,
                height = 0,
                fileSize = imageBytes.size.toLong(),
                filename = manifest.filename,
                owner = producer,
                timestamp = manifest.timestamp,
                manifestHash = manifest.manifestHash,
                c2paManifest = manifest,
                account = account,
                recipient = recipient,
                expireAt = expireAt,
                commitment = commitment
            )
        }

        /** Build from an existing C2PA manifest. */
        fun fromManifest(
            manifest: C2paManifest,
            account: String = "",
            recipient: String = "",
            expireAt: Long = 0L,
            commitment: Commitment = Commitment.finalized
        ): ImageHashPayload {
            val imageHashBytes = try { hexToByteArray(manifest.assetHash) } catch (_: Exception) { manifest.assetHash.toByteArray(Charsets.UTF_8) }
            return ImageHashPayload(
                imageHash = imageHashBytes,
                parentHash = null,
                hashAlgorithm = "sha256",
                mimeType = manifest.mimeType,
                width = 0,
                height = 0,
                fileSize = 0L,
                filename = manifest.filename,
                owner = manifest.producer,
                timestamp = manifest.timestamp,
                manifestHash = manifest.manifestHash,
                c2paManifest = manifest,
                account = account,
                recipient = recipient,
                expireAt = expireAt,
                commitment = commitment
            )
        }

        private fun hexToByteArray(hex: String): ByteArray {
            val s = hex.removePrefix("0x").replace(" ", "")
            require(s.length % 2 == 0) { "Invalid hex string length" }
            return ByteArray(s.length / 2) { i ->
                val idx = i * 2
                ((s[idx].digitToInt(16) shl 4) + s[idx + 1].digitToInt(16)).toByte()
            }
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
 * - [response]           — the on-chain result (attestation PDA, tx signature)
 * - [manifest]           — the C2PA manifest object (assetHash, manifestHash, filename, etc.)
 * - [manifestFile]       — sidecar `.c2pa.json` file saved on device (if [ManifestOption.SidecarFile] or [ManifestOption.Both])
 * - [embeddedImageFile]  — the image file with manifest embedded in metadata (if [ManifestOption.EmbedInImage] or [ManifestOption.Both])
 *
 * ```kotlin
 * val pr = Provenance.attestImageHash(payload, ManifestOption.Both(filePath)).getOrThrow()
 *
 * // On-chain
 * pr.response.attestationId       // Solana Attestation PDA
 * pr.response.Signature           // tx signature
 *
 * // Manifest fields
 * pr.manifest.assetHash           // SHA-256 of raw image bytes
 * pr.manifest.manifestHash        // SHA-256 of claim JSON — what's on-chain
 * pr.manifest.filename            // "photo.png"
 *
 * // Sidecar file (ManifestOption.SidecarFile or Both)
 * pr.manifestFile?.absolutePath   // ".../provenance_manifests/photo.png.c2pa.json"
 *
 * // Embedded image (ManifestOption.EmbedInImage or Both)
 * pr.embeddedImageFile?.absolutePath  // the image file now carries the manifest in its metadata
 * ```
 */
data class ProvenanceResult(
    /**
     * On-chain attestation result from the backend.
     * `null` when the device was offline and the attestation has been queued locally —
     * check [isQueued] to distinguish this case.
     */
    val response: ProvenanceResponse? = null,
    /** The C2PA manifest built from the image. */
    val manifest: C2paManifest,
    /**
     * Solana Attestation PDA (Base58) — derived deterministically client-side
     * from seeds ["attestation", credential, schema, nonce] before the tx is sent.
     * Use this with [com.altude.provenance.Provenance.verifyOnChain].
     * Also stored inside the sidecar `.c2pa.json` and embedded image metadata.
     */
    val attestationId: String = "",
    /**
     * Signed [ProvenanceCertificate] — contains the ED25519 signature over the canonical
     * C2PA claim, device metadata, and optional GPS coordinates.
     */
    val certificate: ProvenanceCertificate? = null,
    /**
     * Sidecar `.c2pa.json` file saved on device.
     * Non-null when [ManifestOption.SidecarFile] or [ManifestOption.Both] was used.
     */
    val manifestFile: java.io.File? = null,
    /**
     * The image file with the C2PA manifest embedded in its metadata.
     * Non-null when [ManifestOption.EmbedInImage] or [ManifestOption.Both] was used.
     */
    val embeddedImageFile: java.io.File? = null,
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
 * @param hash   The [C2paManifest.manifestHash] stored on-chain.
 * @param result Success carries [ProvenanceResult] (manifest + response); failure carries the error.
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
    /** Hash stored on-chain — compare with local [C2paManifest.manifestHash]. */
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
 * 1. The backend is queried with the manifest hash or attestation PDA.
 * 2. The backend returns the on-chain hash and the stored certificate.
 * 3. The SDK parses the certificate and returns this object.
 *
 * **What to check after receiving a [VerifyResult]:**
 * ```kotlin
 * val v = Provenance.verifyByHash(payload.hash).getOrThrow()
 *
 * // 1. On-chain status
 * check(v.isVerified) { v.message }
 *
 * // 2. Hash matches what you computed locally
 * check(v.onChainHash == payload.hash)
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
 * @property onChainHash    [C2paManifest.manifestHash] stored on Solana.
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
 * The image has been signed and queued locally — call
 * [com.altude.provenance.Provenance.submitPending] when back online to submit
 * everything to the Solana chain in one efficient batch.
 *
 * @property queueId           UUID identifying this entry in the pending queue.
 * @property certificate       Signed [ProvenanceCertificate] — already tamper-evident
 *                             even before on-chain submission.
 * @property manifestFile      Sidecar `.c2pa.json` saved locally (if [ManifestOption.SidecarFile]).
 * @property embeddedImageFile Image file with manifest embedded (if [ManifestOption.EmbedInImage]).
 */
data class OfflineAttestResult(
    val queueId:           String,
    val certificate:       ProvenanceCertificate,
    val manifestFile:      java.io.File? = null,
    val embeddedImageFile: java.io.File? = null
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




