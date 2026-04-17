package com.altude.provenance
import com.altude.core.Programs.Utility

import android.os.Build
import android.util.Base64
import android.util.Log
import com.altude.core.Programs.AttestationProgram
import com.altude.core.config.SdkConfig
import com.altude.core.helper.Mnemonic
import com.altude.core.model.AltudeTransactionBuilder
import com.altude.core.model.HotSigner
import com.altude.core.model.TransactionVersion
import com.altude.core.network.AltudeRpc
import com.altude.core.service.StorageService
import com.altude.provenance.data.ImageHashPayload
import com.altude.provenance.data.ProvenanceCertificate
import com.altude.provenance.data.ProvenancePrefs
import foundation.metaplex.rpc.RPC
import org.json.JSONObject
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
    private const val SCHEMA_DEBUG_ENABLED = false

    private inline fun schemaDebug(block: () -> Unit) {
        if (SCHEMA_DEBUG_ENABLED) {
            block()
        }
    }
    /**
     * Builds and signs a createCredential transaction for the given account and schema.
     * Returns the base64-encoded signed transaction.
     */
    @JvmStatic
    suspend fun buildCredentialTx(
        name: String,
        account: String = "",
        commitment: String = "confirmed"
    ): String = withContext(Dispatchers.IO) {
        val keypair = ProvenanceManager.getKeyPair(account)
        val payer = feePayerPubKey
        val authority = keypair.publicKey
        // Derive credential PDA from authority + credential name (credential name == instruction arg 'name')
        val credential = AttestationProgram.deriveCredentialAddress(
            authority = authority,
            name = name
        )
        println("credential created: " + credential.toBase58())
        val systemProgram = Utility.SYSTEM_PROGRAM_ID
        val signers = listOf(feePayerPubKey, authority)
        val ix = AttestationProgram.createCredential(
            payer = feePayerPubKey,
            credential = credential,
            authority = authority,
            systemProgram = systemProgram,
            name = name,
            signers = signers
        )
        val authorizedSignature = HotSigner(
            com.altude.core.data.SolanaKeypair(keypair.publicKey, keypair.secretKey)
        )//"http://10.0.2.2:8899"
        val blockhash = RPC(SdkConfig.apiConfig.RpcUrl).getLatestBlockhash(null).blockhash //rpc.getLatestBlockhash(commitment = commitment).blockhash
        val txBuilder = AltudeTransactionBuilder(TransactionVersion.Legacy)
            .addInstruction(ix)
            .setRecentBlockHash(blockhash)
            .setFeePayer(payer)
            .setSigners(listOf(authorizedSignature))
        val tx = txBuilder.build()
        Base64.encodeToString(tx.serialize(SerializeConfig(requireAllSignatures = false)), Base64.NO_WRAP)
    }

    const val SCHEMA_NAME = "image_hash"
    // Credential name used by this app for the credential PDA seed. Keep consistent across creation and lookups.
    const val CREDENTIAL_NAME = "image_hash_credential"

    private val schemaPdaCache = mutableMapOf<String, PublicKey>()
    private val rpc            get() = AltudeRpc(SdkConfig.apiConfig.RpcUrl)
    private val feePayerPubKey get() = PublicKey(SdkConfig.apiConfig.FeePayer)

    // ── Keypair ───────────────────────────────────────────────────────────────

    suspend fun getKeyPair(account: String = ""): Keypair {
        val seed = StorageService.getDecryptedSeed(account)
            ?: throw IllegalStateException("Please set seed first")
        if (seed.type == "mnemonic") return Mnemonic(seed.mnemonic).getKeyPair()
        return seed.privateKey?.let {
            SolanaEddsa.createKeypairFromSecretKey(it.copyOfRange(0, 32))
        } ?: throw IllegalStateException("No seed found in storage")
    }

    /**
     * Gets the wallet key (Base58 address) for a given account.
     * If account is empty, uses the default wallet.
     */
    suspend fun getWalletKey(account: String = ""): String {
        return getKeyPair(account).publicKey.toBase58()
    }

    // ── PDA ───────────────────────────────────────────────────────────────────

    internal suspend fun deriveSchemaAddress(account: String = ""): PublicKey {
        val keypair   = getKeyPair(account)  // Normalizes account (empty → default)
        val walletKey = keypair.publicKey.toBase58()
        // Validate public key and program ID before PDA derivation
        if (!isValidBase58(walletKey)) {
            throw IllegalArgumentException("Invalid Base58 public key for wallet: $walletKey")
        }
        try {
            PublicKey(walletKey)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid Base58 public key for wallet: $walletKey", e)
        }
        val programId = AttestationProgram.PROGRAM_ID
        println("[DEBUG] keypair.publicKey: $walletKey")
        println("[DEBUG] AttestationProgram.PROGRAM_ID: ${programId.toBase58()}")
        return schemaPdaCache.getOrPut(walletKey) {
            // 1. Check if a predefined schema PDA is stored
            val predefinedPda = ProvenancePrefs.getSchemaPda(walletKey)
            if (predefinedPda != null) {
                return@getOrPut PublicKey(predefinedPda)
            }
            // 2. Otherwise derive it normally
            // Derive credential PDA first (authority + credential name), then derive schema from that credential
            run {
                val credentialPda = AttestationProgram.deriveCredentialAddress(authority = keypair.publicKey, name = CREDENTIAL_NAME)
                AttestationProgram.deriveSchemaAddress(credential = credentialPda, name = SCHEMA_NAME, version = 0)
            }
        }
    }

    /**
     * Sets a predefined schema PDA for the wallet.
     * Use this when you already have a known schema PDA (e.g., from devnet testing or deployment).
     * This skips the need to create a new schema for the wallet.
     *
     * @param schemaPdaBase58 The schema PDA in Base58 format
     * @param account The wallet account (empty string uses default wallet)
     */
    suspend fun setSchemaPda(schemaPdaBase58: String, account: String = "") {
        val walletKey = getWalletKey(account)  // Normalizes account (empty → default)
        
        // Validate it's a valid Base58 public key
        try {
            PublicKey(schemaPdaBase58)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid Base58 schema PDA: $schemaPdaBase58", e)
        }
        
        // Store in prefs and cache
        ProvenancePrefs.setSchemaPda(walletKey, schemaPdaBase58)
        schemaPdaCache[walletKey] = PublicKey(schemaPdaBase58)
    }

    // ── Schema ────────────────────────────────────────────────────────────────

    /**
     * Ensures schema exists for a provided credential PDA.
     *
     * This is used when the backend (or external system) dictates the credential PDA,
     * and the client needs to create the associated schema account deterministically.
     *
     * @return base64 signed createSchema transaction, or null if schema already exists on-chain.
     */
    internal suspend fun ensureSchemaForCredentialPdaInternal(
        account: String,
        commitment: String,
        credentialPdaBase58: String,
        schemaName: String = SCHEMA_NAME
    ): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val keypair = getKeyPair(account)
            val walletKey = keypair.publicKey.toBase58()

            val credentialPda = try {
                PublicKey(credentialPdaBase58)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid credentialPda base58: $credentialPdaBase58", e)
            }

            // ── Validate credential account on-chain BEFORE attempting schema creation ──
            val credentialAccountInfo = runCatching {
                rpc.getAccountInfo<AltudeRpc.SolanaAccountResult>(
                    credentialPda.toBase58(),
                    isBase64 = true
                )
            }.getOrNull()

            val credentialValue = credentialAccountInfo?.value
                ?: throw IllegalStateException(
                    "Credential account does not exist on-chain: ${credentialPda.toBase58()}"
                )

            val owner = credentialValue.owner
            val expectedOwner = AttestationProgram.PROGRAM_ID.toBase58()
            if (owner != expectedOwner) {
                throw IllegalStateException(
                    "Credential account owner mismatch. expected=$expectedOwner actual=$owner credential=${credentialPda.toBase58()}"
                )
            }

            val base64Data = credentialValue.data?.firstOrNull()
                ?: throw IllegalStateException("Credential account missing data")

            val parsedCredential = AttestationProgram.parseCredentialData(base64Data)
            Log.d("CreateSchema", "Parsed credential: authority=${parsedCredential.authority.toBase58()} name='${parsedCredential.name}' signers=${parsedCredential.authorizedSigners.size}")

            // The schema authority must match the credential authority.
            if (parsedCredential.authority != keypair.publicKey) {
                throw IllegalStateException(
                    "Credential authority mismatch. credentialAuthority=${parsedCredential.authority.toBase58()} walletAuthority=${keypair.publicKey.toBase58()}"
                )
            }

            // Payer must be an authorized signer for the Credential (common SAS constraint).
            if (parsedCredential.authorizedSigners.none { it == feePayerPubKey }) {
                throw IllegalStateException(
                    "Fee payer is not an authorized signer of this credential. feePayer=${feePayerPubKey.toBase58()} credentialSigners=${parsedCredential.authorizedSigners.joinToString { it.toBase58() }}"
                )
            }

            // Make sure the credential PDA provided actually matches the PDA derived from (authority, name).
            val expectedCredentialPda = AttestationProgram.deriveCredentialAddress(
                authority = parsedCredential.authority,
                name = parsedCredential.name
            )
            if (expectedCredentialPda != credentialPda) {
                throw IllegalStateException(
                    "Credential PDA mismatch (seed derivation). expected=${expectedCredentialPda.toBase58()} provided=${credentialPda.toBase58()} authority=${parsedCredential.authority.toBase58()} name='${parsedCredential.name}'"
                )
            }

            // Derive schema PDA from the provided credential.
            val schemaPda = AttestationProgram.deriveSchemaAddress(
                credential = credentialPda,
                name = schemaName,
                version = 0
            )
            Log.d("CreateSchema", "Schema PDA: ${schemaPda.toBase58()}")

            // On-chain check: if schema account exists, restore flag + return null
            val onChainAccount = runCatching {
                rpc.getAccountInfo<AltudeRpc.SolanaAccountResult>(
                    schemaPda.toBase58(),
                    isBase64 = true
                )
            }.getOrNull()

            if (onChainAccount?.value != null) {
                ProvenancePrefs.markSchemaCreated(walletKey)
                return@withContext Result.success(null)
            }

            // Build createSchema instruction.
            val instruction = AttestationProgram.createSchema(
                payer = feePayerPubKey,
                authority = keypair.publicKey,
                credential = credentialPda,
                schema = schemaPda,
                name = schemaName,
                description = "Stores SHA-256 hash of images",
                fieldNames = listOf("type", "hash", "mime", "name", "timestamp")
            )

            val blockhash = RPC(SdkConfig.apiConfig.RpcUrl).getLatestBlockhash(null).blockhash
            val tx = AltudeTransactionBuilder(TransactionVersion.Legacy)
                .setFeePayer(feePayerPubKey)
                .setRecentBlockHash(blockhash)
                .addInstruction(instruction)
                // NOTE: device wallet signs here; in production your backend should sign with fee payer key.
                .setSigners(listOf(HotSigner(keypair)))
                .build()

            val bytes = tx.serialize(SerializeConfig(requireAllSignatures = false))
            val txB64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            Result.success(txB64)
        } catch (e: Throwable) {
            Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }

    suspend fun ensureSchema(account: String, commitment: String): Result<String?> =
        withContext(Dispatchers.IO) {
            try {
                val keypair   = getKeyPair(account)  // Normalizes account (empty → default)
                val walletKey = keypair.publicKey.toBase58()

                // 1. Fast path — SharedPreferences (zero extra RPC calls in normal use)
                if (ProvenancePrefs.isSchemaCreated(walletKey))
                    return@withContext Result.success(null)

                // 2. On-chain fallback — handles app reinstall / clear data.
                //    The PDA is deterministic so we can always derive it without storing it.
                //    If the account already exists on-chain, restore the flag and skip.
                val schemaPda = runCatching {
                    deriveSchemaAddress(keypair.publicKey.toBase58())
                }.getOrElse { e ->
                    error("Failed to derive schema PDA (check AttestationProgram.PROGRAM_ID is valid Base58): ${e.message}")
                }
                val onChainAccount = runCatching {
                    rpc.getAccountInfo<AltudeRpc.SolanaAccountResult>(
                        schemaPda.toBase58(),
                        isBase64 = true
                    )
                }.getOrNull()

                if (onChainAccount?.value != null) {
                    ProvenancePrefs.markSchemaCreated(walletKey)
                    return@withContext Result.success(null)
                }

                // 3. Schema does not exist on-chain — build the createSchema instruction
                schemaDebug {
                    println("[DEBUG] Building createSchema instruction...")
                    println("[DEBUG] Authority: ${keypair.publicKey.toBase58()}")
                    println("[DEBUG] FeePayer: ${feePayerPubKey.toBase58()}")
                    println("[DEBUG] Schema PDA: ${schemaPda.toBase58()}")
                    println("[DEBUG] Program ID: ${AttestationProgram.PROGRAM_ID.toBase58()}")
                    println("[DEBUG] Is DevNet: ${AttestationProgram.isDevnet}")
                }

                // 3a. Calibrate discriminators from on-chain IDL only when explicit schema debugging is enabled
                schemaDebug {
                    val rpcUrl = SdkConfig.apiConfig.RpcUrl
                    AttestationProgram.logOnChainIDL(rpcUrl)
                    val idlFetched = runCatching {
                        AttestationProgram.fetchDiscriminatorsFromChain(rpcUrl)
                    }.getOrElse { false }
                    println("[DEBUG] On-chain IDL discriminator fetch: ${if (idlFetched) "✅ SUCCESS" else "⚠️ FAILED (using computed)"}")
                    println("[DEBUG] createSchema discriminator (hex): ${AttestationProgram.discCreateSchema.joinToString("") { "%02x".format(it) }}")
                    println("[DEBUG] createSchema discriminator (dec): ${AttestationProgram.discCreateSchema.joinToString(",") { (it.toInt() and 0xFF).toString() }}")
                }

                // Derive credential PDA (example: using payer, authority, schema)
                // Credential PDA derived from authority + credential name
                val credentialPda = AttestationProgram.deriveCredentialAddress(
                    authority = keypair.publicKey,
                    name = CREDENTIAL_NAME
                )
                val instruction = AttestationProgram.createSchema(
                    payer       = feePayerPubKey,
                    authority   = keypair.publicKey,
                    credential  = credentialPda,
                    schema      = schemaPda,
                    name        = SCHEMA_NAME,
                    description = "Stores SHA-256 hash of images",
                    fieldNames  = listOf("type", "hash", "mime", "name", "timestamp")
                )

                println("[DEBUG] Instruction accounts:")
                instruction.keys.forEachIndexed { index, meta ->
                    println("[DEBUG]   [$index] ${meta.publicKey.toBase58()} signer=${meta.isSigner} writable=${meta.isWritable}")
                }
                println("[DEBUG] Instruction data length: ${instruction.data.size} bytes")
                println("[DEBUG] Instruction data (hex): ${instruction.data.joinToString("") { "%02x".format(it) }}")

                val blockhash = rpc.getLatestBlockhash(commitment = commitment).blockhash
                println("[DEBUG] Blockhash: $blockhash")

                val tx = AltudeTransactionBuilder(TransactionVersion.Legacy)
                    .setFeePayer(feePayerPubKey)
                    .setRecentBlockHash(blockhash)
                    .addInstruction(instruction)
                    .setSigners(listOf(HotSigner(keypair)))
                    .build()
                val bytearraytx = tx.serialize(SerializeConfig(requireAllSignatures = false))

                println("[DEBUG] Serialized transaction length: ${bytearraytx.size} bytes")

                val txString = Base64.encodeToString(
                    bytearraytx,
                    Base64.NO_WRAP
                )

                println("[DEBUG] Base64 transaction: $txString")

                Result.success(
                    txString
                )
            } catch (e: Throwable) {
                Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
            }
        }


    suspend fun resetSchema(account: String = "") {
        runCatching {
            val walletKey = getWalletKey(account)  // Normalizes account (empty → default)
            ProvenancePrefs.reset(walletKey)
            schemaPdaCache.remove(walletKey)
        }
    }

    /**
     * Builds (but does NOT check or submit) a createSchema transaction.
     * Used by retry loops / debugging to inspect the exact bytes produced by the SDK.
     */
    internal suspend fun buildSchemaTx(account: String, commitment: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val keypair = getKeyPair(account)
                val schemaPda = deriveSchemaAddress(keypair.publicKey.toBase58())

                val credentialPda = AttestationProgram.deriveCredentialAddress(
                    authority = keypair.publicKey,
                    name = CREDENTIAL_NAME
                )
                val instruction = AttestationProgram.createSchema(
                    payer       = feePayerPubKey,
                    authority   = keypair.publicKey,
                    credential  = credentialPda,
                    schema      = schemaPda,
                    name        = SCHEMA_NAME,
                    description = "Stores SHA-256 hash of images",
                    fieldNames  = listOf("type", "hash", "mime", "name", "timestamp")
                )

                val blockhash = rpc.getLatestBlockhash(commitment = commitment).blockhash
                val tx = AltudeTransactionBuilder(TransactionVersion.Legacy)
                    .setFeePayer(feePayerPubKey)
                    .setRecentBlockHash(blockhash)
                    .addInstruction(instruction)
                    .setSigners(listOf(HotSigner(keypair)))
                    .build()

                val bytes = tx.serialize(SerializeConfig(requireAllSignatures = false))
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        }

    // ── On-chain fetch ────────────────────────────────────────────────────────

    /**
     * Fetches and parses an Attestation PDA account directly from Solana RPC.
     * No backend involved — trustless on-chain read.
     *
     * @param attestationId Attestation PDA in Base58 (from [ProvenanceResult.response?.attestationId]
     *                      or stored in the sidecar / embedded manifest).
     */
    internal suspend fun fetchAttestationOnChain(
        attestationId: String
    ): AttestationProgram.AttestationAccountData = withContext(Dispatchers.IO) {
        val accountResult = rpc.getAccountInfo<AltudeRpc.SolanaAccountResult>(
            attestationId,
            isBase64 = true
        )
        val base64Data = accountResult.value?.data?.firstOrNull()
            ?: error("Attestation account not found on-chain: $attestationId")
        AttestationProgram.parseAttestationData(base64Data)
    }

    // ── AttestResult ──────────────────────────────────────────────────────────

    internal data class AttestResult(
        val signedTx:      String,
        val certificate:   ProvenanceCertificate,
        /** Attestation PDA derived locally — no backend round-trip needed. */
        val attestationId: String
    )

    // ── Network helpers ───────────────────────────────────────────────────────

    /**
     * Fetches a fresh Solana blockhash with retry logic.
     * Call ONCE before a batch and share the result — Solana blockhashes are valid
     * for ~150 slots (~90 s). [attestWithPrefetched] refreshes automatically every
     * 30 s when used via [Provenance.attestBatch].
     * 
     * Includes retry logic to handle RPC failures and rate limiting.
     */
    internal suspend fun fetchBlockhash(commitment: String): String {
        var lastException: Exception? = null
        
        // Try up to 3 times with exponential backoff
        repeat(3) { attempt ->
            try {
                val result = rpc.getLatestBlockhash(commitment = commitment)
                return result.blockhash
            } catch (e: Exception) {
                lastException = e
                println("Blockhash fetch attempt ${attempt + 1} failed: ${e.message}")
                
                // If this is not the last attempt, wait before retrying
                if (attempt < 2) {
                    val delayMs = 500L * (attempt + 1) // 500ms, 1000ms
                    kotlinx.coroutines.delay(delayMs)
                }
            }
        }
        
        // All attempts failed, throw the last exception
        throw Exception("Failed to fetch blockhash after 3 attempts: ${lastException?.message}", lastException)
    }

    // ── Base58 → ByteArray helper ─────────────────────────────────────────────

    private fun isValidBase58(str: String): Boolean {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        return str.all { it in alphabet }
    }

    /**
     * Decodes a Base58-encoded Solana public key to its raw 32-byte array.
     * Used instead of `keypair.publicKey.bytes` to avoid relying on a specific
     * property that may differ across Metaplex / web3-solana library versions.
     */
    private fun decodeBase58(encoded: String): ByteArray {
        val alphabet    = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        if (!isValidBase58(encoded)) {
            throw IllegalArgumentException("Invalid Base58 string: $encoded")
        }
        val leadingZeros = encoded.takeWhile { it == '1' }.length
        var bigInt = java.math.BigInteger.ZERO
        for (c in encoded) {
            val idx = alphabet.indexOf(c)
            if (idx < 0) throw IllegalArgumentException("Invalid Base58 char: $c")
            bigInt = bigInt.multiply(java.math.BigInteger.valueOf(58L))
                .add(java.math.BigInteger.valueOf(idx.toLong()))
        }
        val rawBytes = bigInt.toByteArray()
        // BigInteger may prefix a 0x00 sign byte — strip it
        val stripped = if (rawBytes.isNotEmpty() && rawBytes[0] == 0.toByte())
            rawBytes.copyOfRange(1, rawBytes.size) else rawBytes
        val result = ByteArray(leadingZeros) + stripped
        return when {
            result.size == 32 -> result
            result.size > 32  -> result.copyOfRange(result.size - 32, result.size)
            else              -> ByteArray(32 - result.size) + result
        }
    }

    // ── Offline-safe certificate builder ─────────────────────────────────────

    /**
     * Builds and ED25519-signs a [ProvenanceCertificate] from [payload].
     * Suspend because [SolanaEddsa.sign] is a suspend function.
     * No network calls — safe to call when the device is offline.
     */
    internal suspend fun buildCertificate(
        payload: ImageHashPayload,
        keypair: Keypair
    ): ProvenanceCertificate {
        val pubKeyBytes = decodeBase58(keypair.publicKey.toBase58())
        val pubKeyHex   = pubKeyBytes.joinToString("") { "%02x".format(it) }
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
     * Builds and signs the Solana `createAttestation` transaction.
     * Returns a [Pair] of (base64-encoded signed tx, Attestation PDA Base58).
     * The PDA is derived deterministically — no backend round-trip needed.
     */
    internal suspend fun buildTx(
        payload:   ImageHashPayload,
        schemaPda: PublicKey,
        keypair:   Keypair,
        blockhash: String
    ): Pair<String, String> {
        val attester  = keypair.publicKey

        // Generate a random nonce (PublicKey) for this attestation
        val nonceSeed = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val nonceKeypair = SolanaEddsa.createKeypairFromSecretKey(nonceSeed)
        val nonce = nonceKeypair.publicKey

        // Derive credential PDA (authority + credential name)
        // IMPORTANT: must be derived from the *credential authority* (the wallet), not the fee payer.
        val credentialPda = AttestationProgram.deriveCredentialAddress(
            authority = attester,
            name = CREDENTIAL_NAME
        )

        val payloadJson = JSONObject().apply {
            put("type",      payload.type)
            put("hash",      payload.hash)
            put("mime",      payload.mime)
            put("name",      payload.name)
            put("timestamp", payload.timestamp)
        }.toString()

        // Use IDL-conformant instruction builder (discriminant=6, correct account order)
        val (instruction, attestationPda) = AttestationProgram.buildCreateAttestationIx(
            payer           = feePayerPubKey,
            authority       = attester,
            credential      = credentialPda,
            schemaPda       = schemaPda,
            nonce           = nonce,
            attestationData = payloadJson.toByteArray(),
            expireAt        = payload.expireAt
        )
        val tx = AltudeTransactionBuilder()
            .setFeePayer(feePayerPubKey)
            .setRecentBlockHash(blockhash)
            .addInstruction(instruction)
            .setSigners(listOf(HotSigner(keypair)))
            .build()
        val signedTx = Base64.encodeToString(
            tx.serialize(SerializeConfig(requireAllSignatures = false)),
            Base64.NO_WRAP
        )
        return Pair(signedTx, attestationPda.toBase58())
    }

    // ── Batch-optimised attest (pre-fetched keypair + blockhash) ─────────────

    /**
     * Builds certificate + tx using caller-supplied [keypair] and [blockhash].
     * No internal RPC or storage calls — use this inside [Provenance.attestBatch].
     */
    internal suspend fun attestWithPrefetched(
        payload:   ImageHashPayload,
        schemaPda: PublicKey,
        keypair:   Keypair,
        blockhash: String
    ): Result<AttestResult> = runCatching {
        val certificate = buildCertificate(payload, keypair)
        val (signedTx, attestationId) = buildTx(payload, schemaPda, keypair, blockhash)
        AttestResult(signedTx = signedTx, certificate = certificate, attestationId = attestationId)
    }.let { r ->
        r.exceptionOrNull()?.let { e ->
            Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        } ?: Result.success(r.getOrThrow())
    }

    // ── Single-image convenience (fetches blockhash internally) ───────────────

    /**
     * Convenience wrapper for single-image attestation.
     * Fetches keypair and blockhash internally.
     * For batches use [attestWithPrefetched] instead.
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
            } ?: Result.success(r.getOrThrow())
        }
    }
}
