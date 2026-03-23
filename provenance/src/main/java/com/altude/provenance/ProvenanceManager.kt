package com.altude.provenance

import android.os.Build
import android.util.Base64
import com.altude.core.Programs.AttestationProgram
import com.altude.core.config.SdkConfig
import com.altude.core.helper.Mnemonic
import com.altude.core.model.AltudeTransactionBuilder
import com.altude.core.model.HotSigner
import com.altude.core.network.AltudeRpc
import com.altude.core.service.StorageService
import com.altude.provenance.data.ImageHashPayload
import com.altude.provenance.data.ProvenanceCertificate
import com.altude.provenance.data.ProvenancePrefs
import foundation.metaplex.solana.transactions.SerializeConfig
import foundation.metaplex.solanaeddsa.Keypair
import foundation.metaplex.solanaeddsa.SolanaEddsa
import foundation.metaplex.solanapublickeys.PublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Internal transaction builder for image-hash provenance operations.
 *
 * Responsibilities:
 * - Keypair / PDA resolution
 * - Schema creation (once per wallet)
 * - [buildCertificate] — offline-safe, pure crypto, no network
 * - [fetchBlockhash]   — single RPC call; share across a batch to avoid N calls
 * - [buildTx]          — builds Solana tx from a pre-fetched blockhash + keypair
 * - [attestWithPrefetched] — batch-optimised: certificate + tx, no internal network calls
 * - [attest]           — convenience wrapper for single-image attestation
 */
internal object ProvenanceManager {

    const val SCHEMA_NAME = "image_hash"

    private val schemaPdaCache = mutableMapOf<String, PublicKey>()
    private val rpc            get() = AltudeRpc(SdkConfig.apiConfig.RpcUrl)
    private val feePayerPubKey get() = PublicKey(SdkConfig.apiConfig.FeePayer)

    // ── Keypair ───────────────────────────────────────────────────────────────

    suspend fun getKeyPair(account: String = ""): Keypair {
        val seed = StorageService.getDecryptedSeed(account)
            ?: throw Error("Please set seed first")
        if (seed.type == "mnemonic") return Mnemonic(seed.mnemonic).getKeyPair()
        return seed.privateKey?.let {
            SolanaEddsa.createKeypairFromSecretKey(it.copyOfRange(0, 32))
        } ?: throw Error("No seed found in storage")
    }

    // ── PDA ───────────────────────────────────────────────────────────────────

    internal suspend fun deriveSchemaAddress(account: String = ""): PublicKey {
        val keypair   = getKeyPair(account)
        val walletKey = keypair.publicKey.toBase58()
        return schemaPdaCache.getOrPut(walletKey) {
            AttestationProgram.deriveSchemaAddress(
                authority = keypair.publicKey,
                name      = SCHEMA_NAME
            )
        }
    }

    // ── Schema ────────────────────────────────────────────────────────────────

    suspend fun ensureSchema(account: String, commitment: String): Result<String?> =
        withContext(Dispatchers.IO) {
            try {
                val keypair   = getKeyPair(account)
                val walletKey = keypair.publicKey.toBase58()
                if (ProvenancePrefs.isSchemaCreated(walletKey))
                    return@withContext Result.success(null)

                val instruction = AttestationProgram.createSchema(
                    authority   = keypair.publicKey,
                    feePayer    = feePayerPubKey,
                    name        = SCHEMA_NAME,
                    description = "Stores SHA-256 hash of images",
                    fieldNames  = listOf("type", "hash", "mime", "name", "timestamp"),
                    isRevocable = true
                )
                val blockhash = rpc.getLatestBlockhash(commitment = commitment).blockhash
                val tx = AltudeTransactionBuilder()
                    .setFeePayer(feePayerPubKey)
                    .setRecentBlockHash(blockhash)
                    .addInstruction(instruction)
                    .setSigners(listOf(HotSigner(keypair)))
                    .build()
                Result.success(
                    Base64.encodeToString(
                        tx.serialize(SerializeConfig(requireAllSignatures = false)),
                        Base64.NO_WRAP
                    )
                )
            } catch (e: Throwable) {
                Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
            }
        }

    suspend fun resetSchema(account: String = "") {
        runCatching {
            val walletKey = getKeyPair(account).publicKey.toBase58()
            ProvenancePrefs.reset(walletKey)
            schemaPdaCache.remove(walletKey)
        }
    }

    // ── AttestResult ──────────────────────────────────────────────────────────

    internal data class AttestResult(
        val signedTx:    String,
        val certificate: ProvenanceCertificate
    )

    // ── Network helpers ───────────────────────────────────────────────────────

