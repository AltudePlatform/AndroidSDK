package com.altude.core.Programs

import com.altude.core.Programs.Utility.TOKEN_PROGRAM_ID
import com.altude.core.Programs.Utility.buildSetAuthorityData
import foundation.metaplex.solana.transactions.AccountMeta
import foundation.metaplex.solana.transactions.SolanaTransactionBuilder
import foundation.metaplex.solana.transactions.TransactionInstruction
import foundation.metaplex.solanapublickeys.PublicKey
import org.bitcoinj.core.VersionMessage
import java.nio.ByteBuffer
import java.nio.ByteOrder

object TokenProgram {
    fun transferToken(
        source: PublicKey,
        destination: PublicKey,
        owner: PublicKey,
        mint: PublicKey,
        amount: Long,
        decimals: UInt,
        signers: List<PublicKey> = emptyList()
    ): TransactionInstruction {
        val buffer = ByteBuffer.allocate(10) //1 byte for instruction, 8 for amount, 1 for decimals
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(12) // Instruction: Transfer
        buffer.putLong(amount.toLong()) // Amount as u64 LE
        buffer.put(decimals.toByte()) // Amount as u64 LE

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
            programId = TOKEN_PROGRAM_ID,
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
            programId = TOKEN_PROGRAM_ID,
            data = data
        )
    }

    fun initializeMint(
        mint: PublicKey,
        decimals: UInt,
        mintAuthority: PublicKey,
        freezeAuthority: PublicKey? = null
    ): TransactionInstruction {
        val keys = listOf(
            AccountMeta(mint, isSigner = false, isWritable = true),           // Writable mint account
            AccountMeta(Utility.SYSVAR_RENT_PUBKEY, isSigner = false, isWritable = false) // Rent sysvar
        )

        val freezeAuthorityOption = if (freezeAuthority != null) 1 else 0
        val freezeAuthKey = freezeAuthority ?: PublicKey(ByteArray(32))

        // SPL Token Program InitializeMint layout:
        // u8: instruction index (0)
        // u8: decimals
        // [32]: mintAuthority
        // u8: freezeAuthorityOption
        // [32]: freezeAuthority
        val buffer = ByteBuffer.allocate(1 + 1 + 32 + 1 + 32)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0) // instruction index 0 = InitializeMint
        buffer.put(decimals.toByte())
        buffer.put(mintAuthority.toByteArray())
        buffer.put(freezeAuthorityOption.toByte())
        buffer.put(freezeAuthKey.toByteArray())

        val data = buffer.array()

        return TransactionInstruction(
            TOKEN_PROGRAM_ID,
            keys,
            data
        )
    }

    fun mintTo(
        mint: PublicKey,
        destination: PublicKey,
        amount: ULong,
        mintAuthority: PublicKey,
        signers: List<PublicKey> = emptyList()
    ): TransactionInstruction {
        val keys = mutableListOf(
            AccountMeta(mint, false, true),       // Writable mint
            AccountMeta(destination, false, true) // Writable destination token account
        )

        // Add the mintAuthority + any multisig signers
        keys.add(AccountMeta(mintAuthority, true, false))
        signers.forEach { signer ->
            keys.add(AccountMeta(signer, true, false))
        }

        // Encode MintTo data
        // u8: instruction index (7 for MintTo)
        // u64: amount (little endian)
        val buffer = ByteBuffer.allocate(1 + 8)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(7.toByte())        // MintTo instruction index
        buffer.putLong(amount.toLong())        // Amount to mint

        val data = buffer.array()

        return TransactionInstruction(
            TOKEN_PROGRAM_ID,
            keys,
            data
        )
    }
    fun closeAtaAccount(
        ata: PublicKey,
        destination: PublicKey,
        authority: PublicKey
    ): TransactionInstruction {

        return TransactionInstruction(
            programId = TOKEN_PROGRAM_ID,
            keys = listOf(
                AccountMeta(ata, isSigner = false, isWritable = true),
                AccountMeta(destination, isSigner = false, isWritable = true),
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
            programId = TOKEN_PROGRAM_ID,
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