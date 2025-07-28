package com.altude.core

import android.util.Base64
import com.altude.core.data.CreateAccountOption
import com.altude.core.data.SendOptions
import com.altude.core.Program.AssociatedTokenAccountProgram
import com.altude.core.Program.AssociatedTokenAccountProgram.deriveAtaAddress
import com.altude.core.Program.Utility
import com.altude.core.Program.TokenProgram
import com.altude.core.config.SdkConfig
import com.altude.core.data.CloseAccountOption
import com.altude.core.model.EmptySignature
import com.altude.core.model.HotSigner
import com.altude.core.model.SolanaKeypair
import com.altude.core.model.AltudeTransactionBuilder
import foundation.metaplex.rpc.Commitment
import foundation.metaplex.rpc.RPC
import foundation.metaplex.rpc.RpcGetLatestBlockhashConfiguration
import foundation.metaplex.solana.transactions.SerializeConfig
import foundation.metaplex.solana.transactions.TransactionInstruction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import foundation.metaplex.solanapublickeys.PublicKey
import java.lang.Error
import kotlin.collections.listOf


object TransactionManager {

    private val quickNodeUrl = "https://multi-ultra-frost.solana-devnet.quiknode.pro/417151c175bae42230bf09c1f87acda90dc21968/"
    private val rpc = RPC(quickNodeUrl)
    val feePayerPubKey = PublicKey("BjLvdmqDjnyFsewJkzqPSfpZThE8dGPqCAZzVbJtQFSr")
//    val privateKeyBytes = byteArrayOf(
//        235.toByte(), 144.toByte(), 12, 215.toByte(), 112, 178.toByte(), 249.toByte(), 227.toByte(), 180.toByte(), 112, 121, 214.toByte(), 13, 190.toByte(), 158.toByte(), 91,
//        208.toByte(), 118, 253.toByte(), 192.toByte(), 48, 6, 252.toByte(), 37, 111, 169.toByte(), 209.toByte(), 238.toByte(), 174.toByte(), 78, 210.toByte(), 184.toByte(),
//        9, 37, 75, 1, 98, 80, 44, 48, 119, 25, 193.toByte(), 156.toByte(), 161.toByte(), 185.toByte(), 250.toByte(), 119,
//        160.toByte(), 54, 62, 93, 4, 130.toByte(), 200.toByte(), 226.toByte(), 100, 255.toByte(), 215.toByte(), 170.toByte(), 26, 226.toByte(), 213.toByte(), 28
//    )
    //val feePayer = KeyPair(feePayerPubKeypair.toByteArray(), privateKeyBytes)
    suspend fun TransferToken(option: SendOptions): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val pubKeyMint = PublicKey(option.token)
            val pubKeyDestination = PublicKey(option.toAddress)

            val sourceAta = deriveAtaAddress(SdkConfig.ownerKeyPair.publicKey, pubKeyMint)
            val destinationAta = deriveAtaAddress(pubKeyDestination, pubKeyMint)

            if (!Utility.ataExists(sourceAta.toBase58()))
                throw Error("Owner associated token account does not exist.")

            val destinationCreateAta: TransactionInstruction? =
                if (!Utility.ataExists(destinationAta.toBase58())) {
                    AssociatedTokenAccountProgram.createAssociatedTokenAccount(
                        ata = destinationAta,
                        feePayer = feePayerPubKey,
                        owner = pubKeyDestination, // ✅ Corrected
                        mint = pubKeyMint
                    )
                } else null

            val decimals = Utility.getTokenDecimals(option.token)
            val rawAmount = Utility.getRawQuantity(option.amount, decimals)

            val transferInstruction = TokenProgram.transferToken(
                source = sourceAta,
                destination = destinationAta,
                owner = SdkConfig.ownerKeyPair.publicKey,
                mint = pubKeyMint,
                amount = rawAmount,
                decimals = decimals.toUInt(),
                signers = listOf(SdkConfig.ownerKeyPair.publicKey, feePayerPubKey)
            )

            val blockhashInfo = rpc.getLatestBlockhash(
                RpcGetLatestBlockhashConfiguration(commitment = Commitment.finalized)
            )

            val recentBlockhash = blockhashInfo.blockhash
            val authorizedSignature = HotSigner(SolanaKeypair(SdkConfig.ownerKeyPair.publicKey, SdkConfig.ownerKeyPair.secretKey))

