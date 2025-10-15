package com.altude.gasstation

import android.util.Base64
import com.altude.core.Programs.AssociatedTokenAccountProgram
import com.altude.core.Programs.TokenProgram
import com.altude.gasstation.helper.Utility
import com.altude.core.config.SdkConfig
import com.altude.gasstation.data.CloseAccountOption
import com.altude.gasstation.data.CreateAccountOption
import com.altude.gasstation.data.ISendOption
import com.altude.gasstation.data.SendOptions
import com.altude.core.helper.Mnemonic
import com.altude.core.model.AltudeTransactionBuilder
import com.altude.core.model.EmptySignature
import com.altude.core.model.HotSigner
import com.altude.gasstation.data.KeyPair
import com.altude.gasstation.data.SolanaKeypair
import com.altude.core.network.QuickNodeRpc
import com.altude.core.service.StorageService
import com.metaplex.signer.Signer
import foundation.metaplex.solana.transactions.SerializeConfig
import foundation.metaplex.solana.transactions.TransactionInstruction
import foundation.metaplex.solanaeddsa.Keypair
import foundation.metaplex.solanapublickeys.PublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.Error

object GaslessManager {

    private val rpc = QuickNodeRpc(SdkConfig.apiConfig.RpcUrl)
    val feePayerPubKey =
        PublicKey(SdkConfig.apiConfig.FeePayer) // PublicKey("Hwdo4thQCFKB3yuohhmmnb1gbUBXySaVJwBnkmRgN8cK") //ALZ8NJcf8JDL7j7iVfoyXM8u3fT3DoBXsnAU6ML7Sb5W BjLvdmqDjnyFsewJkzqPSfpZThE8dGPqCAZzVbJtQFSr

    suspend fun transferToken(option: ISendOption): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val pubKeyMint = PublicKey(option.token)
            val pubKeyDestination = PublicKey(option.toAddress)
            val defaultWallet = getKeyPair(option.account)
            val sourceAta =
                AssociatedTokenAccountProgram.deriveAtaAddress(defaultWallet.publicKey, pubKeyMint)
            val destinationAta =
                AssociatedTokenAccountProgram.deriveAtaAddress(pubKeyDestination, pubKeyMint)
            val sourceAtaBase58 = sourceAta.toBase58()
            if (!Utility.ataExists(sourceAtaBase58))
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
            val rawAmount = com.altude.core.Programs.Utility.getRawQuantity(option.amount, decimals)

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
                commitment = option.commitment.name
            )

            val recentBlockhash = blockhashInfo.blockhash
            val authorizedSignature =
                HotSigner(SolanaKeypair(defaultWallet.publicKey, defaultWallet.secretKey))

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

    suspend fun batchTransferToken(options: List<SendOptions>): Result<String> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val transferInstructions = mutableListOf<TransactionInstruction>();
                val authorizedSignatures = mutableListOf<Signer>()
                options.forEach { option ->
                    val pubKeyMint = PublicKey(option.token)
                    val pubKeyDestination = PublicKey(option.toAddress)
                    val defaultWallet = getKeyPair(option.account)
                    val sourceAta = AssociatedTokenAccountProgram.deriveAtaAddress(
                        defaultWallet.publicKey,
                        pubKeyMint
                    )
                    val destinationAta = AssociatedTokenAccountProgram.deriveAtaAddress(
                        pubKeyDestination,
                        pubKeyMint
                    )

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
                    val rawAmount = com.altude.core.Programs.Utility.getRawQuantity(option.amount, decimals)

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
                    if (authorizedSignatures.find { it.publicKey == defaultWallet.publicKey } == null)
                        authorizedSignatures.add(
                            HotSigner(
                                SolanaKeypair(
                                    defaultWallet.publicKey,
                                    defaultWallet.secretKey
                                )
                            )
                        )
                }

                val blockhashInfo = rpc.getLatestBlockhash()

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

    suspend fun createAccount(option: CreateAccountOption): Result<String> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val defaultWallet = getKeyPair(option.account)
                val ownerKey = defaultWallet.publicKey//PublicKey(option.owner)

                val txInstructions = mutableListOf<TransactionInstruction>()
                option.tokens.forEach { token ->
                    val mintKey = PublicKey(token)
                    val ata = AssociatedTokenAccountProgram.deriveAtaAddress(ownerKey, mintKey)
                    if (!Utility.ataExists(ata.toBase58())) {
                        val ataInstruction =
                            AssociatedTokenAccountProgram.createAssociatedTokenAccount(
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
                if (txInstructions.count() == 0) {
                    if (option.tokens.count() > 1)
                        throw Error("All Token accounts already created")
                    throw Error("Token account already created")
                }
                val authorizedSigner = HotSigner(SolanaKeypair(ownerKey, defaultWallet.secretKey))


                val blockhashInfo = rpc.getLatestBlockhash(
                    commitment = option.commitment.name
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
                Error(e)
                Result.failure(e)
            }
        }

    suspend fun closeAccount(
        option: CloseAccountOption
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            var defaultWallet: Keypair? = null
            try {
                defaultWallet = getKeyPair(option.account)
            } catch (e: Error) {
            }
            val ownerKeypubkey = defaultWallet?.publicKey ?: PublicKey(option.account)

            val txInstructions = mutableListOf<TransactionInstruction>()
            var authorized: PublicKey
            var isOwnerRequiredSignature = false
            option.tokens.forEach { token ->
                val mintKey = PublicKey(token)
                val ata = AssociatedTokenAccountProgram.deriveAtaAddress(ownerKeypubkey, mintKey)
                val ataInfo = Utility.getAccountInfo(ata.toBase58())
                if (ataInfo != null) {
                    val parsed = ataInfo.data?.parsed?.info
                    if (parsed?.closeAuthority == feePayerPubKey.toBase58() || defaultWallet == null)
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
            if (txInstructions.count() == 0) {
                if (option.tokens.count() > 1)
                    throw Error("All Token accounts already closed")
                throw Error("Token account already closed")
            }

            val blockhashInfo = rpc.getLatestBlockhash(
                commitment = option.commitment.name
            )

            val tx = AltudeTransactionBuilder()
                .setFeePayer(feePayerPubKey)
                .addRangeInstruction(txInstructions)
                .setRecentBlockHash(blockhashInfo.blockhash)
                .build()
            if (isOwnerRequiredSignature && defaultWallet != null)
                tx.sign(HotSigner(defaultWallet))
            //val sign = Core.SignTransaction(privateKeyBytes,message)
            val serialized = Base64.encodeToString(
                tx.serialize(SerializeConfig(requireAllSignatures = false)),
                Base64.NO_WRAP
            )
            Result.success(serialized)
        } catch (e: Exception) {
            Result.failure(e)

        }
    }

    suspend fun getKeyPair(account: String = ""): Keypair {
        val seedData = StorageService.getDecryptedSeed(account)
        if (seedData != null) {
            if (seedData.type == "mnemonic") return Mnemonic(seedData.mnemonic).getKeyPair()
            return if (seedData.privateKey != null) KeyPair.solanaKeyPairFromPrivateKey(seedData.privateKey!!)
            else throw Error("No seed found in storage")
        } else throw Error("Please set seed first")
    }


}