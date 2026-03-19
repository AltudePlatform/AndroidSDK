package com.altude.provenance.data

import android.content.Context
import androidx.core.content.edit
import com.altude.core.service.StorageService

/**
 * Persists per-wallet schema-creation state across app restarts using SharedPreferences.
 *
 * Schema is a one-time on-chain setup per wallet — once confirmed it never needs
 * to be created again. This class remembers that across app restarts so we never
 * waste a transaction build or blockhash fetch for a schema that already exists.
 *
 * Uses [StorageService.getContext] — same context pattern as the rest of the SDK.
 */
internal object ProvenancePrefs {

    private const val PREFS_NAME    = "altude_provenance"
    private const val SCHEMA_PREFIX = "schema_created_"

    private fun prefs() = StorageService.getContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns `true` if [walletAddress] schema has already been confirmed on-chain.
     * When true, `ensureSchema` in ProvenanceManager skips building the tx entirely.
     */
    fun isSchemaCreated(walletAddress: String): Boolean =
        prefs().getBoolean("$SCHEMA_PREFIX$walletAddress", false)

    /**
     * Marks the schema as confirmed for [walletAddress].
     * Must be called ONLY after the backend returns Status == "success".
     */
    fun markSchemaCreated(walletAddress: String) =
        prefs().edit { putBoolean("$SCHEMA_PREFIX$walletAddress", true) }

    /**
     * Clears the schema flag for [walletAddress].
     * Call on wallet switch or logout via Provenance.resetSession().
     */
    fun reset(walletAddress: String) =
        prefs().edit { remove("$SCHEMA_PREFIX$walletAddress") }
}

