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
 * Builds and partially signs both the `createSchema` and `createAttestation`
 * transactions, then returns Base64-encoded serialized transactions to be
 * forwarded to the backend.
 *
 * Also builds and ED25519-signs a [ProvenanceCertificate] alongside every attestation
 * for offline-verifiable content credentials.
 */
internal object ProvenanceManager {

    /** Fixed schema name used for all image-hash attestations. */
    const val SCHEMA_NAME = "image_hash"

    /**
     * Session-level PDA cache: walletBase58 → Schema PDA.
     * Avoids re-deriving the PDA on every call within the same app session.
     * SharedPreferences ([ProvenancePrefs]) handles persistence across restarts.
     */
    private val schemaPdaCache = mutableMapOf<String, PublicKey>()

    private val rpc get() = AltudeRpc(SdkConfig.apiConfig.RpcUrl)
    private val feePayerPubKey get() = PublicKey(SdkConfig.apiConfig.FeePayer)

    // ── Keypair resolution ────────────────────────────────────────────────────

    suspend fun getKeyPair(account: String = ""): Keypair {
        val seedData = StorageService.getDecryptedSeed(account)
        if (seedData != null) {
            if (seedData.type == "mnemonic") return Mnemonic(seedData.mnemonic).getKeyPair()
            return if (seedData.privateKey != null)
                SolanaEddsa.createKeypairFromSecretKey(seedData.privateKey!!.copyOfRange(0, 32))
            else throw Error("No seed found in storage")
        } else throw Error("Please set seed first")
    }

    // ── PDA helpers ───────────────────────────────────────────────────────────

    /**
     * Derives the Schema PDA for the fixed [SCHEMA_NAME] and the given [account].
     * Caches the result in [schemaPdaCache] — computed once per session per wallet.
     * Keeps [AttestationProgram] fully hidden from callers outside this class.
     */
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

    // ── Schema — once per wallet ──────────────────────────────────────────────

    /**
     * Builds + signs a `createSchema` tx ONLY if not yet confirmed for this wallet.
     *
     * @return `Result<String?>` — `null` = schema already exists; `String` = signed tx to send.
     */
    suspend fun ensureSchema(account: String, commitment: String): Result<String?> =
        withContext(Dispatchers.IO) {
            try {
                val keypair   = getKeyPair(account)
                val walletKey = keypair.publicKey.toBase58()

                if (ProvenancePrefs.isSchemaCreated(walletKey))
                    return@withContext Result.success(null)

                val hotSigner   = HotSigner(keypair)
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
                    .setSigners(listOf(hotSigner))
                    .build()
                val serialized = Base64.encodeToString(
                    tx.serialize(SerializeConfig(requireAllSignatures = false)),
                    Base64.NO_WRAP
                )
                Result.success(serialized)
            } catch (e: Throwable) {
                Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
            }
        }

    /**
     * Clears schema state for [account] — both in-memory cache and SharedPreferences.
     * Call via [Provenance.resetSession] on wallet switch or logout.
     */
    suspend fun resetSchema(account: String = "") {
        runCatching {
            val walletKey = getKeyPair(account).publicKey.toBase58()
            ProvenancePrefs.reset(walletKey)
            schemaPdaCache.remove(walletKey)
        }
    }

    // ── Attestation result ────────────────────────────────────────────────────

    /**
     * Return type of [attest] — carries both the serialized Solana transaction and
     * the signed [ProvenanceCertificate] for the same image.
     */
    internal data class AttestResult(
        /** Base64-encoded signed `createAttestation` Solana transaction. */
        val signedTx: String,
        /**
         * Signed C2PA-compatible certificate (ED25519 over canonical claim JSON).
         * Returned to SDK user via [com.altude.provenance.data.ProvenanceResult.certificate].
         */
        val certificate: ProvenanceCertificate
    )

    // ── Attestation ───────────────────────────────────────────────────────────

