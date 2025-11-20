package com.altude.core.Programs

import android.os.Build
import androidx.annotation.RequiresApi
import com.altude.core.data.JupiterInstruction
import com.altude.core.data.JupiterSwapResponse
import com.altude.core.model.AltudeTransactionBuilder
import com.altude.core.network.QuickNodeRpc
import foundation.metaplex.solana.transactions.AccountMeta
import foundation.metaplex.solana.transactions.Transaction
import foundation.metaplex.solana.transactions.TransactionInstruction
import foundation.metaplex.solanapublickeys.PublicKey
import okio.ByteString.Companion.decodeBase64
import java.util.Base64

object Jupiter {
    suspend fun buildJupiterTransaction(
        jupiterResponse: JupiterSwapResponse
    ): List<TransactionInstruction> {
        val instructions = mutableListOf<TransactionInstruction>()

        // Helper function to convert JSON instruction objects into TransactionInstruction
        @RequiresApi(Build.VERSION_CODES.O)
        fun parseInstruction(obj: JupiterInstruction): TransactionInstruction {
            val programId = PublicKey(obj.programId)
            val accounts = obj.accounts.map {
                AccountMeta(PublicKey(it.pubkey), it.isSigner, it.isWritable)
            }
            val data = Base64.getDecoder().decode(obj.data)
            return TransactionInstruction(programId, accounts, data)
        }

        // 1️⃣ Add compute budget instructions
        jupiterResponse.computeBudgetInstructions?.forEach {
            instructions.add(parseInstruction(it))
        }

        // 2️⃣ Add setup instructions
        jupiterResponse.setupInstructions?.forEach {
            instructions.add(parseInstruction(it))
        }

        // 3️⃣ Add swap instruction
        jupiterResponse.swapInstruction?.let {
            instructions.add(parseInstruction(it))
        }

        // 4️⃣ Add cleanup instruction
        jupiterResponse.cleanupInstruction?.let {
            instructions.add(parseInstruction(it))
        }

        // 5️⃣ Add any "other" instructions
        jupiterResponse.otherInstructions?.forEach {
            instructions.add(parseInstruction(it))
        }


        return instructions
    }
}