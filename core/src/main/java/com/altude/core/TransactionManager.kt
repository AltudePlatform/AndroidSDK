package com.altude.core

import android.util.Base64
import com.altude.core.data.CreateAccountOption
import com.altude.core.data.SendOption
import com.altude.core.Programs.AssociatedTokenAccountProgram
import com.altude.core.Programs.AssociatedTokenAccountProgram.deriveAtaAddress
import com.altude.core.Programs.Utility
import com.altude.core.Programs.TokenProgram
import com.altude.core.data.CloseAccountOption
import com.altude.core.data.TransferOptions
import com.altude.core.helper.Mnemonic
import com.altude.core.model.EmptySignature
import com.altude.core.model.HotSigner
import com.altude.core.model.SolanaKeypair
import com.altude.core.model.AltudeTransactionBuilder
import com.altude.core.model.KeyPair
import com.altude.core.service.StorageService
import com.metaplex.signer.Signer
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

    suspend fun transferToken(option: SendOption): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val pubKeyMint = PublicKey(option.token)
            val pubKeyDestination = PublicKey(option.toAddress)
            val defaultWallet = getKeyPair(option.account)
            val sourceAta = deriveAtaAddress(defaultWallet.publicKey, pubKeyMint)
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
                owner = defaultWallet.publicKey,
                mint = pubKeyMint,
                amount = rawAmount,
                decimals = decimals.toUInt(),
                //signers = listOf(defaultWallet.publicKey, feePayerPubKey)
            )

            val blockhashInfo = rpc.getLatestBlockhash(
                RpcGetLatestBlockhashConfiguration(commitment = Commitment.finalized)
            )

            val recentBlockhash = blockhashInfo.blockhash
            val authorizedSignature = HotSigner(SolanaKeypair(defaultWallet.publicKey, defaultWallet.secretKey))

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

    suspend fun batchTransferToken(options: List<TransferOptions>): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val transferInstructions = mutableListOf<TransactionInstruction>();
            val authorizedSignatures = mutableListOf<Signer>()
            options.forEach { option ->
                val pubKeyMint = PublicKey(option.token)
                val pubKeyDestination = PublicKey(option.toAddress)
                val defaultWallet =  getKeyPair(option.account)
                val sourceAta = deriveAtaAddress(defaultWallet.publicKey, pubKeyMint)
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
                if (destinationCreateAta != null)
                    transferInstructions.add(destinationCreateAta)
                val decimals = Utility.getTokenDecimals(option.token)
                val rawAmount = Utility.getRawQuantity(option.amount, decimals)

                val transferInstruction = TokenProgram.transferToken(
                    source = sourceAta,
                    destination = destinationAta,
                    owner = defaultWallet.publicKey,
                    mint = pubKeyMint,
                    amount = rawAmount,
                    decimals = decimals.toUInt(),
                    //signers = listOf(defaultWallet.publicKey, feePayerPubKey)
                )
                transferInstructions.add(transferInstruction)
                if (authorizedSignatures.find { it.publicKey == defaultWallet.publicKey } == null )
                    authorizedSignatures.add(HotSigner(SolanaKeypair(defaultWallet.publicKey, defaultWallet.secretKey)))
            }

            val blockhashInfo = rpc.getLatestBlockhash(
                RpcGetLatestBlockhashConfiguration(commitment = Commitment.finalized)
            )

            val recentBlockhash = blockhashInfo.blockhash


            val builder = AltudeTransactionBuilder()
                .setFeePayer(feePayerPubKey)
                .setRecentBlockHash(recentBlockhash)
            //destinationCreateAta?.let { builder.addInstruction(it) }
            builder.addRangeInstruction(transferInstructions)
            builder.setSigners(authorizedSignatures)

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
            val defaultWallet =  getKeyPair(option.account)
            val ownerKey = defaultWallet.publicKey//PublicKey(option.owner)

            val txInstructions = mutableListOf<TransactionInstruction>()
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
                    txInstructions.add(ataInstruction)
                    val authInstruction = TokenProgram.setAuthority(
                        ata = ata,
                        currentAuthority = ownerKey,
                        newOwner = feePayerPubKey
                    )
                    txInstructions.add(authInstruction)
                }
            }
            if(txInstructions.count() == 0){
                if(option.tokens.count() > 1)
                    throw Error("All Token accounts already created")
                throw Error("Token account already created")
            }
            val authorizedSigner = HotSigner(SolanaKeypair(ownerKey, defaultWallet.secretKey))

            val payerSigner = EmptySignature(feePayerPubKey)
            val blockhashInfo = rpc.getLatestBlockhash(
                RpcGetLatestBlockhashConfiguration(commitment = Commitment.finalized)
            )

            val tx = AltudeTransactionBuilder().addRangeInstruction(txInstructions)
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
            val defaultWallet =  getKeyPair(option.account)
            val ownerKey = defaultWallet

            val txInstructions = mutableListOf<TransactionInstruction>()
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
                        authorized = defaultWallet.publicKey
                        isOwnerRequiredSignature = true
                    }
                    val instruction = TokenProgram.closeAtaAccount(
                        ata = ata,
                        destination = feePayerPubKey,
                        authority = authorized
                    )
                    txInstructions.add(instruction)

                }
            }
            if(txInstructions.count() == 0){
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
                .addRangeInstruction(txInstructions)
                .setRecentBlockHash(blockhashInfo.blockhash)
                .build()
            if(isOwnerRequiredSignature)
                tx.sign(HotSigner(defaultWallet))
            //val sign = Core.SignTransaction(privateKeyBytes,message)
            val serialized = Base64.encodeToString(tx.serialize(SerializeConfig(requireAllSignatures = false)), Base64.NO_WRAP)
            Result.success(serialized)
        } catch (e: Exception) {
            Result.failure(e)

        }
    }
    suspend fun getKeyPair(account:String = ""): SolanaKeypair {
        val seedData = StorageService.getDecryptedSeed(account)
        if(seedData != null){
            if(seedData.type == "mnemonic") return Mnemonic(seedData.mnemonic).getKeyPair()
            return  if(seedData.privateKey!= null) KeyPair.solanaKeyPairFromPrivateKey(seedData.privateKey)
            else throw Error("No seed found in storage")
        }else throw Error("Please set seed first")
    }
}