    /**
     * Builds and signs both the Solana `createAttestation` transaction and a
     * [ProvenanceCertificate] (ED25519 over canonical C2PA claim JSON) for [payload].
     *
     * Certificate fields:
     * - `imageSha256`     ← [com.altude.provenance.data.C2paManifest.assetHash] (SHA-256 of raw bytes)
     * - `signerAddress`   ← wallet Base58 public key
     * - `signerPublicKey` ← hex-encoded 32-byte ED25519 public key
     * - `signature`       ← hex-encoded 64-byte ED25519 signature over `toClaimJson()` bytes
     * - `deviceMake/Model/osVersion` ← captured at signing time
     * - `latitude/longitude`         ← from [payload.latitude] / [payload.longitude]
     */
    suspend fun attest(
        payload: ImageHashPayload,
        schemaPda: PublicKey
    ): Result<AttestResult> = withContext(Dispatchers.IO) {
        try {
            val keypair   = getKeyPair(payload.account)
            val attester  = keypair.publicKey
            val hotSigner = HotSigner(keypair)
            val recipient = if (payload.recipient.isBlank()) attester
                            else PublicKey(payload.recipient)

            // ── Build Solana attestation tx ───────────────────────────────────
            val payloadJson = """
                {
                  "type": "${payload.type}",
                  "hash": "${payload.hash}",
                  "mime": "${payload.mime}",
                  "name": "${payload.name}",
                  "timestamp": ${payload.timestamp}
                }
            """.trimIndent()

            val instruction = AttestationProgram.createAttestation(
                attester        = attester,
                feePayer        = feePayerPubKey,
                schemaPda       = schemaPda,
                recipient       = recipient,
                attestationData = payloadJson.toByteArray(),
                expireAt        = payload.expireAt
            )

            val blockhash = rpc.getLatestBlockhash(
                commitment = payload.commitment.name
            ).blockhash

            val tx = AltudeTransactionBuilder()
                .setFeePayer(feePayerPubKey)
                .setRecentBlockHash(blockhash)
                .addInstruction(instruction)
                .setSigners(listOf(hotSigner))
                .build()

            val serialized = Base64.encodeToString(
                tx.serialize(SerializeConfig(requireAllSignatures = false)),
                Base64.NO_WRAP
            )

            // ── Build & sign ProvenanceCertificate ────────────────────────────
            val pubKeyHex = keypair.publicKey.bytes
                .joinToString("") { "%02x".format(it) }

            val draft = ProvenanceCertificate(
                instanceId         = "urn:uuid:${java.util.UUID.randomUUID()}",
                captureTimestampMs = System.currentTimeMillis(),
                // assetHash = SHA-256 of raw image bytes (from C2paManifest)
                imageSha256        = payload.c2paManifest.assetHash,
                signerAddress      = attester.toBase58(),
                signerPublicKey    = pubKeyHex,
                signature          = "",                      // filled below after signing
                deviceMake         = Build.MANUFACTURER,
                deviceModel        = Build.MODEL,
                osVersion          = Build.VERSION.RELEASE,
                latitude           = payload.latitude,
                longitude          = payload.longitude
            )

            // Sign the canonical claim JSON (without signatureInfo) with the wallet key
            val claimBytes  = draft.toClaimJson().toByteArray(Charsets.UTF_8)
            val sigBytes    = SolanaEddsa.sign(claimBytes, keypair)
            val sigHex      = sigBytes.joinToString("") { "%02x".format(it) }
            val certificate = draft.copy(signature = sigHex)

            Result.success(AttestResult(signedTx = serialized, certificate = certificate))
        } catch (e: Throwable) {
            Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }
}

/**
 * Internal transaction builder for image-hash provenance operations.
 *
 * Builds and partially signs both the `createSchema` and `createAttestation`
 * transactions, then returns Base64-encoded serialized transactions to be
 * forwarded to the backend.
 */
internal object ProvenanceManager {

    /** Fixed schema name used for all image-hash attestations. */
    const val SCHEMA_NAME = "image_hash"

    /**
     * Session-level PDA cache: walletBase58 → Schema PDA.
     * Avoids re-deriving the PDA on every call within the same app session.
     * SharedPreferences ([ProvenancePrefs]) handles persistence across restarts.
     */
    private val schemaPdaCache = mutableMapOf<String, PublicKey>()

    private val rpc get() = AltudeRpc(SdkConfig.apiConfig.RpcUrl)
    private val feePayerPubKey get() = PublicKey(SdkConfig.apiConfig.FeePayer)

    // ── Keypair resolution ────────────────────────────────────────────────────

    suspend fun getKeyPair(account: String = ""): Keypair {
        val seedData = StorageService.getDecryptedSeed(account)
        if (seedData != null) {
            if (seedData.type == "mnemonic") return Mnemonic(seedData.mnemonic).getKeyPair()
            return if (seedData.privateKey != null)
                SolanaEddsa.createKeypairFromSecretKey(seedData.privateKey!!.copyOfRange(0, 32))
            else throw Error("No seed found in storage")
        } else throw Error("Please set seed first")
    }

