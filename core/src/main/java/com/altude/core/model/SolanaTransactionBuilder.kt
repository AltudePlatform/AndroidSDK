package com.altude.core.model

import com.metaplex.signer.Signer
import foundation.metaplex.solana.transactions.Blockhash
import foundation.metaplex.solana.transactions.SolanaTransaction
import foundation.metaplex.solana.transactions.Transaction
import foundation.metaplex.solana.transactions.TransactionBuilder
import foundation.metaplex.solana.transactions.TransactionInstruction
import foundation.metaplex.solanapublickeys.PublicKey

class SolanaTransactionBuilder : TransactionBuilder {
    private val transaction: SolanaTransaction = SolanaTransaction()
    override fun addInstruction(transactionInstruction: TransactionInstruction): TransactionBuilder {
        transaction.add(transactionInstruction)
        return this
    }

    override fun setRecentBlockHash(recentBlockHash: Blockhash): TransactionBuilder {
        transaction.setRecentBlockHash(recentBlockHash)
        return this
    }

    override suspend fun setSigners(signers: List<Signer>): TransactionBuilder {
        transaction.sign(signers)
        return this
    }

    override suspend fun build(): Transaction {
        return transaction
    }

    fun setFeePayer(publicKey: PublicKey): TransactionBuilder {
        transaction.feePayer = publicKey
        return this
    }
}