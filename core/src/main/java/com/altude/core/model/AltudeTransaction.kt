package com.altude.core.model

import com.metaplex.signer.Signer
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.Message
import com.solana.transaction.Transaction
import foundation.metaplex.solana.transactions.TransactionSignature
import foundation.metaplex.solanapublickeys.PublicKey
import org.bouncycastle.util.encoders.Base64
import java.lang.Error

class AltudeTransaction (base64:String)  {
    val txBytes: ByteArray = Base64.decode(base64)
    //val message = Message.from(txBytes)
    var transaction = Transaction.from(txBytes)


    suspend fun partialSign(signers: List<Signer>): AltudeTransaction {
        val messageBytes = transaction.message.serialize()
        val signatureCount = transaction.message.signatureCount.toInt()
        val base64Message = Base64.toBase64String( messageBytes )
        val signaccounts = transaction.message.accounts.take(signatureCount)

        // Copy signatures
        val newSignatures = transaction.signatures.toMutableList()

        signaccounts.forEach { account ->
            val signer = signers.find { it.publicKey.toBase58() == account.base58() }
            val pubkey = SolanaPublicKey.from(signer?.publicKey?.toBase58()!!)

            val signerIndex = transaction.message.accounts.indexOf(pubkey)
            require(signerIndex >= 0) {
                "Signer not found in transaction accounts"
            }
            require(signerIndex < signatureCount) {
                "Account is not a required signer"
            }
            require(transaction.message.accounts[signerIndex].base58() == signer.publicKey.toBase58()){
                "Account is not a required signer"
            }

            val signature = signer.signMessage(messageBytes)
            require(signature.size == Transaction.SIGNATURE_LENGTH_BYTES)

            newSignatures[signerIndex] = signature
        }

        transaction = Transaction(
            signatures = newSignatures,
            message = transaction.message
        )
        return this
    }
//    private fun _addSignature(pubkey: PublicKey, signature: TransactionSignature) {
//        require(signature.count() == 64)
//
//        val index = versioned.signatures.indexOfFirst { sigpair ->
//            pubkey.equals(sigpair.publicKey)
//        }
//        if (index < 0) {
//            throw Error("unknown signer: $pubkey")
//        }
//
//        versioned.signatures[index].signature = signature
//    }
    fun  serialize(): String {
        return Base64.toBase64String(transaction.serialize())
    }
}

