package com.altude.core.Programs

import android.os.Build
import androidx.annotation.RequiresApi
import com.altude.core.data.SwapInstruction
import com.altude.core.data.SwapResponse
import foundation.metaplex.solana.transactions.AccountMeta
import foundation.metaplex.solana.transactions.TransactionInstruction
import foundation.metaplex.solanapublickeys.PublicKey
import java.util.Base64

object SwapHelper {
    suspend fun buildSwapTransaction(
        swapResponse: SwapResponse
    ): List<TransactionInstruction> {
        val instructions = mutableListOf<TransactionInstruction>()

        // Helper function to convert JSON instruction objects into TransactionInstruction
        @RequiresApi(Build.VERSION_CODES.O)
        fun parseInstruction(obj: SwapInstruction): TransactionInstruction {
            val programId = PublicKey(obj.programId)
            val accounts = obj.accounts.map {
                AccountMeta(PublicKey(it.pubkey), it.isSigner, it.isWritable)
            }
            val data = Base64.getDecoder().decode(obj.data)
            return TransactionInstruction(programId, accounts, data)
        }

        // 1️⃣ Add compute budget instructions
        swapResponse.computeBudgetInstructions?.forEach {
            instructions.add(parseInstruction(it))
        }

        // 3️⃣ Add swap instruction
        swapResponse.swapInstruction?.let {
            instructions.add(parseInstruction(it))
        }

        // 4️⃣ Add cleanup instruction / closing accounts
//        swapResponse.cleanupInstruction?.let {
//            instructions.add(parseInstruction(it))
//        }

        // 5️⃣ Add any "other" instructions
        swapResponse.otherInstructions?.forEach {
            instructions.add(parseInstruction(it))
        }


        return instructions
    }
    suspend fun buildSwapSetupTransaction(
        swapResponse: SwapResponse
    ): List<TransactionInstruction> {
        val instructions = mutableListOf<TransactionInstruction>()

        // Helper function to convert JSON instruction objects into TransactionInstruction
        @RequiresApi(Build.VERSION_CODES.O)
        fun parseInstruction(obj: SwapInstruction): TransactionInstruction {
            val programId = PublicKey(obj.programId)
            val accounts = obj.accounts.map {
                AccountMeta(PublicKey(it.pubkey), it.isSigner, it.isWritable)
            }
            val data = Base64.getDecoder().decode(obj.data)
            return TransactionInstruction(programId, accounts, data)
        }

        // 2️⃣ Add setup instructions
        swapResponse.setupInstructions?.forEach {
            instructions.add(parseInstruction(it))
        }


        return instructions
    }


}