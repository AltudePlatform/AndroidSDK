package com.altude.provenance

import android.content.Context
import androidx.core.content.edit
import com.altude.core.service.StorageService
import com.altude.provenance.data.PendingAttestation
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * Persists pending (offline-queued) image attestations across app restarts using
 * SharedPreferences. Each entry is a [PendingAttestation] serialised to JSON.
 *
 * Usage (internal — called by [Provenance]):
 * ```
 * ProvenanceQueue.enqueue(entry)      // add when offline
 * ProvenanceQueue.getAll()            // list all pending on next online check
 * ProvenanceQueue.dequeue(id)         // remove after successful backend submit
 * ProvenanceQueue.count()             // how many are waiting
 * ProvenanceQueue.clear()             // e.g. on logout
 * ```
 */
@OptIn(ExperimentalSerializationApi::class)
internal object ProvenanceQueue {

    private const val PREFS_NAME = "altude_provenance_queue"
    private const val KEY_QUEUE  = "pending_queue"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults    = true
        explicitNulls     = false
    }

    private fun prefs() = StorageService.getContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns all pending entries, oldest-first. Empty list if none. */
    fun getAll(): List<PendingAttestation> = runCatching {
        val raw = prefs().getString(KEY_QUEUE, null) ?: return emptyList()
        json.decodeFromString<List<PendingAttestation>>(raw)
    }.getOrDefault(emptyList())

    /** Adds [entry] to the end of the queue. */
    fun enqueue(entry: PendingAttestation) {
        val list = getAll().toMutableList().also { it.add(entry) }
        prefs().edit { putString(KEY_QUEUE, json.encodeToString(list)) }
    }

    /** Removes the entry with [id] after a successful backend submission. */
    fun dequeue(id: String) {
        val list = getAll().filterNot { it.id == id }
        prefs().edit { putString(KEY_QUEUE, json.encodeToString(list)) }
    }

    /** Number of pending attestations waiting to be submitted. */
    fun count(): Int = getAll().size

    /** Clears all pending entries — call on wallet switch or logout. */
    fun clear() = prefs().edit { remove(KEY_QUEUE) }
}

