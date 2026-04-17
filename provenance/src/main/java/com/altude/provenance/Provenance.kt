package com.altude.provenance

import com.altude.core.config.SdkConfig
import com.altude.core.Programs.AttestationProgram
import com.altude.core.Programs.Utility
import com.altude.core.data.SolanaKeypair
import com.altude.core.model.HotSigner
import com.altude.core.service.StorageService
import com.altude.provenance.data.AttestRequest
import com.altude.provenance.data.AttestationResult
import com.altude.provenance.data.C2paManifest
import com.altude.provenance.data.Commitment
import com.altude.provenance.data.CreateSchemaRequest
import com.altude.provenance.data.ImageHashPayload
import com.altude.provenance.data.ProvenanceResponse
import com.altude.provenance.data.ManifestOption
import com.altude.provenance.data.OfflineAttestResult
import com.altude.provenance.data.PendingAttestation
import com.altude.provenance.data.ProvenanceCertificate
import com.altude.provenance.data.ProvenancePrefs
import com.altude.provenance.data.ProvenanceResult
import com.altude.provenance.data.SubmitResult
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

    // ── Schema PDA management ─────────────────────────────────────────────────

    /**
     * Sets a predefined schema PDA for the current wallet.
     * Use this when you already have a known schema PDA from a prior deployment.
     * Calling this skips the need to create a new schema during attestation.
     *
     * This is useful for:
     * - Testing on devnet with a pre-deployed schema
     * - Multiple apps using the same schema
     * - Restoring from a backup
     *
     * @param schemaPdaBase58 The schema PDA address in Base58 format
     * @param account Optional wallet account (uses default if empty)
     *
     * @return Result indicating success or validation failure
     *
     * Example:
     * ```kotlin
     * val result = Provenance.setSchemaPda(
     *     schemaPdaBase58 = "YOUR_SCHEMA_PDA_ADDRESS",
     *     account = ""  // uses default wallet
     * )
     * if (result.isSuccess) {
     *     // Now attestations will use this schema PDA directly
     *     Provenance.attestImageHash(payload)
     * }
     * ```
     */
    suspend fun setSchemaPda(
        schemaPdaBase58: String,
        account: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ProvenanceManager.setSchemaPda(schemaPdaBase58, account)
        }
    }

    /**
     * Resets the schema state for the current wallet.
     * Use this when switching wallets or if you need to re-create the schema.
     *
     * @param account Optional wallet account (uses default if empty)
     */
    suspend fun resetSchema(account: String = "") {
        ProvenanceManager.resetSchema(account)
    }

    /**
     * Ensures schema creation and returns the transaction string if needed.
     * This is a public wrapper for schema creation that can be used for debugging
     * or manual transaction submission.
     *
     * @param account Optional wallet account (uses default if empty)
     * @param commitment Transaction commitment level (default: "confirmed")
     * @return Result containing the Base64 transaction string if schema needs to be created,
     *         or null if schema already exists
     */
    suspend fun ensureSchemaTransaction(
        account: String = "",
        commitment: String = "confirmed"
    ): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            // Check if schema needs to be created and return the transaction if needed
            val result = ProvenanceManager.ensureSchema(account, commitment)
            
            if (result.isFailure) {
                throw result.exceptionOrNull()!!
            }
            
            // Return the schema transaction as-is (null if already exists)
            return@runCatching result.getOrNull()
        }
    }

    /**
     * Gets the schema PDA address for the given account.
     * This is useful for displaying the expected schema address in debugging UIs.
     *
     * @param account Optional wallet account (uses default if empty)
     * @return The schema PDA address in Base58 format
     */
    suspend fun getSchemaAddress(account: String = ""): String = withContext(Dispatchers.IO) {
        ProvenanceManager.deriveSchemaAddress(account).toBase58()
    }

    /**
     * Creates a schema on-chain for the given account.
     * 
     * This method will:
     * 1. Check if the schema already exists
     * 2. If not, create and submit a schema transaction
     * 3. Mark the schema as created in local preferences
     * 
     * @param account Optional wallet account (uses default if empty)
     * @param commitment Transaction commitment level (default: "confirmed")
     * @return Result containing the schema creation response, or an error if the operation failed
     * 
     * Example:
     * ```kotlin
     * val result = Provenance.createSchema(account = "")
     * if (result.isSuccess) {
     *     val response = result.getOrThrow()
     *     println("Schema created: ${response.Status}")
     * } else {
     *     println("Failed to create schema: ${result.exceptionOrNull()?.message}")
     * }
     * ```
     */
    suspend fun createSchema(
        account: String = "",
        commitment: String = "confirmed",
        credentialPda: String? = null,
        schemaName: String = ProvenanceManager.SCHEMA_NAME
    ): Result<ProvenanceResponse> = withContext(Dispatchers.IO) {
        runCatching {
            // 1. Check if schema needs to be created
            val schemaTxResult = if (credentialPda.isNullOrBlank()) {
                ProvenanceManager.ensureSchema(account, commitment)
            } else {
                ProvenanceManager.ensureSchemaForCredentialPdaInternal(
                    account = account,
                    commitment = commitment,
                    credentialPdaBase58 = credentialPda,
                    schemaName = schemaName
                )
            }

            if (schemaTxResult.isFailure) {
                throw schemaTxResult.exceptionOrNull()!!
            }
            
            val schemaTx = schemaTxResult.getOrNull()
            
            // 2. If schema already exists, return success response
            if (schemaTx == null) {
                return@runCatching ProvenanceResponse(
                    Status = "success",
                    Message = "Schema already exists"
                )
            }
            
            // 3. Submit the schema transaction directly
            val schemaRes = service().createSchema(CreateSchemaRequest(schemaTx)).await()
            val schemaResponse = runCatching {
                decodeJson<ProvenanceResponse>(schemaRes)
            }.getOrElse { e ->
                throw Exception("Failed to parse createSchema response: ${e.message}", e)
            }

            if (schemaResponse.Status != "success") {
                throw Exception("Schema creation failed: ${schemaResponse.Message}")
            }

            // 4. Mark schema as created in preferences
            val walletKey = ProvenanceManager.getKeyPair(account).publicKey.toBase58()
            ProvenancePrefs.markSchemaCreated(walletKey)

            schemaResponse
        }.mapFailure()
    }

    /**
     * Creates a credential on-chain for the given account and schema.
     *
     * @param name The credential name (string, e.g. "test-credential").
     * @param account Optional wallet account (uses default if empty)
     * @param commitment Transaction commitment level (default: "confirmed")
     * @return Result containing the credential creation response, or an error if the operation failed
     *
     * Example:
     * ```kotlin
     * val result = Provenance.createCredential(name = "test-credential")
     * if (result.isSuccess) {
     *     val response = result.getOrThrow()
     *     println("Credential created: ${response.Status}")
     * } else {
     *     println("Failed to create credential: ${result.exceptionOrNull()?.message}")
     * }
     * ```
     */
    suspend fun createCredential(
        name: String,
        account: String = "",
        commitment: String = "confirmed"
    ): Result<ProvenanceResponse> = withContext(Dispatchers.IO) {
        runCatching {
            // Build and sign the credential transaction using ProvenanceManager
            val signedTx = ProvenanceManager.buildCredentialTx(name, account, commitment)
            // Submit transaction to backend using the correct endpoint
            val res = service().createCredential(
                com.altude.provenance.data.CreateCredentialRequest(signedTx)
            ).await()
            val response = runCatching {
                decodeJson<ProvenanceResponse>(res)
            }.getOrElse { e ->
                throw Exception("Failed to parse createCredential response: ${e.message}", e)
            }

            response
        }.mapFailure()
    }

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
            // 1. Check whether schema needs to be created (SharedPrefs → on-chain RPC fallback)
            val schemaTxResult = ProvenanceManager.ensureSchema(
                account    = payload.account,
                commitment = payload.commitment.name
            )
            if (schemaTxResult.isFailure) {
                val cause = schemaTxResult.exceptionOrNull()!!
                if (isNetworkError(cause))
                    return@withContext queueOffline(payload, manifestOption)
                return@withContext Result.failure(cause)
            }

            // 2. If schema tx was built (schema didn't exist), submit it first and wait
            val schemaTx = schemaTxResult.getOrNull()
            if (schemaTx != null) {
                val schemaRes = runCatching {
                    service().createSchema(CreateSchemaRequest(schemaTx)).await()
                }.getOrElse { e ->
                    if (isNetworkError(e)) return@withContext queueOffline(payload, manifestOption)
                    return@withContext Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
                }
                val schemaResponse = runCatching {
                    decodeJson<ProvenanceResponse>(schemaRes)
                }.getOrElse { e ->
                    return@withContext Result.failure(Exception("Failed to parse createSchema response: ${e.message}", e))
                }
                if (schemaResponse.Status != "success")
                    return@withContext Result.failure(Exception("createSchema failed: ${schemaResponse.Message}"))
                // Mark schema confirmed after backend success
                val walletKey = ProvenanceManager.getKeyPair(payload.account).publicKey.toBase58()
                ProvenancePrefs.markSchemaCreated(walletKey)
            }

            // 3. Derive Schema PDA + sign attestation tx offline
            val schemaPda   = ProvenanceManager.deriveSchemaAddress(payload.account)
            val attestResult = ProvenanceManager.attest(payload, schemaPda)
            if (attestResult.isFailure) {
                val cause = attestResult.exceptionOrNull()!!
                if (isNetworkError(cause))
                    return@withContext queueOffline(payload, manifestOption)
                return@withContext Result.failure(cause)
            }
            val attested = attestResult.getOrThrow()

            // 4. Submit attestation tx
            val attestRes = runCatching {
                service().attest(AttestRequest(attested.signedTx)).await()
            }.getOrElse { e ->
                if (isNetworkError(e)) return@withContext queueOffline(payload, manifestOption)
                return@withContext Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
            }
            val response = runCatching {
                decodeJson<ProvenanceResponse>(attestRes)
            }.getOrElse { e ->
                return@withContext Result.failure(Exception("Failed to parse attest response: ${e.message}", e))
            }

            // 5. Apply chosen manifest option — inject attestationId for verifyOnChain
            val manifestWithId    = payload.c2paManifest.copy(attestationId = attested.attestationId)
            val certificateWithId = attested.certificate.copy(attestationId = attested.attestationId)
            val (manifestFile, embeddedImageFile) =
                applyManifestOption(manifestWithId, manifestOption, certificateWithId)

            Result.success(ProvenanceResult(
                response          = response,
                manifest          = manifestWithId,
                attestationId     = attested.attestationId,
                certificate       = certificateWithId,
                manifestFile      = manifestFile,
                embeddedImageFile = embeddedImageFile
            ))
        } catch (e: Throwable) {
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
        val schemaTxResult = ProvenanceManager.ensureSchema(
            account    = first.account,
            commitment = first.commitment.name
        )
        if (schemaTxResult.isFailure) {
            val cause = schemaTxResult.exceptionOrNull()!!
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
                emit(AttestationResult(i, p.name, p.hash, Result.failure(cause)))
            }
            return@flow
        }

        // ── 2. Submit createSchema if the schema didn't exist yet ─────────────
        val schemaTx = schemaTxResult.getOrNull()
        val keypair  = ProvenanceManager.getKeyPair(first.account)
        val walletKey = keypair.publicKey.toBase58()
        if (schemaTx != null) {
            runCatching {
                val schemaRes = service().createSchema(CreateSchemaRequest(schemaTx)).await()
                val schemaResponse = runCatching {
                    decodeJson<ProvenanceResponse>(schemaRes)
                }.getOrElse { throw Exception("Failed to parse createSchema response: ${it.message}", it) }
                if (schemaResponse.Status != "success")
                    error(schemaResponse.Message)
                ProvenancePrefs.markSchemaCreated(walletKey)
            }.onFailure { e ->
                val cause = if (isNetworkError(e)) e else e
                payloads.forEachIndexed { i, p ->
                    if (isNetworkError(cause)) {
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
                    } else {
                        emit(AttestationResult(i, p.name, p.hash, Result.failure(Exception(cause.message, cause))))
                    }
                }
                return@flow
            }
        }

        // ── 3. PDA + blockhash — once per batch ───────────────────────────────
        val schemaPda          = ProvenanceManager.deriveSchemaAddress(first.account)
        var blockhash          = ProvenanceManager.fetchBlockhash(first.commitment.name)
        var blockhashFetchedAt = System.currentTimeMillis()

        // ── 4. Sequential loop — one attest call per image ────────────────────
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

                val attestRes  = service().attest(AttestRequest(attested.signedTx)).await()
                val response   = runCatching {
                    decodeJson<ProvenanceResponse>(attestRes)
                }.getOrElse { throw Exception("Failed to parse attest response: ${it.message}", it) }

                // Apply chosen manifest option per image — inject attestationId for verifyOnChain
                val manifestWithId    = payload.c2paManifest.copy(attestationId = attested.attestationId)
                val certificateWithId = attested.certificate.copy(attestationId = attested.attestationId)
                val (manifestFile, embeddedImageFile) =
                    applyManifestOption(manifestWithId, manifestOption, certificateWithId)

                ProvenanceResult(
                    response          = response,
                    manifest          = manifestWithId,
                    attestationId     = attested.attestationId,
                    certificate       = certificateWithId,
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

            // 3. Derive attestationId — pure PDA derivation, no network needed
            val schemaPda     = ProvenanceManager.deriveSchemaAddress(payload.account)
            val attester      = keypair.publicKey
            val recipient     = if (payload.recipient.isBlank()) attester
                                else com.altude.core.Programs.AttestationProgram.let {
                                    foundation.metaplex.solanapublickeys.PublicKey(payload.recipient)
                                }
            val attestationId = com.altude.core.Programs.AttestationProgram
                .deriveAttestationAddress(schemaPda, attester, recipient)
                .toBase58()

            // 4. Inject attestationId so sidecar/embedded image supports verifyOnChain
            val manifestWithId    = payload.c2paManifest.copy(attestationId = attestationId)
            val certificateWithId = certificate.copy(attestationId = attestationId)
            val (manifestFile, embeddedImageFile) =
                applyManifestOption(manifestWithId, manifestOption, certificateWithId)

            // 5. Serialise manifest option for storage
            val (optType, optPath) = when (manifestOption) {
                is ManifestOption.EmbedInImage -> "embed" to manifestOption.sourceFilePath
                is ManifestOption.Both         -> "both"  to manifestOption.sourceFilePath
                is ManifestOption.None         -> "none"  to ""
                else                           -> "sidecar" to ""
            }

            // 6. Enqueue
            val queueId = java.util.UUID.randomUUID().toString()
            ProvenanceQueue.enqueue(
                PendingAttestation(
                    id                 = queueId,
                    type               = payload.type,
                    hash               = payload.hash,
                    mime               = payload.mime,
                    name               = payload.name,
                    manifest           = payload.manifest,
                    c2paManifest       = manifestWithId,
                    timestamp          = payload.timestamp,
                    account            = payload.account,
                    recipient          = payload.recipient,
                    expireAt           = payload.expireAt,
                    commitment         = payload.commitment.name,
                    latitude           = payload.latitude,
                    longitude          = payload.longitude,
                    certificateJson    = certificateWithId.toJson(),
                    manifestOptionType = optType,
                    manifestOptionPath = optPath
                )
            )

            OfflineAttestResult(
                queueId           = queueId,
                certificate       = certificateWithId,
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
        val schemaTxResult = ProvenanceManager.ensureSchema(
            account    = first.account,
            commitment = first.commitment
        )
        if (schemaTxResult.isFailure) {
            pending.forEach { p ->
                emit(SubmitResult(p.id, p.name, p.hash,
                    Result.failure(schemaTxResult.exceptionOrNull()!!)))
            }
            return@flow
        }

        // ── Keypair + PDA — once ───────────────────────────────────────────────
        val keypair   = ProvenanceManager.getKeyPair(first.account)
        val walletKey = keypair.publicKey.toBase58()
        val schemaPda = ProvenanceManager.deriveSchemaAddress(first.account)

        // ── Submit createSchema if the schema didn't exist yet ─────────────────
        val schemaTx = schemaTxResult.getOrNull()
        if (schemaTx != null) {
            runCatching {
                val schemaRes      = service().createSchema(CreateSchemaRequest(schemaTx)).await()
                val schemaResponse = decodeJson<ProvenanceResponse>(schemaRes)
                if (schemaResponse.Status != "success")
                    error(schemaResponse.Message)
                ProvenancePrefs.markSchemaCreated(walletKey)
            }.onFailure { e ->
                pending.forEach { p ->
                    emit(SubmitResult(p.id, p.name, p.hash,
                        Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))))
                }
                return@flow
            }
        }

        // ── Blockhash — once; refreshed every 30 s ────────────────────────────
        var blockhash          = ProvenanceManager.fetchBlockhash(first.commitment)
        var blockhashFetchedAt = System.currentTimeMillis()

        pending.forEach { entry ->
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

                // Build Solana tx + derive attestationId locally
                val (signedTx, attestationId) = ProvenanceManager.buildTx(payload, schemaPda, keypair, blockhash)
                val certificate = ProvenanceCertificate.fromJson(entry.certificateJson)

                val attestRes = service().attest(AttestRequest(signedTx)).await()
                val response  = runCatching {
                    decodeJson<ProvenanceResponse>(attestRes)
                }.getOrElse { throw Exception("Failed to parse attest response: ${it.message}", it) }

                // Remove from queue ONLY on success
                if (response.Status == "success") {
                    ProvenanceQueue.dequeue(entry.id)
                }

                // Ensure attestationId is in the manifest/certificate written to disk
                val manifestWithId    = entry.c2paManifest.copy(attestationId = attestationId)
                val certificateWithId = certificate?.copy(attestationId = attestationId)
                val manifestOption = entry.toManifestOption()
                val (manifestFile, embeddedImageFile) =
                    applyManifestOption(manifestWithId, manifestOption, certificateWithId)

                ProvenanceResult(
                    response          = response,
                    manifest          = manifestWithId,
                    certificate       = certificateWithId,
                    manifestFile      = manifestFile,
                    embeddedImageFile = embeddedImageFile,
                    attestationId     = attestationId
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
     * Verifies an attested image **directly on-chain** via Solana RPC — no backend involved.
     *
     * Given the [attestationId] (Attestation PDA) from [ProvenanceResult.attestationId]
     * or from the sidecar/embedded manifest, this fetches the account from Solana and compares
     * the stored hash with your local [expectedHash].
     *
     * This is the **trustless** path — it does not depend on the backend at all.
     *
     * ```kotlin
     * // Right after attestation:
     * val pr = Provenance.attestImageHash(payload).getOrThrow()
     * val v  = Provenance.verifyOnChain(
     *     attestationId = pr.attestationId,
     *     expectedHash  = pr.manifest.manifestHash
     * ).getOrThrow()
     * println(v.isVerified)  // true if hash on Solana matches local hash
     *
     * // Later, from a sidecar file alone (no backend needed):
     * val v = Provenance.verifyOnChain(
     *     attestationId = sidecarJson["attestationId"],
     *     expectedHash  = sidecarJson["manifestHash"]
     * ).getOrThrow()
     * ```
     *
     * @param attestationId  Attestation PDA (Base58) — available via [ProvenanceResult.attestationId].
     * @param expectedHash   [C2paManifest.manifestHash] read from the local sidecar/embedded
     *                       manifest. Pass `null` to skip hash comparison and just confirm
     *                       the account exists on-chain.
     */
    suspend fun verifyOnChain(
        attestationId: String,
        expectedHash:  String? = null
    ): Result<VerifyResult> = withContext(Dispatchers.IO) {
        runCatching {
            val account     = ProvenanceManager.fetchAttestationOnChain(attestationId)
            val onChainHash = extractHashFromPayload(account.payloadJson)
            val now         = System.currentTimeMillis() / 1000L

            val isExpired   = account.expireAt > 0L && account.expireAt < now
            val hashMatches = expectedHash == null || onChainHash == expectedHash
            val isVerified  = hashMatches && !isExpired

            val status = when {
                isExpired    -> "expired"
                !hashMatches -> "tampered"
                else         -> "verified"
            }
            val message = when {
                isExpired    -> "Attestation has expired"
                !hashMatches -> "Hash mismatch — image may have been modified"
                else         -> "Hash verified on-chain"
            }

            VerifyResult(
                isVerified    = isVerified,
                status        = status,
                message       = message,
                attestationId = attestationId,
                onChainHash   = onChainHash,
                certificate   = null  // trustless on-chain only; no certificate stored server-side
            )
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
                val embedded = runCatching {
                    manifest.embedInto(java.io.File(option.sourceFilePath), json)
                }.getOrNull()
                if (embedded != null) {
                    // Successfully embedded into image; no sidecar created.
                    Pair(null, embedded)
                } else {
                    // Embedding failed (e.g., unsupported format). Fall back to sidecar as documented.
                    val sidecar = runCatching { manifest.saveTo(manifestsDir, json) }.getOrNull()
                    Pair(sidecar, null)
                }
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


    private fun <T> Result<T>.mapFailure(): Result<T> =
        fold(
            onSuccess = { Result.success(it) },
            onFailure = { e -> 
                val message = e.message ?: e.javaClass.simpleName
                val improvedMessage = when {
                    // Handle "sanitize accounts offsets" error - usually means program doesn't exist
                    message.contains("sanitize accounts offsets", ignoreCase = true) -> {
                        val isDevnet = com.altude.core.Programs.AttestationProgram.isDevnet
                        val networkName = if (isDevnet) "DevNet" else "Mainnet"
                        val programId = com.altude.core.Programs.AttestationProgram.PROGRAM_ID.toBase58()
                        "The Solana Attestation Service program may not be deployed on $networkName. " +
                        "Program ID: $programId. " +
                        "Original error: $message. " +
                        "If using DevNet, you may need to deploy your own SAS instance or switch to Mainnet."
                    }
                    // Handle blockhash errors
                    message.contains("blockhash not found", ignoreCase = true) -> {
                        "Transaction blockhash expired. Please try again with a fresh blockhash. Original error: $message"
                    }
                    // Handle simulation failures
                    message.contains("simulation failed", ignoreCase = true) -> {
                        "Transaction simulation failed. This may indicate: " +
                        "1) The program is not deployed on this network, " +
                        "2) Insufficient funds for rent, or " +
                        "3) Invalid transaction parameters. Original error: $message"
                    }
                    else -> message
                }
                Result.failure(Exception(improvedMessage, e)) 
            }
        )

    /**
     * Extracts the `hash` field from the on-chain payload JSON string.
     * The stored format is: `{"type":"image_hash","hash":"<sha256>","mime":...}`.
     * Uses a simple regex to avoid requiring a full JSON parse just for one field.
     */
    private fun extractHashFromPayload(payloadJson: String): String {
        val match = Regex(""""hash"\s*:\s*"([^"]+)"""").find(payloadJson)
        return match?.groupValues?.get(1) ?: ""
    }

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
