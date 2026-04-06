package com.altude.core.Programs

import android.util.Log
import com.altude.core.Programs.Utility.SYSTEM_PROGRAM_ID
import com.altude.core.config.SdkConfig
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
    
    // Solana sysvar addresses
    val RENT_SYSVAR = PublicKey("SysvarRent111111111111111111111111111111111")

    /**
     * On-chain program address for the Solana Attestation Service.
     */
    private val MAINNET_PROGRAM_ID = SolanaPublicKey.from("22zoJMtdu4tQc2PzL74ZUT7FrwgB1Udec8DdW4yw4BdG")
    
    private var devnetProgramId: SolanaPublicKey = SolanaPublicKey.from("22zoJMtdu4tQc2PzL74ZUT7FrwgB1Udec8DdW4yw4BdG")

    fun setDevnetProgramId(programIdBase58: String) {
        devnetProgramId = SolanaPublicKey.from(programIdBase58)
    }

    val PROGRAM_ID: PublicKey
        get() = PublicKey(
            if (SdkConfig.apiConfig.RpcUrl.lowercase().contains("devnet")) 
                devnetProgramId.base58() 
            else 
                MAINNET_PROGRAM_ID.base58()
        )
    
    val isDevnet: Boolean
        get() = SdkConfig.apiConfig.RpcUrl.lowercase().contains("devnet")

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
    
    /**
     * Instruction name variant to use for createSchema.
     * SAS may use different naming conventions.
     * 
     * Change this if you get error 0x0 (discriminator mismatch)
     */
    enum class InstructionVariant(val schemaName: String, val attestName: String, val revokeName: String) {
        // Standard Anchor snake_case
        SNAKE_CASE("create_schema", "attest", "revoke"),
        // CamelCase
        CAMEL_CASE("createSchema", "attest", "revoke"),
        // Register variants
        REGISTER("register", "attest", "revoke"),
        REGISTER_SCHEMA("register_schema", "create_attestation", "revoke_attestation"),
        // Initialize variants
        INIT("init", "attest", "revoke"),
        INIT_SCHEMA("init_schema", "attest", "revoke"),
        INITIALIZE("initialize", "attest", "revoke"),
        INITIALIZE_SCHEMA("initialize_schema", "attest", "revoke"),
        // Create variants
        CREATE("create", "attest", "revoke"),
        // Add variants
        ADD_SCHEMA("add_schema", "add_attestation", "remove_attestation"),
        // New variants
        NEW_SCHEMA("new_schema", "new_attestation", "delete_attestation"),
    }
    
    // Default variant - try SNAKE_CASE first (most common in Anchor)
    var instructionVariant = InstructionVariant.SNAKE_CASE
    
    // Whether to include Rent sysvar in accounts (some programs require it)
    var includeRentSysvar = true
    
    // Lazy-computed discriminators (recomputed when variant changes)
    private var cachedVariant: InstructionVariant? = null
    private var discCreateSchema: ByteArray = ByteArray(8)
    private var discAttest: ByteArray = ByteArray(8)
    private var discRevoke: ByteArray = ByteArray(8)
    
    private fun ensureDiscriminators() {
        if (cachedVariant != instructionVariant) {
            discCreateSchema = computeDiscriminator(instructionVariant.schemaName)
            discAttest = computeDiscriminator(instructionVariant.attestName)
            discRevoke = computeDiscriminator(instructionVariant.revokeName)
            cachedVariant = instructionVariant
            
            Log.d(TAG, "Using instruction variant: ${instructionVariant.name}")
            Log.d(TAG, "  ${instructionVariant.schemaName}: ${discCreateSchema.toHexString()}")
            Log.d(TAG, "  ${instructionVariant.attestName}: ${discAttest.toHexString()}")
            Log.d(TAG, "  ${instructionVariant.revokeName}: ${discRevoke.toHexString()}")
        }
    }

    /**
     * Debug function to print all discriminator variants
     */
    fun printAllDiscriminatorVariants() {
        Log.d(TAG, "=== All Discriminator Variants ===")
        for (variant in InstructionVariant.values()) {
            val disc = computeDiscriminator(variant.schemaName)
            Log.d(TAG, "  ${variant.name}.${variant.schemaName}: ${disc.toHexString()} [${disc.joinToString(",") { (it.toInt() and 0xFF).toString() }}]")
        }
    }
    
    /**
     * Try to find the correct instruction variant by testing each one.
     * Call this before createSchema to iterate through variants.
     */
    fun nextVariant(): Boolean {
        val variants = InstructionVariant.values()
        val currentIndex = variants.indexOf(instructionVariant)
        return if (currentIndex < variants.size - 1) {
            instructionVariant = variants[currentIndex + 1]
            cachedVariant = null // Force recompute
            Log.d(TAG, "Switching to variant: ${instructionVariant.name} (${instructionVariant.schemaName})")
            true
        } else {
            Log.d(TAG, "No more variants to try")
            false
        }
    }
    
    /**
     * Reset to the first variant
     */
    fun resetVariant() {
        instructionVariant = InstructionVariant.values().first()
        cachedVariant = null
    }

    // ── PDA seeds ─────────────────────────────────────────────────────────────

    /** Derives the Schema PDA: seeds = ["schema", authority, name] */
    suspend fun deriveSchemaAddress(authority: PublicKey, name: String): PublicKey {
        val seeds = listOf(
            "schema".toByteArray(StandardCharsets.UTF_8),
            authority.toByteArray(),
            name.toByteArray(StandardCharsets.UTF_8)
        )
        return PublicKey.findProgramAddress(seeds, PROGRAM_ID).address
    }

    /**
     * Derives the Attestation PDA.
     * seeds = ["attestation", schema, attester, recipient, nonce]
     */
    suspend fun deriveAttestationAddress(
        schema: PublicKey,
        attester: PublicKey,
        recipient: PublicKey,
        nonce: String = ""
    ): PublicKey {
        val seeds = mutableListOf(
            "attestation".toByteArray(StandardCharsets.UTF_8),
            schema.toByteArray(),
            attester.toByteArray(),
            recipient.toByteArray()
        )
        if (nonce.isNotBlank()) seeds += nonce.toByteArray(StandardCharsets.UTF_8)
        return PublicKey.findProgramAddress(seeds, PROGRAM_ID).address
    }

    // ── Instruction builders ──────────────────────────────────────────────────

    /**
     * Creates a `createSchema` instruction.
     *
     * Accounts (order matches SAS IDL):
     *   0. schema       [writable] — PDA
     *   1. authority    [writable, signer] — Schema authority
     *   2. payer        [writable, signer] — Rent payer (can be same as authority)
     *   3. systemProgram [read-only]
     */
    suspend fun createSchema(
        authority: PublicKey,
        feePayer: PublicKey,
        name: String,
        description: String,
        fieldNames: List<String>,
        isRevocable: Boolean
    ): TransactionInstruction {
        ensureDiscriminators()
        
        val schemaPda = deriveSchemaAddress(authority, name)

        Log.d(TAG, "Creating schema instruction:")
        Log.d(TAG, "  authority: ${authority.toBase58()}")
        Log.d(TAG, "  feePayer: ${feePayer.toBase58()}")
        Log.d(TAG, "  schemaPda: ${schemaPda.toBase58()}")
        Log.d(TAG, "  name: '$name' (${name.length} chars)")
        Log.d(TAG, "  description: '$description' (${description.length} chars)")
        Log.d(TAG, "  fieldNames: $fieldNames (${fieldNames.size} fields)")
        Log.d(TAG, "  isRevocable: $isRevocable")
        Log.d(TAG, "  programId: ${PROGRAM_ID.toBase58()}")

        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val descBytes = description.toByteArray(StandardCharsets.UTF_8)
        val fieldNamesBytes = fieldNames.map { it.toByteArray(StandardCharsets.UTF_8) }

        // Build instruction data using ByteArrayOutputStream for reliable concatenation
        val baos = ByteArrayOutputStream()
        
        // 1. Discriminator (8 bytes)
        baos.write(discCreateSchema)
        
        // 2. Name string (4-byte length + UTF-8 bytes)
        baos.write(encodeU32(nameBytes.size))
        baos.write(nameBytes)
        
        // 3. Description string (4-byte length + UTF-8 bytes)
        baos.write(encodeU32(descBytes.size))
        baos.write(descBytes)
        
        // 4. Field names Vec<String> (4-byte count + each string)
        baos.write(encodeU32(fieldNamesBytes.size))
        for (fb in fieldNamesBytes) {
            baos.write(encodeU32(fb.size))
            baos.write(fb)
        }
        
        // 5. Is revocable (1 byte bool)
        baos.write(if (isRevocable) 1 else 0)
        
        val data = baos.toByteArray()
        
        Log.d(TAG, "Instruction data length: ${data.size} bytes")
        Log.d(TAG, "First 8 bytes (discriminator): ${data.sliceArray(0 until 8).toHexString()}")
        Log.d(TAG, "Full instruction data hex: ${data.toHexString()}")

        // Build accounts list - include Rent sysvar if configured
        val accountsList = mutableListOf(
            AccountMeta(schemaPda,         isSigner = false, isWritable = true),
            AccountMeta(authority,         isSigner = true,  isWritable = true)
        )
        
        // Add feePayer separately if different from authority
        if (authority.toBase58() != feePayer.toBase58()) {
            accountsList.add(AccountMeta(feePayer, isSigner = true, isWritable = true))
        }
        
        // Add system program
        accountsList.add(AccountMeta(SYSTEM_PROGRAM_ID, isSigner = false, isWritable = false))
        
        // Optionally add Rent sysvar (some Anchor programs require it)
        if (includeRentSysvar) {
            accountsList.add(AccountMeta(RENT_SYSVAR, isSigner = false, isWritable = false))
        }
        
        val accounts = accountsList.toList()
        
        Log.d(TAG, "Accounts (${accounts.size}):")
        accounts.forEachIndexed { i, acc ->
            Log.d(TAG, "  [$i] ${acc.publicKey.toBase58()} signer=${acc.isSigner} writable=${acc.isWritable}")
        }
        
        return TransactionInstruction(programId = PROGRAM_ID, keys = accounts, data = data)
    }

    /**
     * Creates an `attest` instruction.
     *
     * Accounts:
     *   0. attestation  [writable] — PDA
     *   1. schema       [read-only] — Schema PDA (must exist!)
     *   2. attester     [writable, signer]
     *   3. recipient    [read-only]
     *   4. payer        [writable, signer]
     *   5. systemProgram
     */
    suspend fun createAttestation(
        attester: PublicKey,
        feePayer: PublicKey,
        schemaPda: PublicKey,
        recipient: PublicKey,
        attestationData: ByteArray,
        expireAt: Long = 0L,
        nonce: String = ""
    ): TransactionInstruction {
        ensureDiscriminators()
        
        val attestationPda = deriveAttestationAddress(schemaPda, attester, recipient, nonce)

        Log.d(TAG, "Creating attestation instruction:")
        Log.d(TAG, "  attester: ${attester.toBase58()}")
        Log.d(TAG, "  schemaPda: ${schemaPda.toBase58()}")
        Log.d(TAG, "  recipient: ${recipient.toBase58()}")
        Log.d(TAG, "  attestationPda: ${attestationPda.toBase58()}")
        Log.d(TAG, "  attestationData length: ${attestationData.size}")
        Log.d(TAG, "  expireAt: $expireAt")
        Log.d(TAG, "  nonce: '$nonce'")

        val nonceBytes = nonce.toByteArray(StandardCharsets.UTF_8)
        
        // Build instruction data using ByteArrayOutputStream
        val baos = ByteArrayOutputStream()
        
        // 1. Discriminator (8 bytes)
        baos.write(discAttest)
        
        // 2. Attestation data Vec<u8> (4-byte length + bytes)
        baos.write(encodeU32(attestationData.size))
        baos.write(attestationData)
        
        // 3. Expire at (i64, 8 bytes)
        baos.write(encodeI64(expireAt))
        
        // 4. Nonce string (4-byte length + UTF-8 bytes)
        baos.write(encodeU32(nonceBytes.size))
        baos.write(nonceBytes)
        
        val data = baos.toByteArray()

        Log.d(TAG, "Attestation instruction data length: ${data.size}")
        Log.d(TAG, "First 8 bytes (discriminator): ${data.sliceArray(0 until 8).toHexString()}")

        val accounts = listOf(
            AccountMeta(attestationPda,    isSigner = false, isWritable = true),
            AccountMeta(schemaPda,         isSigner = false, isWritable = false),
            AccountMeta(attester,          isSigner = true,  isWritable = true),
            AccountMeta(recipient,         isSigner = false, isWritable = false),
            AccountMeta(feePayer,          isSigner = true,  isWritable = true),
            AccountMeta(SYSTEM_PROGRAM_ID, isSigner = false, isWritable = false)
        )
        
        return TransactionInstruction(programId = PROGRAM_ID, keys = accounts, data = data)
    }

    /**
     * Creates a `revoke` instruction.
     */
    fun revokeAttestation(
        authority: PublicKey,
        feePayer: PublicKey,
        attestationPda: PublicKey,
        schemaPda: PublicKey
    ): TransactionInstruction {
        ensureDiscriminators()
        
        val accounts = listOf(
            AccountMeta(attestationPda,    isSigner = false, isWritable = true),
            AccountMeta(schemaPda,         isSigner = false, isWritable = false),
            AccountMeta(authority,         isSigner = true,  isWritable = true),
            AccountMeta(feePayer,          isSigner = true,  isWritable = true),
            AccountMeta(SYSTEM_PROGRAM_ID, isSigner = false, isWritable = false)
        )
        return TransactionInstruction(
            programId = PROGRAM_ID,
            keys = accounts,
            data = discRevoke
        )
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
}
