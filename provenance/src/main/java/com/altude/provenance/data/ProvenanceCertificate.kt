package com.altude.provenance.data

import org.json.JSONObject

/**
 * A C2PA-compatible provenance certificate for a single captured/attested image.
 *
 * Extends the lightweight [C2paManifest] with:
 * - **ED25519 signature** of the canonical claim JSON (offline-verifiable without network)
 * - **Device metadata** — make, model, OS version (`stds.exif` assertion)
 * - **GPS location** — optional precise lat/lng (`c2pa.location.precise` assertion)
 * - **Deterministic JSON** — keys ordered alphabetically so the byte sequence is
 *   always identical for signing and verification
 *
 * ### C2PA assertion structure
 * - `c2pa.hash.data`        — SHA-256 of the raw image bytes; any byte-level modification
 *                             invalidates the hash and therefore the signature.
 * - `stds.exif`             — device make/model and OS version captured at attestation time.
 * - `c2pa.location.precise` — GPS coordinates (omitted when null).
 *
 * ### Verification (offline — no network required)
 * ```kotlin
 * // 1. Reconstruct the claim (all assertions, no signatureInfo block)
 * val claimBytes = certificate.toClaimJson().toByteArray(Charsets.UTF_8)
 * // 2. Verify ED25519 signature
 * val pubKeyBytes = certificate.signerPublicKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
 * val sigBytes    = certificate.signature.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
 * val valid = Ed25519.verify(sigBytes, claimBytes, pubKeyBytes)
 * // 3. Confirm image hash
 * val freshHash = MessageDigest.getInstance("SHA-256").digest(imageBytes)
 *     .joinToString("") { "%02x".format(it) }
 * val hashMatch = freshHash == certificate.imageSha256
 * ```
 *
 * ### Embedding / Storage
 * The certificate JSON ([toJson]) is:
 * - Sent to the backend as part of [ImageHashRequest.certificate] for off-chain storage.
 * - Returned in [ProvenanceResult.certificate] to the SDK user.
 * - Optionally embedded into the image file via [C2paManifest.embedInto] or saved as a
 *   sidecar file via [C2paManifest.saveTo].
 *
 * @property instanceId          Unique manifest URI (urn:uuid:…).
 * @property captureTimestampMs  UTC epoch milliseconds at the moment of attestation.
 * @property imageSha256         SHA-256 hex of the raw image bytes (from [C2paManifest.assetHash]).
 * @property signerAddress       Altude/Solana wallet address (Base58 public key).
 * @property signerPublicKey     Hex-encoded 32-byte ED25519 public key; embedded for offline verification.
 * @property signature           Hex-encoded 64-byte ED25519 signature (RFC 8032) over the
 *                               bytes of [toClaimJson].
 * @property deviceMake          Device manufacturer (`Build.MANUFACTURER`).
 * @property deviceModel         Device model name (`Build.MODEL`).
 * @property osVersion           Android release version (`Build.VERSION.RELEASE`).
 * @property latitude            GPS latitude in decimal degrees, or null.
 * @property longitude           GPS longitude in decimal degrees, or null.
 */
