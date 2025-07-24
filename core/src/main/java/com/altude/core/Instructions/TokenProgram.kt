package com.altude.core.Instructions

import com.altude.core.Instructions.Core.TOKEN_PROGRAM_ID
import com.altude.core.Instructions.Core.buildSetAuthorityData
import foundation.metaplex.solana.transactions.AccountMeta
import foundation.metaplex.solana.transactions.TransactionInstruction
import foundation.metaplex.solanapublickeys.PublicKey
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

object TokenProgram {
    fun transferToken(
        source: PublicKey,
        destination: PublicKey,
        owner: PublicKey,
        mint: PublicKey,
        amount: Long,
        decimals: Int,
        signers: List<PublicKey> = emptyList()
    ): TransactionInstruction {
        val buffer = ByteBuffer.allocate(10) //1 byte for instruction, 8 for amount, 1 for decimals
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(12) // Instruction: Transfer
        buffer.putLong(amount.toLong()) // Amount as u64 LE
        buffer.put(amount.toByte()) // Amount as u64 LE
        //return buffer.array()
//        val data = byteArrayOf(
//            12, // transferChecked instruction index
//            amount.toByte(), // amount in lamports
//            decimals.toByte()
//        )

        val accounts = mutableListOf(
            AccountMeta(source, isSigner = false, isWritable = true),
            AccountMeta(mint, isSigner = false, isWritable = true),
            AccountMeta(destination, isSigner = false, isWritable = true),
            AccountMeta(owner, isSigner = signers.isEmpty(), isWritable = false),
        )
        // Add any additional signers as readonly + signer
        accounts += signers.map { pubKey ->
            AccountMeta(pubKey, isSigner = true, isWritable = false)
        }
        return TransactionInstruction(
            programId = PublicKey(TOKEN_PROGRAM_ID),
            keys = accounts,
            data = buffer.array()
        )
    }

    fun transfer(
        source: PublicKey,
        destination: PublicKey,
        owner: PublicKey,
        amount: ULong
    ): TransactionInstruction {
        val keys = listOf(
            AccountMeta(source, isSigner = false, isWritable = true),
            AccountMeta(destination, isSigner = false, isWritable = true),
            AccountMeta(owner, isSigner = true, isWritable = false),
        )

        val data = byteArrayOf(3) + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(amount.toLong()).array()
        return TransactionInstruction(
            keys = keys,
            programId = PublicKey(TOKEN_PROGRAM_ID),
            data = data
        )
    }

    fun closeAtaAccount(
        ata: PublicKey,
        destination: String,
        authority: PublicKey
    ): TransactionInstruction {

        return TransactionInstruction(
            programId = PublicKey(TOKEN_PROGRAM_ID),
            keys = listOf(
                AccountMeta(ata, isSigner = false, isWritable = true),
                AccountMeta(PublicKey(destination), isSigner = false, isWritable = true),
                AccountMeta(authority, isSigner = true, isWritable = false)
            ),
            data = byteArrayOf(9) // No data needed for ATA creation
        )
    }

    fun setAuthority(
        ata: PublicKey,
        currentAuthority: PublicKey,
        newOwner: PublicKey
    ): TransactionInstruction {
        val authority = currentAuthority
        val newOwnerKey = newOwner

        return TransactionInstruction(
            programId = PublicKey(TOKEN_PROGRAM_ID),
            keys = listOf(
                AccountMeta(ata, isSigner = false, isWritable = true), // account whose authority is changing
                AccountMeta(authority, isSigner = true, isWritable = false) // current authority
            ),
            data = buildSetAuthorityData(
                authorityType = 3, // 2 = closeAccount
                newAuthority = newOwnerKey
            )
        )
    }
}