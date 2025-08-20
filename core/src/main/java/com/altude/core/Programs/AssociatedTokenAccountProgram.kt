package com.altude.core.Programs

import com.altude.core.Programs.Utility.ATA_PROGRAM_ID
import com.altude.core.Programs.Utility.SYSTEM_PROGRAM_ID
import com.altude.core.Programs.Utility.SYSVAR_RENT_PUBKEY
import com.altude.core.Programs.Utility.TOKEN_PROGRAM_ID
import foundation.metaplex.solana.transactions.AccountMeta
import foundation.metaplex.solana.transactions.TransactionInstruction
import foundation.metaplex.solanapublickeys.PublicKey

object AssociatedTokenAccountProgram {


    suspend fun createAssociatedTokenAccount(
        ata: PublicKey,
        feePayer: PublicKey,
        owner: PublicKey,
        mint: PublicKey,
    ): TransactionInstruction {

        val accounts = mutableListOf(
            AccountMeta(feePayer, isSigner = true, isWritable = true),
            AccountMeta(ata, isSigner = false, isWritable = true),
            AccountMeta(owner, isSigner = false, isWritable = false),
            AccountMeta(mint, isSigner = false, isWritable = false),
            AccountMeta(SYSTEM_PROGRAM_ID, isSigner = false, isWritable = false),
            AccountMeta(TOKEN_PROGRAM_ID, isSigner = false, isWritable = false),
            AccountMeta(SYSVAR_RENT_PUBKEY, isSigner = false, isWritable = false),
        )

        return TransactionInstruction(
            programId = ATA_PROGRAM_ID,
            keys = accounts,
            data = byteArrayOf() // No data needed for ATA creation
        )
    }
    suspend fun deriveAtaAddress(owner: PublicKey, mint: PublicKey): PublicKey {
        val seeds = listOf(
            owner.toByteArray(),
            TOKEN_PROGRAM_ID.toByteArray(),
            mint.toByteArray()
        )
        val programid = ATA_PROGRAM_ID
        return PublicKey.findProgramAddress(seeds, programid).address
    }


}