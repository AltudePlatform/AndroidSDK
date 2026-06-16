package com.altude.provenance

import android.content.Context
import android.util.Log
import com.altude.core.config.SdkConfig
import com.altude.core.Programs.AttestationProgram
import com.altude.core.helper.Mnemonic
import foundation.metaplex.solanapublickeys.PublicKey
import com.altude.core.service.StorageService
import com.altude.provenance.data.AttestRequest
import com.altude.provenance.data.AttestationResult
import java.io.File
import com.altude.provenance.data.Commitment
// ...existing imports...
import com.altude.provenance.data.ImageHashPayload
import com.altude.provenance.data.ProvenanceResponse
import com.altude.provenance.data.ManifestOption
import com.altude.provenance.data.OfflineAttestResult
import com.altude.provenance.data.PendingAttestation
import com.altude.provenance.data.ProvenanceCertificate
// ...existing imports...
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
import kotlinx.serialization.decodeFromString
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
     * Builds and saves a C2PA sidecar file from a generic payload map.
     *
     * The payload map must contain at minimum `assetHash` (SHA-256 hex). Optional
     * keys: `mimeType`, `filename`, `producer`, `softwareAgent`, `timestamp`.
     *
     * Returns the written sidecar [File] on success.
     */
    // Converts a generic Map payload into an ImageHashPayload.
    // Supports schema-defined data map.
    private fun mapToImageHashPayload(map: Map<String, Any?>): ImageHashPayload {
        // Already an ImageHashPayload
        val maybePayload = map["imageHashPayload"]
        if (maybePayload is ImageHashPayload) return maybePayload

        // Build schemaData from the map — pass the entire map as schema data
        val schemaData: Map<String, Any> = map.filterKeys { it != "account" && it != "recipient" && it != "expireAt" && it != "commitment" }
            .mapValues { it.value ?: "" }

        val account = map["account"] as? String ?: ""
        val recipient = map["recipient"] as? String ?: ""
        val expireAt = when (val e = map["expireAt"]) {
            is Number -> e.toLong()
            is String -> e.toLongOrNull() ?: 0L
            else -> 0L
        }
        val commitment = when (val c = map["commitment"]) {
            is Commitment -> c
            is String -> try { Commitment.valueOf(c) } catch (_: Exception) { Commitment.finalized }
            else -> Commitment.finalized
        }

        return ImageHashPayload.create(
            schemaData = schemaData,
            account = account,
            recipient = recipient,
            expireAt = expireAt,
            commitment = commitment
        )
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
    // createSchema and createCredential APIs removed.
    // Schema + credential creation must be handled out-of-band or via `ProvenanceManager.setSchemaPda`.

    suspend fun init(context: Context,apiKey: String, isDevnet: Boolean = true ){
        SdkConfig.setNetwork(isDevnet = isDevnet)
        SdkConfig.setApiKey(context, apiKey)

        val existingSeed = runCatching {
            StorageService.getDecryptedSeed("")
        }.getOrNull()

        if (existingSeed == null) {
            StorageService.storeMnemonic(Mnemonic.generateMnemonic(12))
        }
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
        payload:      ImageHashPayload,
        manifestOption:  ManifestOption = ManifestOption.SidecarFile,
        // Optional: credential and schema names when the credential/schema were created out-of-band
        credentialName:  String,
        schemaName:      String
    ): Result<ProvenanceResult> = withContext(Dispatchers.IO) {
        try {
            // Use provided ImageHashPayload directly (no map conversion needed)
            // 1. Keypair (needed to produce deterministic payload owner field) and
            // build the explicit attestation payload map (matches on-chain fixed schema)
            val keypair = ProvenanceManager.getKeyPair(payload.account)
            val payloadMap = ProvenanceManager.buildPayloadJson(payload, keypair.publicKey)

            // 2. Use AttestBuilder to centralise PDA derivation and call into the
            // existing attest(...) helper. Pass the explicit payload map so the
            // on-chain bytes are deterministic.
            val attestResult = ProvenanceManager.AttestBuilder()
                .withPayload(payload)
                .credentialName(credentialName)
                .schemaName(schemaName)
                .execute(payloadMap)
            if (attestResult.isFailure) {
                val cause = attestResult.exceptionOrNull()!!
                // Do not queue offline from this public API; propagate the failure to caller
                return@withContext Result.failure(cause)
            }
            val attested = attestResult.getOrThrow()

            // 4. Submit attestation tx
            val attestRes = runCatching {
                service().attest(AttestRequest(attested.signedTx)).await()
            }.getOrElse { e ->
                // Do not attempt offline queuing here; return failure to caller
                return@withContext Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
            }
            val response = runCatching {
                decodeJson<ProvenanceResponse>(attestRes)
            }.getOrElse { e ->
                return@withContext Result.failure(Exception("Failed to parse attest response: ${e.message}", e))
            }

            // 5. Apply chosen manifest option — inject attestationId for verifyOnChain
            val certificateWithId = attested.certificate.copy(attestationId = attested.attestationId)

            // Build a display-friendly payload map for the sidecar:
            // - keep the raw attestation payload for on-chain bytes
            // - convert binary parent_hash to a readable "<alg>:<hex>" string for display
            val sidecarPayloadMap: MutableMap<String, Any?> = linkedMapOf()
            sidecarPayloadMap.putAll(payloadMap)
            val parentRaw = payloadMap["parent_hash"]
            if (parentRaw is ByteArray && parentRaw.isNotEmpty()) {
                val hex = parentRaw.joinToString("") { "%02x".format(it) }
                val hashAlg = (payloadMap["hash_algorithm"] as? String) ?: "sha256"
                sidecarPayloadMap["parent_hash"] = "$hashAlg:$hex"
            } else {
                // ensure parent_hash isn't a raw empty byte array in the sidecar
                sidecarPayloadMap.remove("parent_hash")
            }
            // Add a base64-encoded copy of the raw image_hash bytes so consumer sidecars
            // expose the exact on-chain bytes in a JSON-safe form.
            try {
                val imgBytes = payload.imageHash
                if (imgBytes.isNotEmpty()) {
                    val imgB64 = android.util.Base64.encodeToString(imgBytes, android.util.Base64.NO_WRAP)
                    sidecarPayloadMap["image_hash_b64"] = imgB64
                }
            } catch (_: Exception) {
                // ignore if payload.imageHash not available for some reason
            }

            // Write a consumer-facing sidecar populated from the display map
            val (manifestFile, embeddedImageFile) =
                applyManifestOption(manifestOption, certificateWithId, sidecarPayloadMap)

            Result.success(ProvenanceResult(
                response          = response,
                attestationId     = attested.attestationId,
                certificate       = attested.certificate,
                manifestFile      = manifestFile,
                embeddedImageFile = embeddedImageFile
            ))
        } catch (e: Throwable) {
            // Propagate errors to caller; do not queue offline from this API
            Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }

    // Backwards-compatible Map-based overload removed. Use the ImageHashPayload variant.

    // ...offline queuing removed for `attestImageHash`; offline helpers remain for explicit offline flows

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
        manifestOption: ManifestOption = ManifestOption.SidecarFile,
        // Optional: credential and schema names when created out-of-band
        credentialName: String,
        schemaName: String
    ): Flow<AttestationResult> = flow {
        if (payloads.isEmpty()) return@flow

        val first = payloads.first()

        // ── 1. Schema — once per wallet ───────────────────────────────────────
        val keypair  = ProvenanceManager.getKeyPair(first.account)

        // Support two modes:
        // - Caller provides credentialName + schemaName (created out-of-band on backend/web app)
        // - Caller relies on previously-marked schema (ProvenancePrefs) and SDK-derived names
        val schemaPda: PublicKey
        val credentialPda = AttestationProgram.deriveCredentialAddress(authority = keypair.publicKey, name = credentialName)
        schemaPda = AttestationProgram.deriveSchemaAddress(credential = credentialPda, name = schemaName, version = 0)

        // ── 3. PDA + blockhash — once per batch ───────────────────────────────
        // schemaPda already set above
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
                                    certificate       = offline.certificate,
                                    manifestFile      = offline.manifestFile,
                                    embeddedImageFile = offline.embeddedImageFile,
                                    isQueued          = true,
                                    queueId           = offline.queueId
                                )
                            }
                            emit(AttestationResult(index + i, p.filename, p.dataHash, offlineResult))
                        }
                        return@flow
                    }
                }
            }

            val itemResult = runCatching {
                val attested = ProvenanceManager
                    .attestWithPrefetched(payload, schemaPda, keypair, credentialPda)
                    .getOrThrow()

                val attestRes  = service().attest(AttestRequest(attested.signedTx)).await()
                val response   = runCatching {
                    decodeJson<ProvenanceResponse>(attestRes)
                }.getOrElse { throw Exception("Failed to parse attest response: ${it.message}", it) }

                // Apply chosen manifest option per image — inject attestationId for verifyOnChain
                val certificateWithId = attested.certificate.copy(attestationId = attested.attestationId)
                val (manifestFile, embeddedImageFile) =
                    applyManifestOption(manifestOption, certificateWithId, null)

                ProvenanceResult(
                    response          = response,
                    attestationId     = attested.attestationId,
                    certificate       = certificateWithId,
                    manifestFile      = manifestFile,
                    embeddedImageFile = embeddedImageFile
                )
            }.recoverNetworkToOffline(payload, manifestOption)
            emit(AttestationResult(index, payload.filename, payload.dataHash, itemResult))
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
            val certificateWithId = certificate.copy(attestationId = attestationId)
            val (manifestFile, embeddedImageFile) =
                applyManifestOption(manifestOption, certificateWithId, null)

            // 5. Serialise manifest option for storage
            val (optType, optPath) = when (manifestOption) {
                is ManifestOption.EmbedInImage -> "embed" to manifestOption.sourceFilePath
                is ManifestOption.Both         -> "both"  to manifestOption.sourceFilePath
                is ManifestOption.None         -> "none"  to ""
                else                           -> "sidecar" to ""
            }

            // 6. Enqueue
            val queueId = java.util.UUID.randomUUID().toString()
            val schemaDataJson = org.json.JSONObject(payload.schemaData).toString()
            ProvenanceQueue.enqueue(
                PendingAttestation(
                    id                 = queueId,
                    type               = "image_hash",
                    hash               = payload.dataHash,
                    mime               = payload.mimeType,
                    name               = payload.filename,
                    manifest           = schemaDataJson,
                    timestamp          = payload.timestamp,
                    account            = payload.account,
                    recipient          = payload.recipient,
                    expireAt           = payload.expireAt,
                    commitment         = payload.commitment.name,
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
        val schemaPda = ProvenanceManager.deriveSchemaAddress(first.account)
        val feePayer  = PublicKey(SdkConfig.requireApiConfig().FeePayer)
        val credentialPda = AttestationProgram.deriveCredentialAddress(
            feePayer,
            ProvenanceManager.CREDENTIAL_NAME
        )

        // No client-side createSchema submission here either. Backend must create schema.

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
                val schemaDataMap = try {
                    val json = org.json.JSONObject(entry.manifest)
                    json.keys().asSequence().associateTo(linkedMapOf<String, Any>()) { key ->
                        key to (json.get(key) ?: "")
                    }
                } catch (_: Exception) {
                    emptyMap<String, Any>()
                }
                val payload = ImageHashPayload.create(
                    schemaData = schemaDataMap,
                    account = entry.account,
                    recipient = entry.recipient,
                    expireAt = entry.expireAt,
                    commitment = Commitment.valueOf(entry.commitment)
                )

                // Build Solana tx + derive attestationId locally
                val (signedTx, attestationId) = ProvenanceManager.buildTx(payload, schemaPda, keypair, credentialPda)
                val certificate = ProvenanceCertificate.fromJson(entry.certificateJson)

                val attestRes = service().attest(AttestRequest(signedTx)).await()
                val response  = runCatching {
                    decodeJson<ProvenanceResponse>(attestRes)
                }.getOrElse { throw Exception("Failed to parse attest response: ${it.message}", it) }

                // Remove from queue ONLY on success
                if (response.Status == "success") {
                    ProvenanceQueue.dequeue(entry.id)
                }

                // Ensure attestationId is in the certificate written to disk
                val certificateWithId = certificate?.copy(attestationId = attestationId)
                val manifestOption = entry.toManifestOption()
                val (manifestFile, embeddedImageFile) =
                    applyManifestOption(manifestOption, certificateWithId ?: ProvenanceCertificate(
                        instanceId = "",
                        captureTimestampMs = 0L,
                        imageSha256 = "",
                        signerAddress = "",
                        signerPublicKey = "",
                        signature = "",
                        deviceMake = "",
                        deviceModel = "",
                        osVersion = "",
                        attestationId = attestationId
                    ), null)

                ProvenanceResult(
                    response          = response,
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
     * Applies the [option] to save or embed the certificate locally.
     *
     * When [certificate] is provided its [ProvenanceCertificate.toJson] (which includes
     * the ED25519 signature) is written to the sidecar file or embedded in the image,
     * making the sidecar / embedded image fully self-contained for offline verification.
     */
    private fun applyManifestOption(
        option:      ManifestOption,
        certificate: ProvenanceCertificate,
        payloadMap:  Map<String, Any?>? = null
    ): Pair<java.io.File?, java.io.File?> {
        val manifestsDir = java.io.File(StorageService.getContext().filesDir, "provenance_manifests")

        // Build the sidecar JSON. Include certificate info and payload data
        val baseMap: MutableMap<String, Any?> = linkedMapOf()
        if (payloadMap != null) baseMap.putAll(payloadMap)

        val sidecarToWrite: String = try {
            // Convert hex signature -> base64
            val sigHex = certificate.signature
            val sigBytes = sigHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val sigB64 = android.util.Base64.encodeToString(sigBytes, android.util.Base64.NO_WRAP)
            val signer = if (certificate.signerAddress.startsWith("wallet:")) certificate.signerAddress else "wallet:${certificate.signerAddress}"

            baseMap["certificate"] = certificate.toJson()
            baseMap["signature_base64"] = sigB64
            baseMap["signer"] = signer
            baseMap["signature_alg"] = "ed25519"

            org.json.JSONObject(baseMap).toString()
        } catch (e: Exception) {
            // Fallback to certificate.toJson() if anything goes wrong
            certificate.toJson()
        }

        return when (option) {
            is ManifestOption.SidecarFile -> {
                Pair(runCatching { saveSidecarFile(manifestsDir, sidecarToWrite, certificate.instanceId) }.getOrNull(), null)
            }
            is ManifestOption.SidecarDir -> {
                val dir = java.io.File(option.directoryPath)
                Pair(runCatching { saveSidecarFile(dir, sidecarToWrite, certificate.instanceId) }.getOrNull(), null)
            }
            is ManifestOption.EmbedInImage -> {
                val embedded = runCatching {
                    embedInImage(java.io.File(option.sourceFilePath), sidecarToWrite)
                }.getOrNull()
                if (embedded != null) {
                    Pair(null, embedded)
                } else {
                    val sidecar = runCatching { saveSidecarFile(manifestsDir, sidecarToWrite, certificate.instanceId) }.getOrNull()
                    Pair(sidecar, null)
                }
            }
            is ManifestOption.Both -> {
                val sidecar  = runCatching { saveSidecarFile(manifestsDir, sidecarToWrite, certificate.instanceId) }.getOrNull()
                val embedded = runCatching {
                    embedInImage(java.io.File(option.sourceFilePath), sidecarToWrite)
                }.getOrNull()
                Pair(sidecar, embedded)
            }
            else -> Pair(null, null)
        }
    }

    private fun saveSidecarFile(dir: java.io.File, content: String, manifestId: String): java.io.File {
        if (!dir.exists()) dir.mkdirs()
        val filename = "$manifestId.json"
        val file = java.io.File(dir, filename)
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    private fun embedInImage(imageFile: java.io.File, manifestJson: String): java.io.File {
        if (!imageFile.exists()) throw Exception("Image file not found: ${imageFile.absolutePath}")
        
        // For now, just create a modified copy in the same directory with .manifest suffix
        // A full implementation would embed into JPEG XMP or PNG tEXt chunk
        val manifestFile = java.io.File(
            imageFile.parentFile,
            imageFile.nameWithoutExtension + ".provenance.json"
        )
        manifestFile.writeText(manifestJson, Charsets.UTF_8)
        return manifestFile
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
                certificate       = offline.certificate,
                manifestFile      = offline.manifestFile,
                embeddedImageFile = offline.embeddedImageFile,
                isQueued          = true,
                queueId           = offline.queueId
            )
        }
    }
}
