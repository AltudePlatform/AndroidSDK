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
import java.math.BigInteger
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
                    cachedRpcUrl = rpcUrl
                    cachedRpc = it
                }
            }
        }
    }

    @get:JvmName("getRpcInstance")
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
        val imageSha256Value = payload.imageHash.joinToString("") { "%02x".format(it) }

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
        // Prefer the caller-provided schema map; otherwise use the payload's own schemaData.
        val payloadMap: Map<String, Any?> = attestationPayloadMap ?: payload.schemaData
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

                // Normalize the user payload against the decoded on-chain schema so
                // dynamic field names and types are resolved consistently.
                val payloadForSchema = normalizeAttestationPayload(decodedSchema, payloadMap)

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

    // ── Attest result helpers ────────────────────────────────────────────────

    internal data class AttestResult(
        val signedTx:      String,
        val certificate:   ProvenanceCertificate,
        /** Attestation PDA derived locally — no backend round-trip needed. */
        val attestationId: String
    )

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

    internal suspend fun attest(
        payload:        ImageHashPayload,
        schemaPda:      PublicKey,
        credentialPda:  PublicKey,
        attestationPayloadMap: Map<String, Any?>? = null
    ): Result<AttestResult> = withContext(Dispatchers.IO) {
        runCatching {
            val keypair = getKeyPair(payload.account)
            attestWithPrefetched(payload, schemaPda, keypair, credentialPda, attestationPayloadMap).getOrThrow()
        }.let { r ->
            r.exceptionOrNull()?.let { e ->
                Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
            } ?: Result.success(r.getOrThrow())
        }
    }

    /**
     * Internal convenience to centralise PDA/keypair derivation for a single attestation.
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
                val credentialPda = AttestationProgram.deriveCredentialAddress(authority = keypair.publicKey, name = credentialName)
                val schemaPda = AttestationProgram.deriveSchemaAddress(credential = credentialPda, name = schemaName, version = 0)
                attestWithPrefetched(p, schemaPda, keypair, credentialPda, attestationPayloadMap).getOrThrow()
            }.let { r ->
                r.exceptionOrNull()?.let { e -> Result.failure(Exception(e.message ?: e.javaClass.simpleName, e)) } ?: Result.success(r.getOrThrow())
            }
        }
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
     *
     * Matching rules:
     * - preserve schema field order
     * - exact key match first
     * - fall back to case-insensitive / separator-insensitive matching
     * - coerce values into the Borsh type expected by the schema
     */
    private fun normalizeAttestationPayload(
        schema: com.altude.provenance.DecodedSasSchemaFixed,
        provided: Map<String, Any?>
    ): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        for (field in schema.fields) {
            val value = resolvePayloadValue(field.name, provided)
            out[field.name] = coerceSchemaValue(field.type, value)
        }
        return out
    }

    private fun resolvePayloadValue(fieldName: String, provided: Map<String, Any?>): Any? {
        if (provided.containsKey(fieldName)) return provided[fieldName]
        val target = canonicalPayloadKey(fieldName)
        return provided.entries.firstOrNull { canonicalPayloadKey(it.key) == target }?.value
    }

    private fun canonicalPayloadKey(key: String): String =
        key.trim().lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun coerceSchemaValue(type: String, value: Any?): Any? {
        val normalizedType = type.trim().lowercase()
        return when {
            normalizedType.startsWith("vec<") && normalizedType.endsWith(">") -> {
                coerceVectorValue(normalizedType.substring(4, normalizedType.length - 1), value)
            }
            normalizedType == "u8" || normalizedType == "u16" || normalizedType == "u32" -> toLongValue(value).toInt()
            normalizedType == "u64" -> toLongValue(value)
            normalizedType == "u128" -> toBigIntegerValue(value)
            normalizedType == "i8" || normalizedType == "i16" || normalizedType == "i32" -> toLongValue(value).toInt()
            normalizedType == "i64" -> toLongValue(value)
            normalizedType == "i128" -> toBigIntegerValue(value)
            normalizedType == "bool" -> toBooleanValue(value)
            normalizedType == "char" -> toCharValue(value)
            normalizedType == "string" -> toStringValue(value)
            else -> value
        }
    }

    private fun coerceVectorValue(innerType: String, value: Any?): Any? {
        val normalizedInner = innerType.trim().lowercase()
        return when {
            normalizedInner == "u8" -> coerceVecU8(value)
            normalizedInner == "u16" || normalizedInner == "u32" -> toList(value) { toLongValue(it).toInt() }
            normalizedInner == "u64" -> toList(value) { toLongValue(it) }
            normalizedInner == "u128" -> toList(value) { toBigIntegerValue(it) }
            normalizedInner == "i8" || normalizedInner == "i16" || normalizedInner == "i32" -> toList(value) { toLongValue(it).toInt() }
            normalizedInner == "i64" -> toList(value) { toLongValue(it) }
            normalizedInner == "i128" -> toList(value) { toBigIntegerValue(it) }
            normalizedInner == "bool" -> toList(value) { toBooleanValue(it) }
            normalizedInner == "char" -> toList(value) { toCharValue(it) }
            normalizedInner == "string" -> toList(value) { toStringValue(it) }
            else -> value
        }
    }

    private fun coerceVecU8(value: Any?): Any? {
        return when (value) {
            null -> null
            is ByteArray -> value
            is String -> {
                val trimmed = value.trim()
                if (looksLikeHex(trimmed)) runCatching { hexToByteArray(trimmed) }.getOrElse { trimmed }
                else trimmed
            }
            is Iterable<*> -> {
                val items = value.toList()
                if (items.all { it is Number || it is String }) {
                    items.map { toLongValue(it).toInt().toByte() }.toByteArray()
                } else {
                    items
                }
            }
            is Array<*> -> coerceVecU8(value.toList())
            is Number -> byteArrayOf((value.toLong() and 0xFFL).toByte())
            else -> value.toString()
        }
    }

    private fun <T> toList(value: Any?, mapper: (Any?) -> T): Any? = when (value) {
        null -> null
        is Iterable<*> -> value.map(mapper)
        is Array<*> -> value.map(mapper)
        is ByteArray -> value.map { mapper(it) }
        else -> listOf(mapper(value))
    }

    private fun toLongValue(value: Any?): Long = when (value) {
        null -> 0L
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull() ?: 0L
        is Boolean -> if (value) 1L else 0L
        else -> value.toString().trim().toLongOrNull() ?: 0L
    }

    private fun toBigIntegerValue(value: Any?): BigInteger = when (value) {
        null -> BigInteger.ZERO
        is BigInteger -> value
        is Number -> BigInteger.valueOf(value.toLong())
        is String -> runCatching { BigInteger(value.trim()) }.getOrDefault(BigInteger.ZERO)
        is Boolean -> if (value) BigInteger.ONE else BigInteger.ZERO
        else -> runCatching { BigInteger(value.toString().trim()) }.getOrDefault(BigInteger.ZERO)
    }

    private fun toBooleanValue(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", ignoreCase = true) || value == "1"
        else -> value.toString().equals("true", ignoreCase = true)
    }

    private fun toCharValue(value: Any?): String = when (value) {
        null -> ""
        is Char -> value.toString()
        is String -> value.take(1)
        else -> value.toString().take(1)
    }

    private fun toStringValue(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Map<*, *> -> canonicalJsonString(value)
        is Iterable<*> -> canonicalJsonString(value)
        is Array<*> -> canonicalJsonString(value.toList())
        is ByteArray -> value.joinToString(separator = "") { "%02x".format(it) }
        else -> value.toString()
    }

    private fun looksLikeHex(value: String): Boolean {
        val trimmed = value.removePrefix("0x")
        return trimmed.isNotEmpty() && trimmed.length % 2 == 0 && trimmed.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    private fun hexToByteArray(hex: String): ByteArray {
        val s = hex.removePrefix("0x").replace(" ", "")
        require(s.length % 2 == 0) { "Invalid hex string length" }
        return ByteArray(s.length / 2) { i ->
            val idx = i * 2
            ((s[idx].digitToInt(16) shl 4) + s[idx + 1].digitToInt(16)).toByte()
        }
    }

    private fun canonicalJsonString(value: Any?): String = when (value) {
        null -> "null"
        is String -> org.json.JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        is ByteArray -> org.json.JSONObject.quote(value.joinToString(separator = "") { "%02x".format(it) })
        is Map<*, *> -> value.entries
            .sortedBy { it.key?.toString().orEmpty() }
            .joinToString(prefix = "{", postfix = "}") { (key, entryValue) ->
                "${org.json.JSONObject.quote(key?.toString().orEmpty())}:${canonicalJsonString(entryValue)}"
            }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { canonicalJsonString(it) }
        is Array<*> -> canonicalJsonString(value.toList())
        else -> org.json.JSONObject.quote(value.toString())
    }
}

