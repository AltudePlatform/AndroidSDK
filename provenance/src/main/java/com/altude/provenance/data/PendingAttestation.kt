package com.altude.provenance.data

import kotlinx.serialization.Serializable

/**
 * A queued image attestation waiting to be submitted when the device is back online.
 *
 * Stored as JSON in SharedPreferences by [com.altude.provenance.ProvenanceQueue].
 * Created by [com.altude.provenance.Provenance.attestOffline] — the
 * [ProvenanceCertificate] is already signed at queue time so the credential is
 * tamper-evident even before the image reaches the Solana chain.
 *
 * @property id                 UUID — used to dequeue after successful submission.
 * @property certificateJson    [ProvenanceCertificate.toJson()] — signed offline.
 * @property c2paManifest       Full manifest object needed for manifest-option application
 *                              after the item is submitted.
 * @property manifestOptionType One of `"sidecar"`, `"embed"`, `"both"`, `"none"`.
 * @property manifestOptionPath Absolute file path for `"embed"` / `"both"` options.
 * @property queuedAtMs         UTC epoch ms when this entry was queued.
 */
@Serializable
data class PendingAttestation(
    val id:                 String,
    val type:               String,
    val hash:               String,
    val mime:               String,
    val name:               String,
    /** Full C2PA claim JSON — forwarded to backend for off-chain verification. */
    val manifest:           String,
    /** Full [C2paManifest] object — used when applying manifest option after submit. */
    val c2paManifest:       C2paManifest,
    val timestamp:          Long,
    val account:            String,
    val recipient:          String,
    val expireAt:           Long,
    /** [Commitment.name] stored as a plain string for serialization. */
    val commitment:         String,
    val latitude:           Double? = null,
    val longitude:          Double? = null,
    /** [ProvenanceCertificate.toJson()] — already ED25519-signed at queue time. */
    val certificateJson:    String,
    /** `"sidecar"` | `"embed"` | `"both"` | `"none"` */
    val manifestOptionType: String  = "sidecar",
    /** Absolute source file path — required for `"embed"` and `"both"` options. */
    val manifestOptionPath: String  = "",
    /** UTC epoch ms when this entry was added to the queue. */
    val queuedAtMs:         Long    = System.currentTimeMillis()
) {
    /** Reconstructs the [ManifestOption] from the stored type + path fields. */
    fun toManifestOption(): ManifestOption = when (manifestOptionType) {
        "embed" -> ManifestOption.EmbedInImage(manifestOptionPath)
        "both"  -> ManifestOption.Both(manifestOptionPath)
        "none"  -> ManifestOption.None
        else    -> ManifestOption.SidecarFile
    }
}

