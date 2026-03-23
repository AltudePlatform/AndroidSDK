package com.altude.provenance

import com.altude.core.config.SdkConfig
import com.altude.core.service.StorageService
import com.altude.provenance.data.AttestationResult
import com.altude.provenance.data.C2paManifest
import com.altude.provenance.data.ImageHashPayload
import com.altude.provenance.data.ImageHashRequest
import com.altude.provenance.data.ImageHashResponse
import com.altude.provenance.data.ManifestOption
import com.altude.provenance.data.ProvenanceCertificate
import com.altude.provenance.data.ProvenancePrefs
import com.altude.provenance.data.ProvenanceResult
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
     * @param manifestOption How to save the manifest after attestation. Default: [ManifestOption.SidecarFile].
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
            if (schemaResult.isFailure)
                return@withContext Result.failure(schemaResult.exceptionOrNull()!!)

            // 2. Derive Schema PDA
            val schemaPda = ProvenanceManager.deriveSchemaAddress(payload.account)

            // 3. Sign attestation tx + build ProvenanceCertificate offline
            val attestResult = ProvenanceManager.attest(payload, schemaPda)
            if (attestResult.isFailure)
                return@withContext Result.failure(attestResult.exceptionOrNull()!!)

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

            // 6. Apply chosen manifest option
            val (manifestFile, embeddedImageFile) =
                applyManifestOption(payload.c2paManifest, manifestOption)

            Result.success(ProvenanceResult(
                response          = response,
                manifest          = payload.c2paManifest,
                certificate       = attested.certificate,
                manifestFile      = manifestFile,
                embeddedImageFile = embeddedImageFile
            ))
        } catch (e: Throwable) {
            Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }

    // ── Bulk images ───────────────────────────────────────────────────────────

    /**
     * Attests a list of images sequentially — 1 schema tx (first call only per wallet),
     * N attestation txs submitted one by one in order.
     *
     * @param payloads       List built via [ImageHashPayload.create].
     * @param manifestOption How to save each manifest. Default: [ManifestOption.SidecarFile].
     *
     * ```kotlin
     * viewModelScope.launch {
     *     Provenance.attestBatch(payloads, ManifestOption.EmbedInImage(filePath)).collect { item ->
     *         val embedded = item.result.getOrNull()?.embeddedImageFile
     *         val sidecar  = item.result.getOrNull()?.manifestFile
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

        // ── 1. Ensure schema ONCE for the whole batch ─────────────────────────
        val schemaResult = ProvenanceManager.ensureSchema(
            account    = first.account,
            commitment = first.commitment.name
        )
        if (schemaResult.isFailure) {
            payloads.forEachIndexed { i, p ->
                emit(AttestationResult(i, p.name, p.hash,
                    Result.failure(schemaResult.exceptionOrNull()!!)))
            }
            return@flow
        }

        // ── 2. Derive Schema PDA ONCE ─────────────────────────────────────────
        val schemaPda = ProvenanceManager.deriveSchemaAddress(first.account)
        val walletKey = ProvenanceManager.getKeyPair(first.account).publicKey.toBase58()

        var schemaMarked = ProvenancePrefs.isSchemaCreated(walletKey)

        // ── 3. Sequential loop — sign offline → submit → emit ─────────────────
        payloads.forEachIndexed { index, payload ->
            val itemResult = runCatching {
                val attested = ProvenanceManager.attest(payload, schemaPda).getOrThrow()

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

                // Apply chosen manifest option per image
                val (manifestFile, embeddedImageFile) =
                    applyManifestOption(payload.c2paManifest, manifestOption)

                ProvenanceResult(
                    response          = response,
                    manifest          = payload.c2paManifest,
                    certificate       = attested.certificate,
                    manifestFile      = manifestFile,
                    embeddedImageFile = embeddedImageFile
                )
            }
            emit(AttestationResult(index, payload.name, payload.hash, itemResult))
        }
    }.flowOn(Dispatchers.IO)

    // ── Session ───────────────────────────────────────────────────────────────

    /**
     * Clears the schema state (SharedPreferences + in-memory PDA cache) for [account].
     * Call on wallet switch or logout so the next [attestImageHash] re-creates the schema.
     */
    suspend fun resetSession(account: String = "") =
        ProvenanceManager.resetSchema(account)

    // ── Online verification ───────────────────────────────────────────────────

    /**
     * Verifies an attested image online by its **manifest hash**
     * ([C2paManifest.manifestHash] — the SHA-256 of the canonical C2PA claim JSON
     * that is stored on-chain).
     *
     * Use this when you have the hash from a local [ImageHashPayload] and want to
     * confirm it is recorded on the Solana chain with a matching certificate.
     *
     * ```kotlin
     * val payload = ImageHashPayload.create(filePath = file.absolutePath, account = wallet)
     * val result  = Provenance.attestImageHash(payload).getOrThrow()
     *
     * // Later — verify the same image online:
     * val v = Provenance.verifyByHash(payload.hash).getOrThrow()
     * if (v.isVerified) {
     *     println("Signer:      ${v.certificate?.signerAddress}")
     *     println("On-chain:    ${v.onChainHash}")
     *     println("Device:      ${v.certificate?.deviceMake} ${v.certificate?.deviceModel}")
     * }
     * ```
     *
     * @param hash [C2paManifest.manifestHash] — SHA-256 hex of the canonical claim JSON.
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
     * Verifies an attested image online by its **on-chain Attestation PDA** (Base58).
     *
     * Use this when you have the `attestationId` from [ProvenanceResult.response]:
     *
     * ```kotlin
     * val result = Provenance.attestImageHash(payload).getOrThrow()
     * val id     = result.response.attestationId
     *
     * // Later — verify by the Solana attestation account:
     * val v = Provenance.verifyByAttestationId(id).getOrThrow()
     * if (v.isVerified) {
     *     println("Cert timestamp: ${v.certificate?.captureTimestampMs}")
     * }
     * ```
     *
     * @param attestationId On-chain Attestation PDA returned by [ProvenanceResult.response].
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
     * Applies [option] after attestation. Returns (sidecarFile, embeddedImageFile).
     * Errors are swallowed to null so a save failure never crashes the attestation.
     */
    private fun applyManifestOption(
        manifest: C2paManifest,
        option:   ManifestOption
    ): Pair<java.io.File?, java.io.File?> {
        val manifestsDir = java.io.File(StorageService.getContext().filesDir, "provenance_manifests")
        return when (option) {
            is ManifestOption.SidecarFile -> {
                val file = runCatching { manifest.saveTo(manifestsDir) }.getOrNull()
                Pair(file, null)
            }
            is ManifestOption.EmbedInImage -> {
                val embedded = runCatching {
                    manifest.embedInto(java.io.File(option.sourceFilePath))
                }.getOrNull()
                Pair(null, embedded)
            }
            is ManifestOption.Both -> {
                val sidecar  = runCatching { manifest.saveTo(manifestsDir) }.getOrNull()
                val embedded = runCatching {
                    manifest.embedInto(java.io.File(option.sourceFilePath))
                }.getOrNull()
                Pair(sidecar, embedded)
            }
            else -> Pair(null, null) // ManifestOption.None
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private inline fun <reified T> decodeJson(element: JsonElement): T =
        json.decodeFromJsonElement(element)

    /** Maps the raw [VerifyResponse] into the public [VerifyResult], parsing the certificate. */
    private fun VerifyResponse.toVerifyResult() = VerifyResult(
        isVerified    = Status.equals("verified", ignoreCase = true),
        status        = Status,
        message       = Message,
        attestationId = attestationId,
        onChainHash   = onChainHash,
        certificate   = ProvenanceCertificate.fromJson(certificate)
    )

    /** Wraps any raw exception in a friendlier [Exception] with the original message. */
    private fun <T> Result<T>.mapFailure(): Result<T> =
        onFailure { e -> Result.failure<T>(Exception(e.message ?: e.javaClass.simpleName, e)) }
            .let { this }
}
