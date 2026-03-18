package com.altude.core.Programs

import com.altude.core.Programs.Utility.SYSTEM_PROGRAM_ID
import foundation.metaplex.solana.transactions.AccountMeta
import foundation.metaplex.solana.transactions.TransactionInstruction
import foundation.metaplex.solanapublickeys.PublicKey
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Solana Attestation Service (SAS) Program instructions.
 *
 * Program ID: 22zoJMtdu5rJOmEMCqaYzDVLEqJa4FcFBGmrnEcUhbCr
 * Reference:  https://github.com/solana-attestation-service
 *
 * SAS uses an Anchor 8-byte discriminator derived from the instruction name.
 * Discriminators are precomputed sha256("global:<instruction_name>")[0..8].
 */
object AttestationProgram {

    /** On-chain program address for the Solana Attestation Service. */
    val PROGRAM_ID = PublicKey("22zoJMtdu5rJOmEMCqaYzDVLEqJa4FcFBGmrnEcUhbCr")

    // ── Anchor discriminators (sha256("global:<name>")[0..8] little-endian) ───
    // Pre-computed from the SAS IDL.
    private val DISC_CREATE_SCHEMA      = byteArrayOf(102.toByte(),  82.toByte(), 205.toByte(), 109.toByte(),  56.toByte(), 206.toByte(), 253.toByte(), 189.toByte())
    private val DISC_CREATE_ATTESTATION = byteArrayOf( 71.toByte(), 149.toByte(),  77.toByte(), 206.toByte(), 166.toByte(),  86.toByte(), 252.toByte(), 185.toByte())
    private val DISC_REVOKE_ATTESTATION = byteArrayOf(243.toByte(), 175.toByte(), 179.toByte(),  26.toByte(),  67.toByte(), 213.toByte(), 154.toByte(),  97.toByte())

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
     *   0. schema       [writable] — PDA, derived by [deriveSchemaAddress]
     *   1. authority    [writable, signer] — Schema authority
     *   2. feePayer     [writable, signer] — Rent / fee payer
     *   3. systemProgram [read-only] — 11111…
     */
    suspend fun createSchema(
        authority: PublicKey,
        feePayer: PublicKey,
        name: String,
        description: String,
        fieldNames: List<String>,
        isRevocable: Boolean
    ): TransactionInstruction {
        val schemaPda = deriveSchemaAddress(authority, name)

        val nameBytes        = name.toByteArray(StandardCharsets.UTF_8)
        val descBytes        = description.toByteArray(StandardCharsets.UTF_8)
        val fieldNamesBytes  = fieldNames.map { it.toByteArray(StandardCharsets.UTF_8) }

        // Layout: discriminator(8) + name_len(4) + name + desc_len(4) + desc
        //         + field_count(4) + [field_len(4) + field]* + is_revocable(1)
        val fieldSection = fieldNamesBytes.fold(ByteArray(4).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(fieldNamesBytes.size)
        }) { acc, fb ->
            val lenBuf = ByteArray(4).also { b -> ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(fb.size) }
            acc + lenBuf + fb
        }

        val data = DISC_CREATE_SCHEMA +
            encode4(nameBytes.size) + nameBytes +
            encode4(descBytes.size) + descBytes +
            fieldSection +
            byteArrayOf(if (isRevocable) 1 else 0)

        val accounts = listOf(
            AccountMeta(schemaPda,     isSigner = false, isWritable = true),
            AccountMeta(authority,     isSigner = true,  isWritable = true),
            AccountMeta(feePayer,      isSigner = true,  isWritable = true),
            AccountMeta(SYSTEM_PROGRAM_ID, isSigner = false, isWritable = false)
        )
        return TransactionInstruction(programId = PROGRAM_ID, keys = accounts, data = data)
    }

    /**
     * Creates an `attest` instruction.
     *
     * Accounts:
     *   0. attestation  [writable] — PDA, derived by [deriveAttestationAddress]
     *   1. schema       [read-only] — Schema PDA
     *   2. attester     [writable, signer]
     *   3. recipient    [read-only]
     *   4. feePayer     [writable, signer]
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
        val attestationPda = deriveAttestationAddress(schemaPda, attester, recipient, nonce)

        // Layout: discriminator(8) + data_len(4) + data + expire_at(8) + nonce_len(4) + nonce
        val nonceBytes = nonce.toByteArray(StandardCharsets.UTF_8)
        val data = DISC_CREATE_ATTESTATION +
            encode4(attestationData.size) + attestationData +
            encode8(expireAt) +
            encode4(nonceBytes.size) + nonceBytes

        val accounts = listOf(
            AccountMeta(attestationPda, isSigner = false, isWritable = true),
            AccountMeta(schemaPda,      isSigner = false, isWritable = false),
            AccountMeta(attester,       isSigner = true,  isWritable = true),
            AccountMeta(recipient,      isSigner = false, isWritable = false),
            AccountMeta(feePayer,       isSigner = true,  isWritable = true),
            AccountMeta(SYSTEM_PROGRAM_ID, isSigner = false, isWritable = false)
        )
        return TransactionInstruction(programId = PROGRAM_ID, keys = accounts, data = data)
    }

    /**
     * Creates a `revokeAttestation` instruction.
     *
     * Accounts:
     *   0. attestation  [writable] — The Attestation PDA to revoke
     *   1. attester     [writable, signer]
     *   2. feePayer     [writable, signer]
     *   3. systemProgram
     */
    fun revokeAttestation(
        attester: PublicKey,
        feePayer: PublicKey,
        attestationPda: PublicKey
    ): TransactionInstruction {
        val accounts = listOf(
            AccountMeta(attestationPda, isSigner = false, isWritable = true),
            AccountMeta(attester,       isSigner = true,  isWritable = true),
            AccountMeta(feePayer,       isSigner = true,  isWritable = true),
            AccountMeta(SYSTEM_PROGRAM_ID, isSigner = false, isWritable = false)
        )
        return TransactionInstruction(
            programId = PROGRAM_ID,
            keys = accounts,
            data = DISC_REVOKE_ATTESTATION
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun encode4(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun encode8(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
}

