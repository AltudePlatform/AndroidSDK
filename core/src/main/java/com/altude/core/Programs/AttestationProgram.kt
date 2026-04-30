package com.altude.core.Programs

import android.util.Log
import com.altude.core.Programs.Utility.SYSTEM_PROGRAM_ID
import com.altude.core.config.SdkConfig
import com.altude.core.network.AltudeRpc
import com.solana.publickey.SolanaPublicKey
import foundation.metaplex.solana.transactions.AccountMeta
import foundation.metaplex.solana.transactions.TransactionInstruction
import foundation.metaplex.solanapublickeys.PublicKey
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Solana Attestation Service (SAS) Program instructions.
 *
 * Reference:  https://github.com/solana-attestation-service
 *
 * SAS uses an Anchor 8-byte discriminator derived from the instruction name.
 * Discriminators are computed as sha256("global:<instruction_name>")[0..8].
 */
object AttestationProgram {

    private const val TAG = "AttestationProgram"

    /**
     * On-chain program address for the Solana Attestation Service.
     */
    private val MAINNET_PROGRAM_ID = SolanaPublicKey.from("22zoJMtdu4tQc2PzL74ZUT7FrwgB1Udec8DdW4yw4BdG")
    
    private var devnetProgramId: SolanaPublicKey = SolanaPublicKey.from("22zoJMtdu4tQc2PzL74ZUT7FrwgB1Udec8DdW4yw4BdG")

    /**
     * Program id selection must be stable even before [SdkConfig.initialize] finishes.
     *
     * Prefer the explicit SDK network flag (set via SdkConfig.setNetwork), and fall back
     * to any hint in the RpcUrl if apiConfig is already populated.
     */
    val PROGRAM_ID: PublicKey
        get() = PublicKey(if (isDevnet) devnetProgramId.base58() else MAINNET_PROGRAM_ID.base58())

    val isDevnet: Boolean
        get() = SdkConfig.isDevnet || SdkConfig.apiConfig.RpcUrl.lowercase().contains("devnet")

    // ── Anchor discriminators (computed dynamically using SHA256) ─────────────
    
    /**
     * Computes the Anchor discriminator for an instruction name.
     * Anchor uses sha256("global:<instruction_name>")[0..8]
     */
    private fun computeDiscriminator(instructionName: String): ByteArray {
        val preimage = "global:$instructionName"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(preimage.toByteArray(StandardCharsets.UTF_8))
        val disc = hash.sliceArray(0 until 8)
        Log.d(TAG, "Discriminator for '$instructionName': ${disc.toHexString()}")
        return disc
    }
    
    // Lazy-computed discriminators
    var discCreateSchema: ByteArray = ByteArray(8)
    var discAttest: ByteArray = ByteArray(8)


    /**
     * How instruction data is prefixed.
     *
     * IMPORTANT:
     * The deployed Solana Attestation Service program (22zo...) does not expose an Anchor IDL
     * account on mainnet in practice (IDL PDA missing), and it behaves like a non-Anchor program.
     * In that case, Anchor 8-byte discriminators will produce "InvalidInstructionData".
     */
    enum class InstructionPrefixMode {
        /** Anchor: 8-byte discriminator prefix `sha256("global:<name>")[0..8]` */
        ANCHOR_DISCRIMINATOR,
        /** Native enum/tag: 1-byte instruction index (0=createSchema, 1=attest, 2=revoke) */
        NATIVE_U8_INDEX,
        /** No prefix at all (rare; for legacy raw-borsh programs) */
        NONE,
    }

    /**
     * Default prefix mode.
     * For the public SAS program on Mainnet (22zo...) we default to native index.
     */
    var instructionPrefixMode: InstructionPrefixMode = InstructionPrefixMode.NATIVE_U8_INDEX

