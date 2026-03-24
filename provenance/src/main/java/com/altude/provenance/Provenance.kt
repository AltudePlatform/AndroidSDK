package com.altude.provenance

import com.altude.core.config.SdkConfig
import com.altude.core.service.StorageService
import com.altude.provenance.data.AttestationResult
import com.altude.provenance.data.C2paManifest
import com.altude.provenance.data.Commitment
import com.altude.provenance.data.ImageHashPayload
import com.altude.provenance.data.ImageHashRequest
import com.altude.provenance.data.ImageHashResponse
import com.altude.provenance.data.ManifestOption
import com.altude.provenance.data.OfflineAttestResult
import com.altude.provenance.data.PendingAttestation
import com.altude.provenance.data.ProvenanceCertificate
import com.altude.provenance.data.ProvenancePrefs
import com.altude.provenance.data.ProvenanceResult
import com.altude.provenance.data.SubmitResult
import com.altude.provenance.data.VerifyResponse
import com.altude.provenance.data.VerifyResult
import com.altude.provenance.interfaces.ProvenanceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
 * Schema is created once per wallet and cached — subsequent calls skip it entirely.
 *
 * ── Where does filePath come from? ───────────────────────────────────────────
 *
 * 1. Camera capture (saves to a File you control):
 * ```kotlin
 * val file = File(context.filesDir, "photo.jpg")
 * val uri  = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
 * // pass uri to camera intent via MediaStore.EXTRA_OUTPUT
 * // after capture:
 * val payload = ImageHashPayload.create(filePath = file.absolutePath, account = wallet)
 * ```
 *
 * 2. Gallery / document picker (returns a content:// URI):
 * ```kotlin
 * // in onActivityResult / ActivityResultCallback:
 * val uri: Uri = result.data?.data ?: return
 * val payload = ImageHashPayload.create(uri = uri, contentResolver = contentResolver, account = wallet)
 * ```
 *
 * 3. File already on disk (downloads, app storage):
 * ```kotlin
 * val file = File(context.filesDir, "image.png")
 * val payload = ImageHashPayload.create(filePath = file.absolutePath, account = wallet)
 * ```
 *
 * Single image:
 * ```kotlin
 * val result = Provenance.attestImageHash(payload)
 * val id     = result.getOrThrow().attestationId
 * ```
 *
 * Bulk images (1 schema tx, N attestation txs, sequential):
 * ```kotlin
 * viewModelScope.launch {
 *     Provenance.attestBatch(payloads).collect { item ->
 *         // item.index, item.name, item.hash, item.result
 *     }
 * }
 * ```
 */
object Provenance {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient         = true
        encodeDefaults    = true
        explicitNulls     = false
    }

    private fun service(): ProvenanceService =
        SdkConfig.createService(ProvenanceService::class.java)

    // ── Single image ──────────────────────────────────────────────────────────

    /**
     * Attests a single image on-chain.
     *
     * @param payload        Built via [ImageHashPayload.create].
     * @param manifestOption How to save the manifest. Default: [ManifestOption.SidecarFile].
     *
     * Options:
     * - [ManifestOption.SidecarFile]   — saves `{name}.c2pa.json` in `filesDir/provenance_manifests/`
     * - [ManifestOption.EmbedInImage]  — embeds manifest into JPEG XMP or PNG tEXt chunk
     * - [ManifestOption.Both]          — saves sidecar file AND embeds in image
     * - [ManifestOption.None]          — no file saved; manifest still in [ProvenanceResult.manifest]
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun attestImageHash(
        payload:        ImageHashPayload,
        manifestOption: ManifestOption = ManifestOption.SidecarFile
    ): Result<ProvenanceResult> = withContext(Dispatchers.IO) {
        try {
            // 1. Ensure schema
            val schemaResult = ProvenanceManager.ensureSchema(
                account    = payload.account,
                commitment = payload.commitment.name
            )
            if (schemaResult.isFailure) {
                val cause = schemaResult.exceptionOrNull()!!
                if (isNetworkError(cause))
                    return@withContext queueOffline(payload, manifestOption)
                return@withContext Result.failure(cause)
            }

            // 2. Derive Schema PDA
            val schemaPda = ProvenanceManager.deriveSchemaAddress(payload.account)

            // 3. Sign attestation tx + build ProvenanceCertificate offline
            val attestResult = ProvenanceManager.attest(payload, schemaPda)
            if (attestResult.isFailure) {
                val cause = attestResult.exceptionOrNull()!!
                if (isNetworkError(cause))
                    return@withContext queueOffline(payload, manifestOption)
                return@withContext Result.failure(cause)
            }

            val attested = attestResult.getOrThrow()

            // 4. Submit
            val request = ImageHashRequest(
                type                = payload.type,
                hash                = payload.hash,
                mime                = payload.mime,
                name                = payload.name,
                timestamp           = payload.timestamp,
                account             = payload.account,
                recipient           = payload.recipient,
                expireAt            = payload.expireAt,
                manifest            = payload.manifest,
                signedSchemaTx      = schemaResult.getOrNull(),
                signedAttestationTx = attested.signedTx,
                certificate         = attested.certificate.toJson()
            )
            val res      = service().attestImageHash(request).await()
            val response = decodeJson<ImageHashResponse>(res)

            // 5. Mark schema confirmed ONLY after backend success
            if (response.Status == "success") {
                val walletKey = ProvenanceManager.getKeyPair(payload.account).publicKey.toBase58()
                ProvenancePrefs.markSchemaCreated(walletKey)
            }

            // 6. Apply chosen manifest option — pass certificate so the file includes the signature
            val (manifestFile, embeddedImageFile) =
                applyManifestOption(payload.c2paManifest, manifestOption, attested.certificate)

            Result.success(ProvenanceResult(
                response          = response,
                manifest          = payload.c2paManifest,
                certificate       = attested.certificate,
                manifestFile      = manifestFile,
                embeddedImageFile = embeddedImageFile
            ))
        } catch (e: Throwable) {
            // Any unhandled network error at the backend call stage
            if (isNetworkError(e))
                return@withContext queueOffline(payload, manifestOption)
            Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }

    /**
     * Builds a local certificate, applies the manifest option, and enqueues the
     * attestation for later submission — no network required.
     * Returns a [ProvenanceResult] with [ProvenanceResult.isQueued] == `true`.
     */
    private suspend fun queueOffline(
        payload:        ImageHashPayload,
        manifestOption: ManifestOption
    ): Result<ProvenanceResult> {
        val offline = attestOffline(payload, manifestOption)
            .getOrElse { return Result.failure(it) }
        return Result.success(
            ProvenanceResult(
                response          = null,
                manifest          = payload.c2paManifest,
                certificate       = offline.certificate,
                manifestFile      = offline.manifestFile,
                embeddedImageFile = offline.embeddedImageFile,
                isQueued          = true,
                queueId           = offline.queueId
            )
        )
    }

    // ── Bulk images (performance-optimised) ───────────────────────────────────

    /**
     * Attests a list of images sequentially with **one keypair fetch** and
     * **one blockhash fetch per 30 s** — not one per image.
     *
     * - Schema tx: once per wallet (cached after first success).
     * - Keypair:   fetched once before the loop.
     * - Blockhash: fetched once; automatically refreshed every 30 s so it never
     *              expires during a long batch (Solana validity ~90 s).
     *
     * Emits one [AttestationResult] per image as it completes so the UI can show
     * progress without waiting for the whole batch.
     *
     * ```kotlin
     * viewModelScope.launch {
     *     Provenance.attestBatch(payloads).collect { item ->
     *         // item.index, item.name, item.hash, item.result
     *     }
     * }
     * ```
     */
    fun attestBatch(
        payloads:       List<ImageHashPayload>,
        manifestOption: ManifestOption = ManifestOption.SidecarFile
    ): Flow<AttestationResult> = flow {
        if (payloads.isEmpty()) return@flow

        val first = payloads.first()

        // ── 1. Schema — once per wallet ───────────────────────────────────────
        val schemaResult = ProvenanceManager.ensureSchema(
            account    = first.account,
            commitment = first.commitment.name
        )
        if (schemaResult.isFailure) {
            val cause = schemaResult.exceptionOrNull()!!
            if (isNetworkError(cause)) {
                // Offline — queue all items locally and return immediately
                payloads.forEachIndexed { i, p ->
                    val offlineResult = runCatching {
                        val offline = attestOffline(p, manifestOption).getOrThrow()
                        ProvenanceResult(
                            manifest          = p.c2paManifest,
                            certificate       = offline.certificate,
                            manifestFile      = offline.manifestFile,
                            embeddedImageFile = offline.embeddedImageFile,
                            isQueued          = true,
                            queueId           = offline.queueId
                        )
                    }
                    emit(AttestationResult(i, p.name, p.hash, offlineResult))
                }
                return@flow
            }
            payloads.forEachIndexed { i, p ->
                emit(AttestationResult(i, p.name, p.hash,
                    Result.failure(cause)))
            }
            return@flow
        }

        // ── 2. Keypair + PDA — once per batch ─────────────────────────────────
        val keypair   = ProvenanceManager.getKeyPair(first.account)
        val walletKey = keypair.publicKey.toBase58()
        val schemaPda = ProvenanceManager.deriveSchemaAddress(first.account)
        var schemaMarked = ProvenancePrefs.isSchemaCreated(walletKey)

        // ── 3. Blockhash — once; refreshed every 30 s ─────────────────────────
        var blockhash          = ProvenanceManager.fetchBlockhash(first.commitment.name)
        var blockhashFetchedAt = System.currentTimeMillis()

        // ── 4. Sequential loop ─────────────────────────────────────────────────
        payloads.forEachIndexed { index, payload ->
            // Refresh blockhash if older than 30 s to stay well within validity window
            if (System.currentTimeMillis() - blockhashFetchedAt > 30_000L) {
                try {
                    blockhash          = ProvenanceManager.fetchBlockhash(payload.commitment.name)
                    blockhashFetchedAt = System.currentTimeMillis()
                } catch (e: Throwable) {
                    // Network gone mid-batch — queue remaining items and stop submitting
                    if (isNetworkError(e)) {
                        payloads.drop(index).forEachIndexed { i, p ->
                            val offlineResult = runCatching {
                                val offline = attestOffline(p, manifestOption).getOrThrow()
                                ProvenanceResult(
                                    manifest          = p.c2paManifest,
                                    certificate       = offline.certificate,
                                    manifestFile      = offline.manifestFile,
                                    embeddedImageFile = offline.embeddedImageFile,
                                    isQueued          = true,
                                    queueId           = offline.queueId
                                )
                            }
                            emit(AttestationResult(index + i, p.name, p.hash, offlineResult))
                        }
                        return@flow
                    }
                }
            }

            val itemResult = runCatching {
                val attested = ProvenanceManager
                    .attestWithPrefetched(payload, schemaPda, keypair, blockhash)
                    .getOrThrow()

                val request = ImageHashRequest(
                    type                = payload.type,
                    hash                = payload.hash,
                    mime                = payload.mime,
                    name                = payload.name,
                    timestamp           = payload.timestamp,
                    account             = payload.account,
                    recipient           = payload.recipient,
                    expireAt            = payload.expireAt,
                    manifest            = payload.manifest,
                    signedSchemaTx      = if (index == 0) schemaResult.getOrNull() else null,
                    signedAttestationTx = attested.signedTx,
                    certificate         = attested.certificate.toJson()
                )
                val res      = service().attestImageHash(request).await()
                val response = decodeJson<ImageHashResponse>(res)

                if (!schemaMarked && index == 0 && response.Status == "success") {
                    ProvenancePrefs.markSchemaCreated(walletKey)
                    schemaMarked = true
                }

                // Apply chosen manifest option per image — include signature in file
                val (manifestFile, embeddedImageFile) =
                    applyManifestOption(payload.c2paManifest, manifestOption, attested.certificate)

                ProvenanceResult(
                    response          = response,
                    manifest          = payload.c2paManifest,
                    certificate       = attested.certificate,
                    manifestFile      = manifestFile,
                    embeddedImageFile = embeddedImageFile
                )
            }.recoverNetworkToOffline(payload, manifestOption)
            emit(AttestationResult(index, payload.name, payload.hash, itemResult))
        }
    }.flowOn(Dispatchers.IO)

    // ── Offline queue ─────────────────────────────────────────────────────────

    /**
     * Signs the image credential and saves it to the local pending queue —
     * **no network required**.
     *
     * Use when the device is offline or network reliability is uncertain.
     * The [ProvenanceCertificate] is fully signed at this point so the credential
     * is tamper-evident. Call [submitPending] when back online to broadcast to Solana.
     *
     * ```kotlin
     * // Offline — sign now, submit later
     * val offline = Provenance.attestOffline(payload).getOrThrow()
     * println("Queued: ${offline.queueId}")
     * println("Pending: ${Provenance.pendingCount()} images waiting")
     *
     * // Later, when online:
     * Provenance.submitPending().collect { item ->
     *     if (item.result.isSuccess) println("Submitted: ${item.name}")
     * }
     * ```
     *
     * @param payload        Built via [ImageHashPayload.create] — no network needed.
     * @param manifestOption Applied immediately (local file I/O only); saved in the
     *                       queue entry and re-applied after successful submission.
     */
    suspend fun attestOffline(
        payload:        ImageHashPayload,
        manifestOption: ManifestOption = ManifestOption.SidecarFile
    ): Result<OfflineAttestResult> = withContext(Dispatchers.IO) {
        runCatching {
            // 1. Get keypair from local storage — no network
            val keypair = ProvenanceManager.getKeyPair(payload.account)

            // 2. Build + sign certificate — pure crypto, no network
            val certificate = ProvenanceManager.buildCertificate(payload, keypair)

            // 3. Apply manifest option locally — include signature in file
            val (manifestFile, embeddedImageFile) =
                applyManifestOption(payload.c2paManifest, manifestOption, certificate)

            // 4. Serialise manifest option for storage
            val (optType, optPath) = when (manifestOption) {
                is ManifestOption.EmbedInImage -> "embed" to manifestOption.sourceFilePath
                is ManifestOption.Both         -> "both"  to manifestOption.sourceFilePath
                is ManifestOption.None         -> "none"  to ""
                else                           -> "sidecar" to ""
            }

            // 5. Enqueue
            val queueId = java.util.UUID.randomUUID().toString()
            ProvenanceQueue.enqueue(
                PendingAttestation(
                    id                 = queueId,
                    type               = payload.type,
                    hash               = payload.hash,
                    mime               = payload.mime,
                    name               = payload.name,
                    manifest           = payload.manifest,
                    c2paManifest       = payload.c2paManifest,
                    timestamp          = payload.timestamp,
                    account            = payload.account,
                    recipient          = payload.recipient,
                    expireAt           = payload.expireAt,
                    commitment         = payload.commitment.name,
                    latitude           = payload.latitude,
                    longitude          = payload.longitude,
                    certificateJson    = certificate.toJson(),
                    manifestOptionType = optType,
                    manifestOptionPath = optPath
                )
            )

            OfflineAttestResult(
                queueId           = queueId,
                certificate       = certificate,
                manifestFile      = manifestFile,
                embeddedImageFile = embeddedImageFile
            )
        }.mapFailure()
    }

    /**
     * Submits all pending (offline-queued) attestations to the Solana chain.
     *
     * Optimised the same way as [attestBatch]:
     * - Schema: once per wallet
     * - Keypair: fetched once
     * - Blockhash: fetched once, refreshed every 30 s
     *
     * Each item is removed from the queue **only on success** — failures remain
     * in the queue so they are retried on the next [submitPending] call.
     *
     * ```kotlin
     * // Call when the device comes back online
     * if (Provenance.pendingCount() > 0) {
     *     Provenance.submitPending().collect { item ->
     *         if (item.result.isSuccess) println("✓ ${item.name}")
     *         else println("✗ ${item.name}: ${item.result.exceptionOrNull()?.message}")
     *     }
     * }
     * ```
     */
    fun submitPending(): Flow<SubmitResult> = flow {
        val pending = ProvenanceQueue.getAll()
        if (pending.isEmpty()) return@flow

        val first = pending.first()

        // ── Schema — once ──────────────────────────────────────────────────────
        val schemaResult = ProvenanceManager.ensureSchema(
            account    = first.account,
            commitment = first.commitment
        )
        if (schemaResult.isFailure) {
            pending.forEach { p ->
                emit(SubmitResult(p.id, p.name, p.hash,
                    Result.failure(schemaResult.exceptionOrNull()!!)))
            }
            return@flow
        }

        // ── Keypair + PDA — once ───────────────────────────────────────────────
        val keypair   = ProvenanceManager.getKeyPair(first.account)
        val walletKey = keypair.publicKey.toBase58()
        val schemaPda = ProvenanceManager.deriveSchemaAddress(first.account)
        var schemaMarked = ProvenancePrefs.isSchemaCreated(walletKey)

        // ── Blockhash — once; refreshed every 30 s ────────────────────────────
        var blockhash          = ProvenanceManager.fetchBlockhash(first.commitment)
        var blockhashFetchedAt = System.currentTimeMillis()

        pending.forEachIndexed { index, entry ->
            if (System.currentTimeMillis() - blockhashFetchedAt > 30_000L) {
                blockhash          = ProvenanceManager.fetchBlockhash(entry.commitment)
                blockhashFetchedAt = System.currentTimeMillis()
            }

            val itemResult = runCatching {
                // Reconstruct a minimal ImageHashPayload from the queued entry
                val payload = ImageHashPayload(
                    type         = entry.type,
                    hash         = entry.hash,
                    mime         = entry.mime,
                    name         = entry.name,
                    manifest     = entry.manifest,
                    c2paManifest = entry.c2paManifest,
                    timestamp    = entry.timestamp,
                    account      = entry.account,
                    recipient    = entry.recipient,
                    expireAt     = entry.expireAt,
                    commitment   = Commitment.valueOf(entry.commitment),
                    latitude     = entry.latitude,
                    longitude    = entry.longitude
                )

                // Build Solana tx using the pre-fetched blockhash (cert already signed)
                val signedTx = ProvenanceManager.buildTx(payload, schemaPda, keypair, blockhash)
                val certificate = ProvenanceCertificate.fromJson(entry.certificateJson)

                val request = ImageHashRequest(
                    type                = entry.type,
                    hash                = entry.hash,
                    mime                = entry.mime,
                    name                = entry.name,
                    timestamp           = entry.timestamp,
                    account             = entry.account,
                    recipient           = entry.recipient,
                    expireAt            = entry.expireAt,
                    manifest            = entry.manifest,
                    signedSchemaTx      = if (index == 0) schemaResult.getOrNull() else null,
                    signedAttestationTx = signedTx,
                    certificate         = entry.certificateJson
                )
                val res      = service().attestImageHash(request).await()
                val response = decodeJson<ImageHashResponse>(res)

                if (!schemaMarked && index == 0 && response.Status == "success") {
                    ProvenancePrefs.markSchemaCreated(walletKey)
                    schemaMarked = true
                }

                // Remove from queue ONLY on success
                ProvenanceQueue.dequeue(entry.id)

                val manifestOption = entry.toManifestOption()
                val (manifestFile, embeddedImageFile) =
                    applyManifestOption(entry.c2paManifest, manifestOption, certificate)

                ProvenanceResult(
                    response          = response,
                    manifest          = entry.c2paManifest,
                    certificate       = certificate,
                    manifestFile      = manifestFile,
                    embeddedImageFile = embeddedImageFile
                )
            }
            // Failures stay in queue — do NOT dequeue on failure
            emit(SubmitResult(entry.id, entry.name, entry.hash, itemResult))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Returns the number of images waiting in the offline queue.
     * Check this when coming back online to decide whether to call [submitPending].
     */
    fun pendingCount(): Int = ProvenanceQueue.count()

    /**
     * Clears all pending queue entries for the current wallet.
     * Call on logout or wallet switch — entries will NOT be submitted.
     */
    fun clearPending() = ProvenanceQueue.clear()

    // ── Session ───────────────────────────────────────────────────────────────

    /**
     * Clears the schema state + pending queue for [account].
     * Call on wallet switch or logout.
     */
    suspend fun resetSession(account: String = "") {
        ProvenanceManager.resetSchema(account)
        ProvenanceQueue.clear()
    }

    // ── Online verification ───────────────────────────────────────────────────

    /**
     * Verifies an attested image online by its manifest hash
     * ([C2paManifest.manifestHash] — stored on-chain).
     */
    suspend fun verifyByHash(hash: String): Result<VerifyResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw      = service().verifyByHash(hash).await()
                val response = decodeJson<VerifyResponse>(raw)
                response.toVerifyResult()
            }.mapFailure()
        }

    /**
     * Verifies an attested image online by its on-chain Attestation PDA (Base58).
     */
    suspend fun verifyByAttestationId(attestationId: String): Result<VerifyResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw      = service().verifyByAttestationId(attestationId).await()
                val response = decodeJson<VerifyResponse>(raw)
                response.toVerifyResult()
            }.mapFailure()
        }

    // ── Manifest option helper ────────────────────────────────────────────────

    /**
     * Applies the [option] to save or embed the manifest locally.
     *
     * When [certificate] is provided its [ProvenanceCertificate.toJson] (which includes
     * the ED25519 signature) is written instead of the plain [C2paManifest.toJson], making
     * the sidecar file / embedded image fully self-contained for offline verification.
     */
    private fun applyManifestOption(
        manifest:    C2paManifest,
        option:      ManifestOption,
        certificate: ProvenanceCertificate? = null
    ): Pair<java.io.File?, java.io.File?> {
        val json         = certificate?.toJson() ?: manifest.toJson()
        val manifestsDir = java.io.File(StorageService.getContext().filesDir, "provenance_manifests")
        return when (option) {
            is ManifestOption.SidecarFile -> {
                Pair(runCatching { manifest.saveTo(manifestsDir, json) }.getOrNull(), null)
            }
            is ManifestOption.EmbedInImage -> {
                Pair(null, runCatching {
                    manifest.embedInto(java.io.File(option.sourceFilePath), json)
                }.getOrNull())
            }
            is ManifestOption.Both -> {
                val sidecar  = runCatching { manifest.saveTo(manifestsDir, json) }.getOrNull()
                val embedded = runCatching {
                    manifest.embedInto(java.io.File(option.sourceFilePath), json)
                }.getOrNull()
                Pair(sidecar, embedded)
            }
            else -> Pair(null, null)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private inline fun <reified T> decodeJson(element: JsonElement): T =
        json.decodeFromJsonElement(element)

    private fun VerifyResponse.toVerifyResult() = VerifyResult(
        isVerified    = Status.equals("verified", ignoreCase = true),
        status        = Status,
        message       = Message,
        attestationId = attestationId,
        onChainHash   = onChainHash,
        certificate   = ProvenanceCertificate.fromJson(certificate)
    )

    private fun <T> Result<T>.mapFailure(): Result<T> =
        onFailure { e -> Result.failure<T>(Exception(e.message ?: e.javaClass.simpleName, e)) }
            .let { this }

    /**
     * Returns `true` for any exception that indicates no network connectivity —
     * IO failures, DNS lookups, connection timeouts, and Retrofit/OkHttp errors.
     */
    private fun isNetworkError(e: Throwable): Boolean {
        if (e is java.io.IOException)              return true
        if (e is java.net.UnknownHostException)    return true
        if (e is java.net.SocketTimeoutException)  return true
        if (e is java.net.ConnectException)        return true
        if (e is java.net.SocketException)         return true
        val msg = e.message?.lowercase() ?: ""
        if (msg.contains("unable to resolve host"))    return true
        if (msg.contains("failed to connect"))         return true
        if (msg.contains("no address associated"))     return true
        if (msg.contains("network"))                   return true
        val cause = e.cause
        return cause != null && cause !== e && isNetworkError(cause)
    }

    /**
     * If this [Result] failed with a network error, signs the payload locally,
     * enqueues it, and returns a queued [ProvenanceResult] instead.
     * Non-network errors are propagated as-is.
     */
    private suspend fun Result<ProvenanceResult>.recoverNetworkToOffline(
        payload:        ImageHashPayload,
        manifestOption: ManifestOption
    ): Result<ProvenanceResult> {
        val err = exceptionOrNull() ?: return this
        if (!isNetworkError(err)) return this
        return runCatching {
            val offline = attestOffline(payload, manifestOption).getOrThrow()
            ProvenanceResult(
                manifest          = payload.c2paManifest,
                certificate       = offline.certificate,
                manifestFile      = offline.manifestFile,
                embeddedImageFile = offline.embeddedImageFile,
                isQueued          = true,
                queueId           = offline.queueId
            )
        }
    }
}
