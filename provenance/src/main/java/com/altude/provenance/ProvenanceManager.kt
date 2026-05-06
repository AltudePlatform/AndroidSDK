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
import java.util.concurrent.ConcurrentHashMap

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
    private const val SCHEMA_DEBUG_ENABLED = true

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
        // Client-side creation of credentials is intentionally removed.
        // Callers must create credentials off-chain (backend) or via deployed tooling.
        throw UnsupportedOperationException(
            "createCredential is removed from the provenance SDK. " +
            "Create credentials using your backend or a deployed attestation program. " +
            "If you need help, see README or ask the platform owner to deploy the program and provide PROGRAM_ID."
        )
    }

    const val SCHEMA_NAME = "image_hash"
    // Credential name used by this app for the credential PDA seed. Keep consistent across creation and lookups.
    const val CREDENTIAL_NAME = "image_hash_credential"

    private val schemaPdaCache = ConcurrentHashMap<String, PublicKey>()

    @Volatile
    private var cachedRpcUrl: String? = null

    @Volatile
    private var cachedRpc: AltudeRpc? = null

    private fun getRpc(): AltudeRpc {
        val config = SdkConfig.requireApiConfig()
        val rpcUrl = config.RpcUrl

        val existingRpc = cachedRpc
        if (existingRpc != null && cachedRpcUrl == rpcUrl) {
            return existingRpc
        }

        return synchronized(this) {
            val synchronizedExistingRpc = cachedRpc
            if (synchronizedExistingRpc != null && cachedRpcUrl == rpcUrl) {
                synchronizedExistingRpc
            } else {
                AltudeRpc(rpcUrl).also {
                    cachedRpc = it
                    cachedRpcUrl = rpcUrl
                }
            }
        }
    }

    private val rpc: AltudeRpc
        get() = getRpc()

    private val feePayerPubKey get(): PublicKey {
        return PublicKey(SdkConfig.requireApiConfig().FeePayer)
    }

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
        // Creating a schema from a credential PDA is intentionally removed from the client SDK.
        // Schema creation must be performed by a backend service or an on-chain tool that
        // holds the fee-payer/deployment keys. Return an explicit failure so callers know
        // to perform this operation out-of-band.
        return@withContext Result.failure(Exception(
            "createSchema is removed from the provenance SDK. Create schemas off-chain via your backend or an on-chain tool. " +
            "If you need help, deploy the attestation program and set the schema PDA with Provenance.setSchemaPda(...)."
        ))
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
                // Schema does not exist on-chain — client will NOT build or submit a createSchema
                // transaction. Inform the caller that schema creation must be handled out-of-band.
                return@withContext Result.failure(Exception(
                    "createSchema is removed from the provenance SDK. Please create the schema using your backend or an on-chain tool, or set a predefined schema PDA with Provenance.setSchemaPda(...)."
                ))
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
            // Building a createSchema transaction client-side has been removed.
            // Return an explicit failure so callers must perform schema creation out-of-band.
            Result.failure(Exception(
                "buildSchemaTx is removed from the provenance SDK. Create schemas off-chain via your backend or an on-chain tool."
            ))
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

    // ── SAS schema decoder (moved to helper) ─────────────────────────────────
    // Decoder implementation was moved to `SasSchemaDecoder.kt` to keep
    // `ProvenanceManager` focused on transaction and PDA logic. Use
    // `com.altude.provenance.decodeSasSchemaFixed(base64)` to decode schema
    // account data (returns DecodedSasSchemaFixed from the helper file).

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
                val imageSha256Value = payload.c2paManifest?.assetHash ?: run {
                    // Fallback: if no C2PA manifest is present, derive hex from payload.imageHash
                    try {
                        payload.imageHash.joinToString("") { "%02x".format(it) }
                    } catch (_: Exception) { "" }
                }

                val draft = ProvenanceCertificate(
                    instanceId         = "urn:uuid:${java.util.UUID.randomUUID()}",
                    captureTimestampMs = System.currentTimeMillis(),
                    imageSha256        = imageSha256Value,
            signerAddress      = keypair.publicKey.toBase58(),
            signerPublicKey    = pubKeyHex,
            signature          = "",
            deviceMake         = Build.MANUFACTURER,
            deviceModel        = Build.MODEL,
            osVersion          = Build.VERSION.RELEASE
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
        payload:        ImageHashPayload,
        schemaPda:      PublicKey,
        keypair:        Keypair,
        credentialPda:  PublicKey,
        attestationPayloadMap: Map<String, Any?>? = null
    ): Pair<String, String> {
        val attester  = keypair.publicKey

        // Generate a random nonce (PublicKey) for this attestation
        val nonceSeed = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val nonceKeypair = SolanaEddsa.createKeypairFromSecretKey(nonceSeed)
        val nonce = nonceKeypair.publicKey

        // Derive credential PDA (authority + credential name)
        // IMPORTANT: must be derived from the *credential authority* (the wallet), not the fee payer.


        // Build payload Map with explicit field order required by on-chain schema.
        // If the caller provided an explicit attestationPayloadMap (from the public
        // API) use it directly so callers can supply custom schema-aligned fields.
        val payloadMap: Map<String, Any?> = attestationPayloadMap ?: run {
            // Default deterministic payload used when no explicit map provided.
            buildPayloadJson(payload, attester)
        }
        Log.d("ProvenanceManager", "payloadMap for attestation: $payloadMap")
//        val forAttestcredPda = AttestationProgram.deriveCredentialAddress(feePayerPubKey, "attestcredpda_01")
//        Log.d("AttestationProgram", "forAttestcredPda Example credential PDA (for reference): ${forAttestcredPda}")
        // Determine authority: use fee payer if it equals the attester, otherwise use attester
        val authority = feePayerPubKey
        Log.d("ProvenanceManager", "Using authority for attestation: ${authority.toBase58()}")
        // Try to fetch the on-chain schema account and use the compact layout serializer.
        val schemaAccount = runCatching {
            rpc.getAccountInfo<AltudeRpc.SolanaAccountResult>(
                schemaPda.toBase58(), isBase64 = true
            )
        }.getOrNull()

        val schemaBase64 = schemaAccount?.value?.data?.firstOrNull()
        var attestData: ByteArray? = null
        if (schemaBase64 != null) {
            try {
                val decodedSchema = decodeSasSchemaFixed(schemaBase64)

                // If caller provided an explicit attestationPayloadMap, normalize it to
                // match the on-chain schema field names & types (aliases, hex -> bytes for vec<u8>, etc.)
                val payloadForSchema = if (attestationPayloadMap != null) {
                    normalizeAttestationPayload(decodedSchema, attestationPayloadMap)
                } else {
                    payloadMap
                }

                attestData = SasSchemaBorshSerializer.serializeAttestationData(decodedSchema, payloadForSchema)
            } catch (e: Exception) {
                Log.w("ProvenanceManager", "Failed to decode on-chain schema, falling back to JSON: ${e.message}")
                JSONObject(payloadMap).toString().toByteArray(Charsets.UTF_8)
            }
        } else {
            // Fallback: serialize as UTF-8 JSON if schema not available
            JSONObject(payloadMap).toString().toByteArray(Charsets.UTF_8)
        }
        // Use IDL-conformant instruction builder (discriminant=6, correct account order)
        val (instruction, attestationPda) = AttestationProgram.buildCreateAttestationIx(
            payer           = feePayerPubKey,
            authority       = authority,
            credential      = credentialPda,
            schemaPda       = schemaPda,
            nonce           = nonce,
            // Serialize attestation data according to the on-chain SAS schema when possible.
            attestationData = attestData ?: error("Failed to serialize attestation data"),
            expireAt        = payload.expireAt
        )

        val blockhash = rpc.getLatestBlockhash().blockhash
        val tx = AltudeTransactionBuilder()
            .setFeePayer(feePayerPubKey)
            .setRecentBlockHash(blockhash)
            .addInstruction(instruction)
            //.setSigners(listOf(HotSigner(keypair)))
            .build()
        val signedTx = Base64.encodeToString(
            tx.serialize(SerializeConfig(requireAllSignatures = false)),
            Base64.NO_WRAP
        )
        return Pair(signedTx, attestationPda.toBase58())
    }

    /**
     * Helper to build the deterministic payload JSON string used by the on-chain attestation schema.
     * Public for tests/internal inspection.
     */
    internal fun buildPayloadJson(payload: ImageHashPayload, attester: PublicKey): Map<String, Any?> {
        // Return a deterministic Map of field -> value (preserves insertion order in Kotlin's LinkedHashMap)
        val map = linkedMapOf<String, Any?>()
        // Fixed schema expected by SAS (image_hash credential)
        // image_hash and parent_hash are vec<u8> — provide as ByteArray
        val imageHashBytes = payload.imageHash
        map["image_hash"] = imageHashBytes
        map["parent_hash"] = payload.parentHash ?: ByteArray(0)
        map["hash_algorithm"] = payload.hashAlgorithm
        map["mime_type"] = payload.mimeType
        map["width"] = payload.width
        map["height"] = payload.height
        map["file_size"] = payload.fileSize
        map["filename"] = payload.filename
        map["owner"] = if (payload.owner.isNotBlank()) payload.owner else attester.toBase58()
        map["timestamp"] = payload.timestamp
        return map
    }

    /**
     * Normalize a caller-provided attestation payload Map to the decoded SAS schema.
     * - Preserves field ordering from the schema
     * - Accepts common aliases (camelCase, legacy names)
     * - Converts hex strings for vec<u8> into ByteArray
     */
    private fun normalizeAttestationPayload(schema: com.altude.provenance.DecodedSasSchemaFixed, provided: Map<String, Any?>): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        for (field in schema.fields) {
            val name = field.name
            val type = field.type

            // Try direct key first
            var value: Any? = provided[name]

            // Fallback aliases
            if (value == null) {
                val aliases = when (name) {
                    "image_hash" -> listOf("image_hash", "imageHash", "asset_hash", "assetHash", "hash", "manifestHash", "assetHashHex")
                    "parent_hash" -> listOf("parent_hash", "parentHash", "parentHashHex")
                    "hash_algorithm" -> listOf("hash_algorithm", "hashAlgorithm", "algorithm")
                    "mime_type" -> listOf("mime_type", "mimeType", "mime")
                    "width" -> listOf("width")
                    "height" -> listOf("height")
                    "file_size" -> listOf("file_size", "fileSize", "size")
                    "filename" -> listOf("filename", "fileName", "name")
                    "owner" -> listOf("owner", "ownerAddress", "creator")
                    "timestamp" -> listOf("timestamp", "ts", "time")
                    else -> listOf(name)
                }
                for (k in aliases) {
                    if (provided.containsKey(k)) {
                        value = provided[k]
                        break
                    }
                }
            }

            // Type-specific conversions
            val normalized: Any? = when (type) {
                "vec<u8>" -> {
                    when (value) {
                        is ByteArray -> value
                        is String -> {
                            // Accept hex (0-9a-f) or base64. Detect hex if only hex chars and even length.
                            val s = value.trim()
                            val hexRegex = Regex("^[0-9a-fA-F]{2,}")
                            if ((s.length % 2 == 0) && hexRegex.matches(s)) {
                                try { hexToByteArray(s) } catch (_: Exception) { s.toByteArray(Charsets.UTF_8) }
                            } else {
                                // treat as base64 or raw string; let serializer handle base64 decode
                                s
                            }
                        }
                        is List<*> -> value
                        is Number -> listOf((value.toInt() and 0xFF).toByte())
                        null -> null
                        else -> value.toString().toByteArray(Charsets.UTF_8)
                    }
                }
                "string" -> value?.toString() ?: ""
                else -> value
            }

            out[name] = normalized
        }
        return out
    }

    private fun hexToByteArray(hex: String): ByteArray {
        val s = hex.removePrefix("0x").replace(" ", "")
        require(s.length % 2 == 0) { "Invalid hex string length" }
        return ByteArray(s.length / 2) { i ->
            val idx = i * 2
            ((s[idx].digitToInt(16) shl 4) + s[idx + 1].digitToInt(16)).toByte()
        }
    }

    // ── Batch-optimised attest (pre-fetched keypair + blockhash) ─────────────

    /**
     * Builds certificate + tx using caller-supplied [keypair] and [blockhash].
     * No internal RPC or storage calls — use this inside [Provenance.attestBatch].
     */
    internal suspend fun attestWithPrefetched(
        payload:        ImageHashPayload,
        schemaPda:      PublicKey,
        keypair:        Keypair,
        credentialPda:  PublicKey,
        attestationPayloadMap: Map<String, Any?>? = null
    ): Result<AttestResult> = runCatching {
        val certificate = buildCertificate(payload, keypair)
        val (signedTx, attestationId) = buildTx(payload, schemaPda, keypair, credentialPda, attestationPayloadMap)
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
        payload:        ImageHashPayload,
        schemaPda:      PublicKey,
        credentialPda:  PublicKey,
        attestationPayloadMap: Map<String, Any?>? = null
    ): Result<AttestResult> = withContext(Dispatchers.IO) {
        runCatching {
            val keypair   = getKeyPair(payload.account)
            //val blockhash = fetchBlockhash(payload.commitment.name)
            attestWithPrefetched(payload, schemaPda, keypair, credentialPda, attestationPayloadMap).getOrThrow()
        }.let { r ->
            r.exceptionOrNull()?.let { e ->
                Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
            } ?: Result.success(r.getOrThrow())
        }
    }

    /**
     * Fluent builder for a single-image attest operation.
     * Internal convenience to centralise PDA/keypair derivation and call the
     * existing attest(...) helper. Usage (internal):
     * ProvenanceManager.AttestBuilder()
     *     .withPayload(payload)
     *     .credentialName("name")
     *     .schemaName("schema")
     *     .execute(payloadMap)
     */
    internal class AttestBuilder {
        private var payload: ImageHashPayload? = null
        private var credentialName: String = CREDENTIAL_NAME
        private var schemaName: String = SCHEMA_NAME

        fun withPayload(p: ImageHashPayload) = apply { this.payload = p }
        fun credentialName(n: String) = apply { this.credentialName = n }
        fun schemaName(n: String) = apply { this.schemaName = n }

        suspend fun execute(attestationPayloadMap: Map<String, Any?>? = null): Result<AttestResult> = withContext(Dispatchers.IO) {
            val p = payload ?: return@withContext Result.failure(Exception("Missing payload in AttestBuilder"))
            return@withContext runCatching {
                val keypair = getKeyPair(p.account)
                val feePayer = PublicKey(SdkConfig.requireApiConfig().FeePayer)
                // Derive credential/schema PDAs using the fee payer as authority (matching public API behaviour)
                val credentialPda = AttestationProgram.deriveCredentialAddress(authority = feePayer, name = credentialName)
                val schemaPda = AttestationProgram.deriveSchemaAddress(credential = credentialPda, name = schemaName, version = 1)
                // Delegate to existing attest helper (which builds certificate + tx)
                attest(p, schemaPda, credentialPda, attestationPayloadMap).getOrThrow()
            }.let { r ->
                r.exceptionOrNull()?.let { e -> Result.failure(Exception(e.message ?: e.javaClass.simpleName, e)) } ?: Result.success(r.getOrThrow())
            }
        }
    }
}