    /**
     * Fetches a fresh Solana blockhash.
     * Call ONCE before a batch and share the result — Solana blockhashes are valid
     * for ~150 slots (~90 s). [attestWithPrefetched] refreshes automatically every
     * 30 s when used via [Provenance.attestBatch].
     */
    internal suspend fun fetchBlockhash(commitment: String): String =
        rpc.getLatestBlockhash(commitment = commitment).blockhash

    // ── Offline-safe certificate builder ─────────────────────────────────────

    /**
     * Builds and ED25519-signs a [ProvenanceCertificate] from [payload].
     *
     * **No network calls** — safe to call when the device is offline.
     * Used by [Provenance.attestOffline] to produce a signed credential before
     * queuing the image for later on-chain submission.
     */
    internal fun buildCertificate(
        payload: ImageHashPayload,
        keypair: Keypair
    ): ProvenanceCertificate {
        val pubKeyHex = keypair.publicKey.bytes.joinToString("") { "%02x".format(it) }
        val draft = ProvenanceCertificate(
            instanceId         = "urn:uuid:${java.util.UUID.randomUUID()}",
            captureTimestampMs = System.currentTimeMillis(),
            imageSha256        = payload.c2paManifest.assetHash,
            signerAddress      = keypair.publicKey.toBase58(),
            signerPublicKey    = pubKeyHex,
            signature          = "",
            deviceMake         = Build.MANUFACTURER,
            deviceModel        = Build.MODEL,
            osVersion          = Build.VERSION.RELEASE,
            latitude           = payload.latitude,
            longitude          = payload.longitude
        )
        val sigBytes = SolanaEddsa.sign(
            draft.toClaimJson().toByteArray(Charsets.UTF_8),
            keypair
        )
        return draft.copy(signature = sigBytes.joinToString("") { "%02x".format(it) })
    }

    // ── Tx builder (requires pre-fetched blockhash) ───────────────────────────

    /**
     * Builds and signs the Solana `createAttestation` transaction using a
     * **pre-fetched** [blockhash] and [keypair].
     *
     * Call [fetchBlockhash] once per batch and pass the result here to avoid an
     * RPC network call per image.
     */
    internal fun buildTx(
        payload:   ImageHashPayload,
        schemaPda: PublicKey,
        keypair:   Keypair,
        blockhash: String
    ): String {
        val attester  = keypair.publicKey
        val recipient = if (payload.recipient.isBlank()) attester
                        else PublicKey(payload.recipient)

        val payloadJson = """{"type":"${payload.type}","hash":"${payload.hash}","mime":"${payload.mime}","name":"${payload.name}","timestamp":${payload.timestamp}}"""

        val instruction = AttestationProgram.createAttestation(
            attester        = attester,
            feePayer        = feePayerPubKey,
            schemaPda       = schemaPda,
            recipient       = recipient,
            attestationData = payloadJson.toByteArray(),
            expireAt        = payload.expireAt
        )
        val tx = AltudeTransactionBuilder()
            .setFeePayer(feePayerPubKey)
            .setRecentBlockHash(blockhash)
            .addInstruction(instruction)
            .setSigners(listOf(HotSigner(keypair)))
            .build()
        return Base64.encodeToString(
            tx.serialize(SerializeConfig(requireAllSignatures = false)),
            Base64.NO_WRAP
        )
    }

    // ── Batch-optimised attest (pre-fetched keypair + blockhash) ─────────────

    /**
     * Builds both the certificate and the Solana tx using **caller-supplied**
     * [keypair] and [blockhash] — no internal RPC or storage calls.
     *
     * Use this inside [Provenance.attestBatch] so that both the keypair and
     * blockhash are fetched ONCE for the entire batch rather than once per image.
     */
    internal fun attestWithPrefetched(
        payload:   ImageHashPayload,
        schemaPda: PublicKey,
        keypair:   Keypair,
        blockhash: String
    ): Result<AttestResult> = runCatching {
        val certificate = buildCertificate(payload, keypair)
        val signedTx    = buildTx(payload, schemaPda, keypair, blockhash)
        AttestResult(signedTx = signedTx, certificate = certificate)
    }.let { r ->
        r.exceptionOrNull()?.let { e ->
            Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        } ?: Result.success(r.getOrThrow())
    }

    // ── Single-image convenience (fetches blockhash internally) ───────────────

    /**
     * Convenience wrapper for single-image attestation — fetches the blockhash and
     * keypair internally. For batches use [attestWithPrefetched] instead.
     */
    suspend fun attest(
        payload:   ImageHashPayload,
        schemaPda: PublicKey
    ): Result<AttestResult> = withContext(Dispatchers.IO) {
        runCatching {
            val keypair   = getKeyPair(payload.account)
            val blockhash = fetchBlockhash(payload.commitment.name)
            attestWithPrefetched(payload, schemaPda, keypair, blockhash).getOrThrow()
        }.let { r ->
            r.exceptionOrNull()?.let { e ->
                Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
            }
