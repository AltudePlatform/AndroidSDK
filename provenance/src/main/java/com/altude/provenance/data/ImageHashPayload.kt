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
    /** Always `"image_hash"`. */
    val type: String,
    /**
     * [C2paManifest.manifestHash] — SHA-256 of the canonical C2PA claim JSON.
     * Auto-computed by [create]; never set manually.
     */
    val hash: String,
    /** MIME type of the image (e.g. `"image/png"`). */
    val mime: String,
    /** Original filename derived from the file path. */
    val name: String,
    /** Full C2PA claim JSON — forwarded to backend for off-chain verification. */
    val manifest: String,
    /** Original [C2paManifest] object — returned to SDK user via [ProvenanceResult]. */
    internal val c2paManifest: C2paManifest,
    /** Unix epoch (seconds) when the manifest was created. */
    val timestamp: Long,
    /** Attester wallet address (Base58). Blank = stored default wallet. */
    val account: String,
    /** Recipient wallet (Base58). Defaults to attester if blank. */
    val recipient: String,
    /** Unix timestamp (seconds) when attestation expires. 0 = no expiry. */
    val expireAt: Long,
    /** Finality commitment to wait for. */
    val commitment: Commitment,
    /** Optional GPS latitude in decimal degrees. */
    val latitude: Double? = null,
    /** Optional GPS longitude in decimal degrees. */
    val longitude: Double? = null,
    /** Pre-built certificate (set internally after offline signing). */
    internal val certificate: ProvenanceCertificate? = null
) {
    companion object {

        /**
         * Creates an [ImageHashPayload] from a file path.
         *
         * **Where does `filePath` come from?**
         *
         * 1. **Camera capture** — save output to a File you control, pass its path:
         * ```kotlin
         * val file = File(context.filesDir, "photo.jpg")
         * val uri  = FileProvider.getUriForFile(context, "${packageName}.provider", file)
         * // pass uri to camera intent via MediaStore.EXTRA_OUTPUT, then after capture:
         * val payload = ImageHashPayload.create(filePath = file.absolutePath, account = wallet)
         * ```
         *
         * 2. **Gallery / document picker** — use the [Uri] overload instead:
         * ```kotlin
         * val payload = ImageHashPayload.create(uri = pickedUri, contentResolver = contentResolver, account = wallet)
         * ```
         *
         * 3. **File already on disk** (downloads, app storage):
         * ```kotlin
         * val payload = ImageHashPayload.create(filePath = File(filesDir, "image.png").absolutePath, account = wallet)
         * ```
         *
         * Internally builds a [C2paManifest]:
         * 1. Reads bytes from [filePath]
         * 2. SHA-256(bytes)     → [C2paManifest.assetHash]
         * 3. Builds canonical C2PA claim JSON
         * 4. SHA-256(claimJson) → [C2paManifest.manifestHash] ← stored on-chain
         *
         * @param filePath  Absolute path to the image file on device.
         * @param mime      MIME type e.g. `"image/png"`, `"image/jpeg"`.
         * @param producer  Wallet address or app name embedded in the C2PA claim.
         * @param account   Attester wallet (Base58). Blank = stored default wallet.
         */
        fun create(
            filePath:   String,
            mime:       String     = "image/png",
            producer:   String     = "",
            account:    String     = "",
            recipient:  String     = "",
            expireAt:   Long       = 0L,
            commitment: Commitment = Commitment.finalized,
            latitude:   Double?    = null,
            longitude:  Double?    = null
        ): ImageHashPayload {
            val manifest = C2paManifest.build(
                filePath  = filePath,
                mimeType  = mime,
                producer  = producer
            )
            return ImageHashPayload(
                type         = "image_hash",
                hash         = manifest.manifestHash,
                mime         = manifest.mimeType,
                name         = manifest.filename,
                manifest     = manifest.toJson(),
                c2paManifest = manifest,
                timestamp    = manifest.timestamp,
                account      = account,
                recipient    = recipient,
                expireAt     = expireAt,
                commitment   = commitment,
                latitude     = latitude,
                longitude    = longitude
            )
        }

        /**
         * Creates an [ImageHashPayload] from a **gallery / document picker URI** (`content://`).
         *
         * Use this when the user picks an image from the gallery or files app —
         * Android returns a `content://` URI that cannot be used as a file path directly.
         * This overload reads the bytes via [ContentResolver] and builds the C2PA manifest.
         *
         * ```kotlin
         * // In ActivityResultCallback from photo picker / ACTION_GET_CONTENT:
         * val payload = ImageHashPayload.create(
         *     uri             = pickedUri,
         *     contentResolver = contentResolver,
         *     mime            = contentResolver.getType(pickedUri) ?: "image/jpeg",
         *     account         = walletAddress
         * )
         * val result = Provenance.attestImageHash(payload)
         * ```
         *
         * @param uri             Content URI returned by gallery / document picker.
         * @param contentResolver From `Activity.contentResolver` or `Context.contentResolver`.
         * @param mime            MIME type — use `contentResolver.getType(uri)` if unsure.
         * @param producer        Wallet address or app name embedded in the C2PA claim.
         * @param account         Attester wallet (Base58). Blank = stored default wallet.
         */
        fun create(
            uri:             Uri,
            contentResolver: ContentResolver,
            mime:            String     = "image/jpeg",
            producer:        String     = "",
            account:         String     = "",
            recipient:       String     = "",
            expireAt:        Long       = 0L,
            commitment:      Commitment = Commitment.finalized,
            latitude:        Double?    = null,
            longitude:       Double?    = null
        ): ImageHashPayload {
            val imageBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("C2PA: cannot open URI $uri")

            // Derive a best-effort filename from the URI's last path segment
            val filename = uri.lastPathSegment?.substringAfterLast('/') ?: "image"

            val manifest = C2paManifest.buildFromBytes(
                imageBytes = imageBytes,
                mimeType   = mime,
                filename   = filename,
                producer   = producer
            )
            return ImageHashPayload(
                type         = "image_hash",
                hash         = manifest.manifestHash,
                mime         = manifest.mimeType,
                name         = manifest.filename,
                manifest     = manifest.toJson(),
                c2paManifest = manifest,
                timestamp    = manifest.timestamp,
                account      = account,
                recipient    = recipient,
                expireAt     = expireAt,
                commitment   = commitment,
                latitude     = latitude,
                longitude    = longitude
            )
        }

        /**
         * Creates a payload from an already-built [C2paManifest].
         * Use this when you want to inspect manifest fields before attesting.
         *
         * ```kotlin
         * val manifest = C2paManifest.build(filePath = file.absolutePath, producer = wallet)
         * println("assetHash:    ${manifest.assetHash}")
         * println("manifestHash: ${manifest.manifestHash}")
         * val payload = ImageHashPayload.fromManifest(manifest, account = wallet)
         * ```
         */
        fun fromManifest(
            manifest:   C2paManifest,
            account:    String     = "",
            recipient:  String     = "",
            expireAt:   Long       = 0L,
            commitment: Commitment = Commitment.finalized,
            latitude:   Double?    = null,
            longitude:  Double?    = null
        ): ImageHashPayload = ImageHashPayload(
            type         = "image_hash",
            hash         = manifest.manifestHash,
            mime         = manifest.mimeType,
            name         = manifest.filename,
            manifest     = manifest.toJson(),
            c2paManifest = manifest,
            timestamp    = manifest.timestamp,
            account      = account,
            recipient    = recipient,
            expireAt     = expireAt,
            commitment   = commitment,
            latitude     = latitude,
            longitude    = longitude
        )
    }
}

// ── Wire types ────────────────────────────────────────────────────────────────

/**
 * JSON body sent to `POST api/provenance/createSchema`.
 * Only called once per wallet — when the schema account does not yet exist on-chain.
 */
@Serializable
data class CreateSchemaRequest(
    /** Base64-encoded signed `createSchema` Solana transaction. */
    val signedTransaction: String,
    val solanaClusterId: Int = 3
)

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
     * from (schemaPda, attester, recipient) before the tx is sent.
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