    fun ensureDiscriminators() {
        if (instructionPrefixMode != InstructionPrefixMode.ANCHOR_DISCRIMINATOR) return
        // Ensure discriminators are computed when running in Anchor mode. If fetchDiscriminatorsFromChain
        // already populated values, skip recomputing. Otherwise compute default discriminators from names.
        try {
            if (discCreateSchema.all { it == 0.toByte() }) {
                discCreateSchema = computeDiscriminator("create_schema")
                Log.d(TAG, "Computed create_schema discriminator: ${discCreateSchema.toHexString()}")
            }
            if (discAttest.all { it == 0.toByte() }) {
                discAttest = computeDiscriminator("create_attestation")
                Log.d(TAG, "Computed create_attestation discriminator: ${discAttest.toHexString()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureDiscriminators failed: ${e.message}")
        }
    }

    /**
     * Fetch the on-chain Anchor IDL for the SAS program and extract the correct
     * instruction discriminators. Call this once at startup or when an
     * InvalidInstructionData error is encountered.
     *
     * The Anchor IDL is stored at:
     *   PDA = findProgramAddress(["anchor:idl"], programId)
     * Account format:
     *   8  bytes  account discriminator (skip)
     *   32 bytes  authority (skip)
     *   4  bytes  data length (u32 LE)
     *   N  bytes  JSON IDL string
     *
     * @return true if discriminators were successfully updated from the IDL.
     */
    suspend fun fetchDiscriminatorsFromChain(rpcEndpoint: String): Boolean {
        if (instructionPrefixMode != InstructionPrefixMode.ANCHOR_DISCRIMINATOR) return false
        return try {
            val rpc = AltudeRpc(rpcEndpoint)

            // Derive IDL PDA: findProgramAddress(["anchor:idl"], programId)
            val idlSeed = "anchor:idl".toByteArray(StandardCharsets.UTF_8)
            val idlPda = PublicKey.findProgramAddress(listOf(idlSeed), PROGRAM_ID).address
            Log.d(TAG, "Fetching on-chain IDL from PDA: ${idlPda.toBase58()}")

            val accountResult = rpc.getAccountInfo<AltudeRpc.SolanaAccountResult>(
                idlPda.toBase58(), isBase64 = true
            )
            val base64Data = accountResult.value?.data?.firstOrNull() ?: run {
                Log.w(TAG, "IDL account not found at ${idlPda.toBase58()}")
                return false
            }

            val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            Log.d(TAG, "IDL account data: ${bytes.size} bytes")

            if (bytes.size < 44) {
                Log.w(TAG, "IDL account too small: ${bytes.size} bytes")
                return false
            }

            // Skip 8-byte account discriminator + 32-byte authority = 40 bytes
            var offset = 40
            val dataLen = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
            offset += 4

            if (bytes.size < offset + dataLen || dataLen <= 0) {
                Log.w(TAG, "IDL data length mismatch: dataLen=$dataLen, available=${bytes.size - offset}")
                return false
            }

            val idlData = bytes.sliceArray(offset until offset + dataLen)
            // Try raw UTF-8 JSON first (newer Anchor), fall back to skip 4-byte header
            val idlJson = tryDecodeIdlJson(idlData) ?: run {
                Log.w(TAG, "Failed to decode IDL JSON")
                return false
            }

            Log.d(TAG, "IDL JSON (first 800 chars): ${idlJson.take(800)}")

            // Extract discriminators from IDL JSON
            val createSchemaDisc = extractDiscriminatorFromIDL(
                idlJson, "createSchema", "create_schema", "create", "initialize", "registerSchema"
            )
            val attestDisc = extractDiscriminatorFromIDL(
                idlJson, "attest", "createAttestation", "create_attestation"
            )
            val revokeDisc = extractDiscriminatorFromIDL(
                idlJson, "revokeAttestation", "revoke", "revoke_attestation", "deleteAttestation"
            )

            if (createSchemaDisc != null) {
                Log.d(TAG, "✅ On-chain createSchema discriminator: ${createSchemaDisc.toHexString()}")
            }
            if (attestDisc != null) {
                Log.d(TAG, "✅ On-chain attest discriminator: ${attestDisc.toHexString()}")
            }
            if (revokeDisc != null) {
                Log.d(TAG, "✅ On-chain revoke discriminator: ${revokeDisc.toHexString()}")
            }


            createSchemaDisc != null

        } catch (e: Exception) {
            Log.w(TAG, "fetchDiscriminatorsFromChain failed: ${e.message}")
            false
        }
    }

    /**
     * Try to decode IDL bytes as a JSON string.
     * Handles:
     *  - Raw UTF-8 JSON (Anchor 0.30+)
     *  - 4-byte length-prefixed JSON (some Anchor versions)
     */
    private fun tryDecodeIdlJson(data: ByteArray): String? {
        // Try raw UTF-8
        val raw = runCatching { String(data, StandardCharsets.UTF_8) }.getOrNull()
        if (raw != null && (raw.trimStart().startsWith("{") || raw.trimStart().startsWith("["))) {
            return raw
        }
        // Try with 4-byte length prefix
        if (data.size >= 4) {
            val len = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (len > 0 && len <= data.size - 4) {
                val withOffset = runCatching {
                    String(data, 4, len, StandardCharsets.UTF_8)
                }.getOrNull()
                if (withOffset != null && withOffset.trimStart().startsWith("{")) return withOffset
            }
        }
        // Try from offset 8 (skip some header)
        if (data.size > 8) {
            val from8 = runCatching { String(data, 8, data.size - 8, StandardCharsets.UTF_8) }.getOrNull()
            if (from8 != null && from8.trimStart().startsWith("{")) return from8
        }
        return null
    }

    /**
     * Searches IDL JSON for a discriminator array associated with any of the given instruction names.
     *
     * Handles both:
     *  - IDL v2 (explicit `"discriminator":[...]` per instruction)
     *  - IDL v1 (no discriminator field — falls back to computing from name found in IDL)
     */
    private fun extractDiscriminatorFromIDL(idlJson: String, vararg names: String): ByteArray? {
        // --- Pass 1: IDL v2 — explicit discriminator arrays ---
        for (name in names) {
            val namePattern = "\"$name\""
            var searchFrom = 0
            while (true) {
                val nameIdx = idlJson.indexOf(namePattern, searchFrom)
                if (nameIdx < 0) break
                searchFrom = nameIdx + 1

                val windowStart = maxOf(0, nameIdx - 100)
                val windowEnd = minOf(idlJson.length, nameIdx + 600)
                val window = idlJson.substring(windowStart, windowEnd)

                val discPattern = "\"discriminator\":"
                val discIdx = window.indexOf(discPattern)
                if (discIdx < 0) continue

                val arrayStart = window.indexOf('[', discIdx + discPattern.length)
                val arrayEnd = window.indexOf(']', if (arrayStart >= 0) arrayStart else 0)
                if (arrayStart < 0 || arrayEnd < 0) continue

                val arrayContent = window.substring(arrayStart + 1, arrayEnd)
                val bytes = arrayContent.split(',')
                    .mapNotNull { it.trim().toIntOrNull() }
                    .map { it.and(0xFF).toByte() }
                    .toByteArray()

                if (bytes.size == 8) {
                    Log.d(TAG, "IDL v2: discriminator for '$name': ${bytes.toHexString()}")
                    return bytes
                }
            }
        }

        // --- Pass 2: IDL v1 — find the actual instruction name, compute discriminator ---
        // Scan for any instruction name that starts with our known names and compute from it.
        // The IDL lists instructions under "instructions":[{"name":"createSchema",...}]
        val instructionsIdx = idlJson.indexOf("\"instructions\"")
        if (instructionsIdx >= 0) {
            val instructionsSection = idlJson.substring(instructionsIdx,
                minOf(idlJson.length, instructionsIdx + 3000))
            for (name in names) {
                val namePattern = "\"name\":\"$name\""
                if (instructionsSection.contains(namePattern)) {
                    // Convert camelCase IDL name to snake_case for discriminator computation
                    val snakeName = camelToSnakeCase(name)
                    val disc = computeDiscriminator(snakeName)
                    Log.d(TAG, "IDL v1: instruction '$name' (→ '$snakeName') discriminator: ${disc.toHexString()}")
                    return disc
                }
            }
        }

        return null
    }

    /** Converts camelCase or PascalCase to snake_case */
    private fun camelToSnakeCase(name: String): String {
        return name.fold(StringBuilder()) { acc, c ->
            if (c.isUpperCase() && acc.isNotEmpty()) acc.append('_').append(c.lowercaseChar())
            else acc.append(c.lowercaseChar())
        }.toString()
    }

    /**
     * Logs the full on-chain IDL so developers can inspect the exact instruction
     * names and discriminators used by the deployed program.
     */
    suspend fun logOnChainIDL(rpcEndpoint: String) {
        try {
            val rpc = AltudeRpc(rpcEndpoint)
            // If this account doesn't exist, the program is most likely not Anchor.
            val idlSeed = "anchor:idl".toByteArray(StandardCharsets.UTF_8)
            val idlPda = PublicKey.findProgramAddress(listOf(idlSeed), PROGRAM_ID).address
            Log.d(TAG, "IDL PDA: ${idlPda.toBase58()}")

            val accountResult = rpc.getAccountInfo<AltudeRpc.SolanaAccountResult>(
                idlPda.toBase58(), isBase64 = true
            )
            val base64Data = accountResult.value?.data?.firstOrNull() ?: run {
                Log.w(TAG, "IDL account NOT FOUND. Program may not be an Anchor program.")
                return
            }

            val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            Log.d(TAG, "IDL account raw bytes: ${bytes.size}")
            Log.d(TAG, "IDL account first 50 hex: ${bytes.take(50).joinToString("") { "%02x".format(it) }}")

            if (bytes.size >= 44) {
                var offset = 40
                val dataLen = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
                offset += 4
                Log.d(TAG, "IDL data length: $dataLen")

                if (dataLen > 0 && bytes.size >= offset + dataLen) {
                    val idlData = bytes.sliceArray(offset until offset + dataLen)
                    val idlJson = tryDecodeIdlJson(idlData)
                    if (idlJson != null) {
                        // Log in 1000-char chunks
                        val chunkSize = 1000
                        for (i in idlJson.indices step chunkSize) {
                            Log.d(TAG, "IDL[${i}..${minOf(i + chunkSize, idlJson.length)}]: ${idlJson.substring(i, minOf(i + chunkSize, idlJson.length))}")
                        }
                    } else {
                        Log.w(TAG, "IDL data not parseable as JSON. Raw hex (first 100): ${idlData.take(100).joinToString("") { "%02x".format(it) }}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "logOnChainIDL failed: ${e.message}")
        }
    }


    // ── PDA seeds ─────────────────────────────────────────────────────────────

    /**
     * Seed helper used by SAS PDAs.
     *
     * SAS derives PDAs with name seeds truncated to the first 32 UTF-8 bytes:
     *   Buffer.from(name, 'utf8').slice(0, 32)
     */
    private fun utf8SeedTrunc32(value: String): ByteArray {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        return if (bytes.size <= 32) bytes else bytes.copyOfRange(0, 32)
    }

    /**
     * Derives the Credential PDA:
     * seeds = ["credential", authority, name(utf8 truncated to 32 bytes)]
     */
    suspend fun deriveCredentialAddress(
        authority: PublicKey,
        name: String
    ): PublicKey {
        val seeds = listOf(
            "credential".toByteArray(StandardCharsets.UTF_8),
            authority.publicKeyBytes,
            utf8SeedTrunc32(name)
        )
        return PublicKey.findProgramAddress(seeds, PROGRAM_ID).address
    }

    /**
     * Derives the Schema PDA:
     * seeds = ["schema", credential, name(utf8 truncated to 32 bytes), version(u8)]
     */
    suspend fun deriveSchemaAddress(
        credential: PublicKey,
        name: String,
        version: Int = 0
    ): PublicKey {
        val versionByte = byteArrayOf((version and 0xFF).toByte())
        val seeds = listOf(
            "schema".toByteArray(StandardCharsets.UTF_8),
            credential.publicKeyBytes,
            utf8SeedTrunc32(name),
            versionByte
        )
        return PublicKey.findProgramAddress(seeds, PROGRAM_ID).address
    }

    /**
     * Derives the Attestation PDA:
     * seeds = ["attestation", credential, schema, nonce]
     */
    suspend fun deriveAttestationAddress(
        credential: PublicKey,
        schema: PublicKey,
        nonce: PublicKey
    ): PublicKey {
        val seeds = listOf(
            "attestation".toByteArray(StandardCharsets.UTF_8),
            credential.publicKeyBytes,
            schema.publicKeyBytes,
            nonce.toByteArray()
        )
        return PublicKey.findProgramAddress(seeds, PROGRAM_ID).address
    }

    // ── Instruction builders ──────────────────────────────────────────────────

    /**
     * Field definition type for schemas.
     */
    enum class FieldType(val value: Int) {
        STRING(0)
    }

    /** FieldDefinition: name and type (no values). */
    data class FieldDefinition(
        val name: String,
        val fieldType: FieldType
    )

    /**
     * Creates a `createSchema` instruction with string field names.
     *
     * Schema defines the structure (field names and types), not the actual values.
     * Actual values are provided later via `createAttestation()`.
     *
     * Accounts (per IDL):
     *   0. payer         (writable, signer) — Rent payer
     *   1. authority     (signer)           — Schema authority (not writable)
     *   2. credential    (read-only)        — Credential the Schema is associated with
     *   3. schema        (writable)         — PDA
     *   4. systemProgram (read-only)
     */
    suspend fun createSchema(
        payer: PublicKey,
        authority: PublicKey,
        credential: PublicKey,
        schema: PublicKey,
        name: String,
        description: String,
        fieldNames: List<String>,
        layout: ByteArray? = null
    ): TransactionInstruction {
        // If no layout provided, default to all STRING (0x00) field types
        val actualLayout = layout ?: ByteArray(fieldNames.size) { FieldType.STRING.value.toByte() }
        return createSchemaWithFields(payer, authority, credential, schema, name, description, actualLayout, fieldNames)
    }

    /**
     * Creates a `createSchema` instruction with typed field definitions.
     */
    suspend fun createSchemaWithFields(
        payer: PublicKey,
        authority: PublicKey,
        credential: PublicKey,
        schema: PublicKey,
        name: String,
        description: String,
        layout: ByteArray,
        fieldNames: List<String>,
        systemProgram: PublicKey = SYSTEM_PROGRAM_ID
    ): TransactionInstruction {
        ensureDiscriminators()
        var data = encodeCreateSchema(name, description, layout, fieldNames)
        // If the runtime is configured to use Anchor discriminators, prefix the 8-byte discriminator.
        if (instructionPrefixMode == InstructionPrefixMode.ANCHOR_DISCRIMINATOR) {
            data = discCreateSchema + data
            Log.d(TAG, "createSchema: prefixed with Anchor discriminator: ${discCreateSchema.toHexString()}")
        }
        Log.d(TAG, "Encoded schema (hex): ${data.toHexString()}")

        val accountsList = listOf(
            AccountMeta(payer, isSigner = true, isWritable = true),
            AccountMeta(authority, isSigner = true, isWritable = false),
            AccountMeta(credential, isSigner = false, isWritable = false),
            AccountMeta(schema, isSigner = false, isWritable = true),
            AccountMeta(systemProgram, isSigner = false, isWritable = false)
        )

        return TransactionInstruction(
            programId = PROGRAM_ID,
            keys = accountsList,
            data = data
        )
    }


    /**
     * Encodes createSchema instruction data per IDL:
     *   [1 byte: discriminant=1] + [String: name] + [String: description] + [bytes: layout] + [Vec<String>: fieldNames]
     */
    private fun encodeCreateSchema(
        name: String,
        description: String,
        layout: ByteArray,
        fieldNames: List<String>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(1)) // discriminant = 1 for CreateSchema (per IDL)
        writeString(out, name)
        writeString(out, description)
        writeBytes(out, layout)
        writeU32(out, fieldNames.size)
        for (fn in fieldNames) {
            writeString(out, fn)
        }
        return out.toByteArray()
    }

    /**
     * Data class for createCredential instruction arguments.
     */
    data class CreateCredentialArgs(
        val name: String,
        val signers: List<PublicKey>
    )

    /**
     * Builds a createCredential instruction.
     *
     * Accounts:
     *   0. payer        (writable, signer)
     *   1. credential   (writable)
     *   2. authority    (signer)
     *   3. systemProgram (readonly)
     *
     * Data:
     *   [1 byte: instruction_id=0] + [String: name] + [Vec<Pubkey>: signers]
     */
    fun createCredential(
        payer: PublicKey,
        credential: PublicKey,
        authority: PublicKey,
        systemProgram: PublicKey = SYSTEM_PROGRAM_ID,
        name: String,
        signers: List<PublicKey>
    ): TransactionInstruction {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0)) // instruction_id = 0 for createCredential
        writeString(out, name)
        writeU32(out, signers.size)
        for (pk in signers) {
            out.write(pk.publicKeyBytes)
        }
        val data = out.toByteArray()
        val accounts = listOf(
            AccountMeta(payer, isSigner = true, isWritable = true),
            AccountMeta(credential, isSigner = false, isWritable = true),
            AccountMeta(authority, isSigner = true, isWritable = false),
            AccountMeta(systemProgram, isSigner = false, isWritable = false)
        )
        return TransactionInstruction(
            programId = PROGRAM_ID,
            keys = accounts,
            data = data
        )
    }

    /**
     * Builds a createAttestation instruction matching the Rust program.
     *
     * Accounts:
     *   0. payer        (writable, signer)
     *   1. authority    (signer)
     *   2. credential   (readonly)
     *   3. schema       (readonly)
     *   4. attestation  (writable)
     *   5. systemProgram (readonly)
     *
     * Data:
     *   [1 byte: discriminator=6] + [Pubkey: nonce] + [Vec<u8>: data] + [i64: expiry]
     */
    fun createAttestation(
        payer: PublicKey,
        authority: PublicKey,
        credential: PublicKey,
        schema: PublicKey,
        attestation: PublicKey,
        systemProgram: PublicKey = SYSTEM_PROGRAM_ID,
        nonce: PublicKey,
        data: ByteArray,
        expiry: Long
    ): TransactionInstruction {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(6)) //createAttest
        out.write(nonce.toByteArray())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array())
        out.write(data)
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(expiry).array())
        val accounts = listOf(
            AccountMeta(payer, isSigner = true, isWritable = true),
            AccountMeta(authority, isSigner = true, isWritable = false),
            AccountMeta(credential, isSigner = false, isWritable = false),
            AccountMeta(schema, isSigner = false, isWritable = false),
            AccountMeta(attestation, isSigner = false, isWritable = true),
            AccountMeta(systemProgram, isSigner = false, isWritable = false)
        )
        return TransactionInstruction(
            programId = PROGRAM_ID,
            keys = accounts,
            data = out.toByteArray()
        )
    }

    // ── Helper functions ──────────────────────────────────────────────────────

    private fun writeString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeU32(out, bytes.size)
        out.write(bytes)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Int) {
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        out.write(b)
    }

    private fun writeBytes(out: ByteArrayOutputStream, value: ByteArray) {
        writeU32(out, value.size)
        out.write(value)
    }

    /**
     * Creates a `createAttestation` instruction with automatic attestation PDA derivation.
     * Delegates to the non-suspend [createAttestation] which matches the IDL.
     *
     * Accounts (per IDL, discriminant=6):
     *   0. payer        (writable, signer)
     *   1. authority    (signer)        — Authorized signer of the Schema's Credential
     *   2. credential   (readonly)      — Credential the Schema is associated with
     *   3. schema       (readonly)      — Schema the Attestation is associated with
     *   4. attestation  (writable)      — Derived PDA
     *   5. systemProgram (readonly)
     *
     * Data:
     *   [1 byte: discriminant=6] + [Pubkey: nonce] + [Vec<u8>: data] + [i64: expiry]
     *
     * @return Pair of (TransactionInstruction, attestation PDA PublicKey)
     */
    suspend fun buildCreateAttestationIx(
        payer: PublicKey,
        authority: PublicKey,
        credential: PublicKey,
        schemaPda: PublicKey,
        nonce: PublicKey,
        attestationData: ByteArray,
        expireAt: Long = 0L
    ): Pair<TransactionInstruction, PublicKey> {
        val attestationPda = deriveAttestationAddress(credential, schemaPda, nonce)

        Log.d(TAG, "Creating attestation instruction (IDL-conformant):")
        Log.d(TAG, "  payer: ${payer.toBase58()}")
        Log.d(TAG, "  authority: ${authority.toBase58()}")
        Log.d(TAG, "  credential: ${credential.toBase58()}")
        Log.d(TAG, "  schemaPda: ${schemaPda.toBase58()}")
        Log.d(TAG, "  attestationPda: ${attestationPda.toBase58()}")
        Log.d(TAG, "  nonce: ${nonce.toBase58()}")
        Log.d(TAG, "  attestationData length: ${attestationData.size}")
        Log.d(TAG, "  expireAt: $expireAt")

        val ix = createAttestation(
            payer = payer,
            authority = authority,
            credential = credential,
            schema = schemaPda,
            attestation = attestationPda,
            nonce = nonce,
            data = attestationData,
            expiry = expireAt
        )
        return Pair(ix, attestationPda)
    }

    // ── Encoding helpers ──────────────────────────────────────────────────────

    private fun encodeU32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun encodeI64(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    
    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }

    // ── On-chain account parser ───────────────────────────────────────────────

    data class AttestationAccountData(
        val payloadJson: String,
        val expireAt: Long,
        val createdAt: Long
    )

    fun parseAttestationData(base64AccountData: String): AttestationAccountData {
        val bytes = android.util.Base64.decode(base64AccountData, android.util.Base64.DEFAULT)
        var offset = 8      // skip Anchor 8-byte discriminator
        offset += 32        // schema pubkey
        offset += 32        // attester pubkey
        offset += 32        // recipient pubkey

        val dataLen = ByteBuffer.wrap(bytes, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        offset += 4

        val payloadJson = String(bytes, offset, dataLen, StandardCharsets.UTF_8)
        offset += dataLen

        val expireAt = ByteBuffer.wrap(bytes, offset, 8)
            .order(ByteOrder.LITTLE_ENDIAN).long
        offset += 8
        val createdAt = ByteBuffer.wrap(bytes, offset, 8)
            .order(ByteOrder.LITTLE_ENDIAN).long

        return AttestationAccountData(
            payloadJson = payloadJson,
            expireAt = expireAt,
            createdAt = createdAt
        )
    }

    data class CredentialAccountData(
        val authority: PublicKey,
        val name: String,
        val authorizedSigners: List<PublicKey>
    )

    /**
     * Parses SAS Credential account data.
     *
     * Observed layout on devnet:
     *   u8   kind/tag (0)
     *   [32] authority pubkey
     *   u32  name length (LE)
     *   [N]  name UTF-8 bytes
     *   u32  authorizedSigners length (LE)
     *   [32]*signers
     */
    fun parseCredentialData(base64AccountData: String): CredentialAccountData {
        val bytes = android.util.Base64.decode(base64AccountData, android.util.Base64.DEFAULT)

        fun attemptParse(startOffset: Int): CredentialAccountData {
            if (bytes.size < startOffset + 1 + 32 + 4 + 4) {
                throw IllegalArgumentException("Credential account data too small: ${bytes.size} bytes (startOffset=$startOffset)")
            }

            var offset = startOffset
            /* val kind = */ offset += 1

            val authorityBytes = bytes.sliceArray(offset until offset + 32)
            val authority = PublicKey(authorityBytes)
            offset += 32

            val nameLen = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
            offset += 4
            if (nameLen < 0 || offset + nameLen > bytes.size) {
                throw IllegalArgumentException("Invalid credential nameLen=$nameLen for dataLen=${bytes.size} (startOffset=$startOffset)")
            }
            val name = String(bytes, offset, nameLen, StandardCharsets.UTF_8)
            offset += nameLen

            if (offset + 4 > bytes.size) {
                throw IllegalArgumentException("Credential data truncated before signers length (startOffset=$startOffset)")
            }
            val signersLen = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
            offset += 4
            if (signersLen < 0) {
                throw IllegalArgumentException("Invalid credential signersLen=$signersLen (startOffset=$startOffset)")
            }
            if (offset + (signersLen * 32) > bytes.size) {
                throw IllegalArgumentException("Credential data truncated: signersLen=$signersLen requires ${signersLen * 32} bytes, remaining=${bytes.size - offset} (startOffset=$startOffset)")
            }

            val signers = buildList(signersLen) {
                repeat(signersLen) {
                    val pkBytes = bytes.sliceArray(offset until offset + 32)
                    add(PublicKey(pkBytes))
                    offset += 32
                }
            }

            return CredentialAccountData(
                authority = authority,
                name = name,
                authorizedSigners = signers
            )
        }

        // Try native (no anchor discriminator) first, then try anchor-offset heuristic.
        return try {
            attemptParse(0)
        } catch (e: Exception) {
            // Log diagnostic info using the CreateSchemaLog tag as requested.
            try {
                Log.d("CreateSchemaLog", "parseCredentialData native parse failed: ${e.message}")
                val preview = bytes.take(32).joinToString("") { "%02x".format(it) }
                Log.d("CreateSchemaLog", "first 32 bytes hex: $preview")
                // Heuristic: maybe the account has an 8-byte Anchor discriminator prefix
                val anchorOffset = 8
                val parsedAnchor = runCatching { attemptParse(anchorOffset) }.getOrNull()
                if (parsedAnchor != null) {
                    Log.d("CreateSchemaLog", "Detected Anchor-style credential account (8-byte discriminator). Parsed name='${parsedAnchor.name}', signers=${parsedAnchor.authorizedSigners.size}")
                    // If Anchor-style layout detected, record the preference globally so future encodes/decodes align.
                    instructionPrefixMode = InstructionPrefixMode.ANCHOR_DISCRIMINATOR
                    // Also populate lazy discriminators now that we're in Anchor mode
                    discCreateSchema = computeDiscriminator("create_schema")
                    discAttest = computeDiscriminator("create_attestation")
                    return parsedAnchor
                } else {
                    Log.d("CreateSchemaLog", "Anchor-style parse also failed — falling back to throwing original error")
                    throw e
                }
            } catch (inner: Exception) {
                throw IllegalArgumentException(inner.message ?: inner.javaClass.simpleName, inner)
            }
        }
    }
}
