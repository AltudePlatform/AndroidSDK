package com.altude.provenance.data

import kotlinx.serialization.Serializable

/**
 * A queued attestation waiting to be submitted when the device is back online.
 *
 * Stored as JSON in SharedPreferences by [com.altude.provenance.ProvenanceQueue].
 * Created by [com.altude.provenance.Provenance.attestOffline] — the
 * [ProvenanceCertificate] is already signed at queue time so the credential is
 * tamper-evident even before it reaches the Solana chain.
 *
 * @property id                 UUID — used to dequeue after successful submission.
 * @property certificateJson    [ProvenanceCertificate.toJson()] — signed offline.
 * @property schemaDataJson     JSON string of the user-defined schema data.
 * @property queuedAtMs         UTC epoch ms when this entry was queued.
 */
@Serializable
data class PendingAttestation(
    val id:                 String,
    val type:               String,
    val hash:               String,
    val mime:               String,
    val name:               String,
    /** User-defined schema data as JSON string. */
    val manifest:           String,
    /** JSON representation of the schema data (same as manifest for compatibility). */
    val schemaDataJson:     String = "",
    val timestamp:          Long,
    val account:            String,
    val recipient:          String,
    val expireAt:           Long,
    /** [Commitment.name] stored as a plain string for serialization. */
    val commitment:         String,
    /** [ProvenanceCertificate.toJson()] — already ED25519-signed at queue time. */
    val certificateJson:    String,
    /** UTC epoch ms when this entry was added to the queue. */
    val queuedAtMs:         Long    = System.currentTimeMillis()
)