    // ── PDA helpers ───────────────────────────────────────────────────────────

    /**
     * Derives the Schema PDA for the fixed [SCHEMA_NAME] and the given [account].
     * Caches the result in [schemaPdaCache] — computed once per session per wallet.
     * Keeps [AttestationProgram] fully hidden from callers outside this class.
     */
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

    // ── Schema — once per wallet ──────────────────────────────────────────────

    /**
     * Builds + signs a `createSchema` tx ONLY if not yet confirmed for this wallet.
     *
     * What `createSchema` does:
     * Registers a reusable template on Solana that defines what fields an image
     * attestation contains (type, hash, mime, name, timestamp). Created ONCE per
     * wallet ever — every image attestation then just references it. Without it,
     * the on-chain program rejects all attestations.
     *
     * Flow:
     *  - [ProvenancePrefs.isSchemaCreated] == true  → return `Result.success(null)` (skip)
     *  - [ProvenancePrefs.isSchemaCreated] == false → build + sign tx, return `Result.success(signedTx)`
     *
     * [Provenance] calls [ProvenancePrefs.markSchemaCreated] ONLY after backend confirms success.
     *
     * @return `Result<String?>` — `null` = schema already exists; `String` = signed tx to send.
     */
    suspend fun ensureSchema(account: String, commitment: String): Result<String?> =
        withContext(Dispatchers.IO) {
            try {
                val keypair   = getKeyPair(account)
                val walletKey = keypair.publicKey.toBase58()

                // Already confirmed on-chain — skip building the tx entirely
                if (ProvenancePrefs.isSchemaCreated(walletKey))
                    return@withContext Result.success(null)

                val hotSigner   = HotSigner(keypair)
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
                    .setSigners(listOf(hotSigner))
                    .build()
                val serialized = Base64.encodeToString(
                    tx.serialize(SerializeConfig(requireAllSignatures = false)),
                    Base64.NO_WRAP
                )
                Result.success(serialized)
            } catch (e: Throwable) {
                Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
            }
        }

    /**
     * Clears schema state for [account] — both in-memory cache and SharedPreferences.
     * Call via [Provenance.resetSession] on wallet switch or logout.
     */
    suspend fun resetSchema(account: String = "") {
        runCatching {
            val walletKey = getKeyPair(account).publicKey.toBase58()
            ProvenancePrefs.reset(walletKey)
            schemaPdaCache.remove(walletKey)
        }
    }

    // ── Attestation ───────────────────────────────────────────────────────────

    /**
     * Builds and signs a `createAttestation` transaction for an [ImageHashPayload].
     * The [schemaPda] must be derived beforehand via [AttestationProgram.deriveSchemaAddress].
     * Returns the Base64-encoded serialized transaction.
     */
    suspend fun attest(
        payload: ImageHashPayload,
        schemaPda: PublicKey
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val keypair   = getKeyPair(payload.account)
            val attester  = keypair.publicKey
            val hotSigner = HotSigner(keypair)
            val recipient = if (payload.recipient.isBlank()) attester
                            else PublicKey(payload.recipient)

            // Structured JSON payload stored in the on-chain attestation
            val payloadJson = """
                {
                  "type": "${payload.type}",
                  "hash": "${payload.hash}",
                  "mime": "${payload.mime}",
                  "name": "${payload.name}",
                  "timestamp": ${payload.timestamp}
                }
            """.trimIndent()

            val instruction = AttestationProgram.createAttestation(
                attester        = attester,
                feePayer        = feePayerPubKey,
                schemaPda       = schemaPda,
                recipient       = recipient,
                attestationData = payloadJson.toByteArray(),
                expireAt        = payload.expireAt
            )

            val blockhash = rpc.getLatestBlockhash(
                commitment = payload.commitment.name
            ).blockhash

            val tx = AltudeTransactionBuilder()
                .setFeePayer(feePayerPubKey)
                .setRecentBlockHash(blockhash)
                .addInstruction(instruction)
                .setSigners(listOf(hotSigner))
                .build()

            val serialized = Base64.encodeToString(
                tx.serialize(SerializeConfig(requireAllSignatures = false)),
                Base64.NO_WRAP
            )
            Result.success(serialized)
        } catch (e: Throwable) {
            Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }
}