            val builder = AltudeTransactionBuilder()
                .setFeePayer(feePayerPubKey)
                .setRecentBlockHash(recentBlockhash)
            destinationCreateAta?.let { builder.addInstruction(it) }
            builder.addInstruction(transferInstruction)
            builder.setSigners(listOf(authorizedSignature))

            val build = builder.build()
            val serialized = Base64.encodeToString(
                build.serialize(SerializeConfig(requireAllSignatures = false)),
                Base64.NO_WRAP
            )

            Result.success(serialized)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }


    suspend fun createAccount(option: CreateAccountOption): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val ownerKey = SdkConfig.ownerKeyPair.publicKey//PublicKey(option.owner)

            val TxInstructions = mutableListOf<TransactionInstruction>()
            option.tokens.forEach { token ->
                val mintKey = PublicKey(token)
                val ata = deriveAtaAddress(ownerKey, mintKey)
                if(!Utility.ataExists(ata.toBase58())){
                    val ataInstruction = AssociatedTokenAccountProgram.createAssociatedTokenAccount(
                        ata = ata,
                        feePayer = feePayerPubKey, // only pubkey
                        owner = ownerKey,
                        mint = mintKey
                    )
                    TxInstructions.add(ataInstruction)
                    val authInstruction = TokenProgram.setAuthority(
                        ata = ata,
                        currentAuthority = ownerKey,
                        newOwner = feePayerPubKey
                    )
                    TxInstructions.add(authInstruction)
                }
            }
            if(TxInstructions.count() == 0){
                if(option.tokens.count() > 1)
                    throw Error("All Token accounts already created")
                throw Error("Token account already created")
            }
            val authorizedSigner = HotSigner(SolanaKeypair(ownerKey, SdkConfig.ownerKeyPair.secretKey))

            val payerSigner = EmptySignature(feePayerPubKey)
            val blockhashInfo = rpc.getLatestBlockhash(
                RpcGetLatestBlockhashConfiguration(commitment = Commitment.finalized)
            )

            val tx = AltudeTransactionBuilder().addRangeInstruction(TxInstructions)
                .setFeePayer(feePayerPubKey)
                .setRecentBlockHash(blockhashInfo.blockhash)
                .setSigners(listOf(authorizedSigner))
                .build()

            val serialized = Base64.encodeToString(
                tx.serialize(SerializeConfig(requireAllSignatures = false)),
                Base64.NO_WRAP
            )
            Result.success(serialized)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    suspend fun closeAccount(
        option: CloseAccountOption
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val ownerKey = SdkConfig.ownerKeyPair

            val TxInstructions = mutableListOf<TransactionInstruction>()
            var authorized: PublicKey
            var isOwnerRequiredSignature = false
            option.tokens.forEach { token ->
                val mintKey = PublicKey(token)
                val ata = deriveAtaAddress(ownerKey.publicKey, mintKey)
                val ataInfo = Utility.getAccountInfo(ata.toBase58())
                if(ataInfo != null){
                    if(ataInfo.data?.parsed?.info?.closeAuthority == feePayerPubKey.toBase58())
                        authorized = feePayerPubKey
                    else {
                        authorized = SdkConfig.ownerKeyPair.publicKey
                        isOwnerRequiredSignature = true
                    }
                        val instruction = TokenProgram.closeAtaAccount(
                            ata = ata,
                            destination = feePayerPubKey,
                            authority = authorized
                        )
                        TxInstructions.add(instruction)

                }
            }
            if(TxInstructions.count() == 0){
                if(option.tokens.count() > 1)
                    throw Error("All Token accounts already closed")
                throw Error("Token account already closed")
            }

            val blockhashInfo = rpc.getLatestBlockhash(
                RpcGetLatestBlockhashConfiguration(commitment = Commitment.finalized)
            )

            val recentBlockhash = blockhashInfo.blockhash
            val tx = AltudeTransactionBuilder()
                .setFeePayer(feePayerPubKey)
                .addRangeInstruction(TxInstructions)
                .setRecentBlockHash(blockhashInfo.blockhash)
                .build()
            if(isOwnerRequiredSignature)
                tx.sign(HotSigner(SdkConfig.ownerKeyPair))
            //val sign = Core.SignTransaction(privateKeyBytes,message)
            val serialized = Base64.encodeToString(tx.serialize(SerializeConfig(requireAllSignatures = false)), Base64.NO_WRAP)
            Result.success(serialized)
        } catch (e: Exception) {
            Result.failure(e)

        }
    }

}