data class ProvenanceCertificate(
    val instanceId: String,
    val captureTimestampMs: Long,
    val imageSha256: String,
    val signerAddress: String,
    val signerPublicKey: String,
    val signature: String,
    val deviceMake: String,
    val deviceModel: String,
    val osVersion: String,
    val latitude: Double?,
    val longitude: Double?,
) {

    // ── Instance methods ──────────────────────────────────────────────────────

    /**
     * Returns the canonical C2PA claim JSON — **all manifest fields except the signature**.
     *
     * This is the exact byte sequence that was signed:
     * `toClaimJson().toByteArray(Charsets.UTF_8)`
     *
     * Pass these bytes together with `signerPublicKey` and `signature` to any
     * standard ED25519 library to verify authenticity without network access.
     */
    fun toClaimJson(): String = buildManifestJson(includeSignature = false)

    /**
     * Serialises the complete certificate — claim + `signatureInfo` block — to JSON.
     * Use this for:
     * - Sending to the backend ([ImageHashRequest.certificate])
     * - Embedding in image EXIF / sidecar file
     */
    fun toJson(): String = buildManifestJson(includeSignature = true)

    /**
     * Single source of truth for the manifest JSON structure.
     *
     * All keys at every level are inserted in **alphabetical order** so that
     * `JSONObject` (backed by `LinkedHashMap`) always serialises them in the
     * same sequence — producing a deterministic byte sequence for signing.
     *
     * When [includeSignature] is `false` the `signatureInfo` block is omitted;
     * this is the exact canonical claim that was signed and that verifiers must
     * reconstruct independently.
     */
    private fun buildManifestJson(includeSignature: Boolean): String {
        val assertions = JSONObject()
        assertions.put(
            "c2pa.hash.data",
            JSONObject().apply {
                put("algorithm", "SHA-256")
                put("value", imageSha256)
            }
        )
        if (latitude != null && longitude != null) {
            assertions.put(
                "c2pa.location.precise",
                JSONObject().apply {
                    put("latitude", latitude)
                    put("longitude", longitude)
                }
            )
        }
        assertions.put(
            "stds.exif",
            JSONObject().apply {
                put("make", deviceMake)
                put("model", deviceModel)
                put("osVersion", osVersion)
            }
        )

        val root = JSONObject()
        root.put("assertions", assertions)
        root.put("captureTimestampMs", captureTimestampMs)
        root.put("claimGenerator", "altude-provenance-sdk/1.0")
        root.put("instanceId", instanceId)
        root.put("schema", "c2pa-v1")

        if (includeSignature) {
            root.put(
                "signatureInfo",
                JSONObject().apply {
                    put("algorithm", "Ed25519")
                    put("publicKey", signerPublicKey)
                    put("signature", signature)
                    put("signerAddress", signerAddress)
                }
            )
        }

        return root.toString()
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {

        /**
         * Parses a [ProvenanceCertificate] from the JSON string returned by
         * [com.altude.provenance.Provenance.verifyByHash] or
         * [com.altude.provenance.Provenance.verifyByAttestationId].
         *
         * Called automatically by the SDK — [VerifyResult.certificate] is already
         * a parsed object. Only use this directly if you have raw certificate JSON
         * from somewhere else (e.g. a sidecar `.c2pa.json` file).
         *
         * Returns `null` if the JSON is missing, blank, or malformed.
         */
        fun fromJson(json: String): ProvenanceCertificate? = runCatching {
            if (json.isBlank()) return null
            val root = JSONObject(json)

            val sig  = root.optJSONObject("signatureInfo")
            val exif = root.optJSONObject("assertions")?.optJSONObject("stds.exif")
            val loc  = root.optJSONObject("assertions")?.optJSONObject("c2pa.location.precise")
            val hash = root.optJSONObject("assertions")?.optJSONObject("c2pa.hash.data")

            ProvenanceCertificate(
                instanceId         = root.optString("instanceId"),
                captureTimestampMs = root.optLong("captureTimestampMs"),
                imageSha256        = hash?.optString("value")        ?: "",
                signerAddress      = sig?.optString("signerAddress") ?: "",
                signerPublicKey    = sig?.optString("publicKey")     ?: "",
                signature          = sig?.optString("signature")     ?: "",
                deviceMake         = exif?.optString("make")         ?: "",
                deviceModel        = exif?.optString("model")        ?: "",
                osVersion          = exif?.optString("osVersion")    ?: "",
                latitude           = if (loc != null && loc.has("latitude"))
                                         loc.getDouble("latitude") else null,
                longitude          = if (loc != null && loc.has("longitude"))
                                         loc.getDouble("longitude") else null,
            )
        }.getOrNull()
    }
}

