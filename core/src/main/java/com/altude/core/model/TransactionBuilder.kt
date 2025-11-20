package com.altude.core.model

import com.metaplex.signer.Signer
import foundation.metaplex.solana.transactions.Blockhash
import foundation.metaplex.solana.transactions.Transaction
import foundation.metaplex.solana.transactions.TransactionInstruction
import foundation.metaplex.solanapublickeys.PublicKey

interface TransactionBuilder {
    fun addInstruction(transactionInstruction: TransactionInstruction): TransactionBuilder

    fun addRangeInstruction(transactionInstruction: List<TransactionInstruction>): TransactionBuilder
    fun setRecentBlockHash(recentBlockHash: Blockhash): TransactionBuilder
    suspend fun setSigners(signers: List<Signer>): TransactionBuilder
    suspend fun build(): Transaction

    // Add this:
    fun setFeePayer(publicKey: PublicKey): TransactionBuilder
    // Add this:
    fun addLookUpTable(lookupTable: MessageAddressTableLookup): TransactionBuilder
    fun addLookUpTables(lookupTables: List<MessageAddressTableLookup>?): TransactionBuilder
}

