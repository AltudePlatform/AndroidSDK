package com.altude.nft

import android.util.Base64
import com.altude.core.Programs.AssociatedTokenAccountProgram
import com.altude.core.Programs.MPLCore
import com.altude.core.Programs.TokenProgram
import com.altude.core.Programs.Utility
import com.altude.core.config.SdkConfig
import com.altude.core.data.CloseAccountOption
import com.altude.core.data.CreateAccountOption
import com.altude.core.data.CreateNFTCollectionOption
import com.altude.core.data.ISendOption
import com.altude.core.data.MintOption
import com.altude.core.data.SendOptions
import com.altude.core.helper.Mnemonic
import com.altude.core.model.AltudeTransactionBuilder
import com.altude.core.model.EmptySignature
import com.altude.core.model.HotSigner
import com.altude.core.model.KeyPair
import com.altude.core.model.SolanaKeypair
import com.altude.core.network.QuickNodeRpc
import com.altude.core.service.StorageService
import com.metaplex.signer.Signer
import foundation.metaplex.rpc.Commitment
import foundation.metaplex.solana.transactions.SerializeConfig
import foundation.metaplex.solana.transactions.TransactionInstruction
import foundation.metaplex.solanapublickeys.PublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.Error
import kotlin.text.format

object TransactionManager {

    private val rpc = QuickNodeRpc(Utility.QUICKNODE_URL)
    val feePayerPubKey =
        PublicKey(SdkConfig.apiConfig.feePayer) // PublicKey("Hwdo4thQCFKB3yuohhmmnb1gbUBXySaVJwBnkmRgN8cK") //ALZ8NJcf8JDL7j7iVfoyXM8u3fT3DoBXsnAU6ML7Sb5W BjLvdmqDjnyFsewJkzqPSfpZThE8dGPqCAZzVbJtQFSr



    suspend fun getKeyPair(account: String = ""): SolanaKeypair {
        val seedData = StorageService.getDecryptedSeed(account)
        if (seedData != null) {
            if (seedData.type == "mnemonic") return Mnemonic(seedData.mnemonic).getKeyPair()
            return if (seedData.privateKey != null) KeyPair.solanaKeyPairFromPrivateKey(seedData.privateKey!!)
            else throw Error("No seed found in storage")
        } else throw Error("Please set seed first")
    }

    suspend fun createCollectionNft(
        option: CreateNFTCollectionOption
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            // --- Setup / accounts ----------------------------------------------------------------
            //val mintAccount = KeyPair.generate()                     // new mint keypair
            //val mintLayout: Long = 82
            val defaultWallet =
                getKeyPair("")                       // your wallet (payer / update authority)
            //val rent = rpc.getMinimumBalanceForRentExemption(usize = mintLayout.toULong()).toLong()

            // Program IDs (ensure these constants match your Utility)

            // Fresh keypair for the collection asset
            val collectionKeypair = KeyPair.generate()
            println(collectionKeypair)
            val transaction = MPLCore.createCollectionv2(
                payer = feePayerPubKey,
                updateAuthority = defaultWallet.publicKey,
                collectionMint = collectionKeypair.publicKey,
                name = option.name,
                uri = option.metadataUri
            )

            val blockhashInfo =
                rpc.getLatestBlockhash(commitment = Commitment.finalized)

            val tx = AltudeTransactionBuilder()
                .setFeePayer(feePayerPubKey)
                .addInstruction(transaction)
                .setRecentBlockHash(blockhashInfo.blockhash)
                .setSigners(listOf(HotSigner(defaultWallet), HotSigner(collectionKeypair)))
                .build()

            // NOTE: tx.serialize(requireAllSignatures=false) means you still need to sign
            // required signers: fee payer (if signing), mintAccount (new account), defaultWallet (update authority) as needed
            val serialized = Base64.encodeToString(
                tx.serialize(SerializeConfig(requireAllSignatures = false)),
                Base64.NO_WRAP
            )

            // You must sign & send the tx (server-side or client-side):
            // rpc.sendTransaction(tx, listOf(feePayerKeypair, mintAccount /*, defaultWallet if needed */)).getOrThrow()

            Result.success(serialized)
        } catch (e: Exception) {
            Result.failure(e)

        }
    }

    suspend fun mint(
        option: MintOption
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            // --- Setup / accounts ----------------------------------------------------------------
            //val mintAccount = KeyPair.generate()                     // new mint keypair
            //val mintLayout: Long = 82
            val defaultWallet =
                getKeyPair(option.owner)                       // your wallet (payer / update authority)

            //BubblegumInstructions.
            val mintIx = MPLCore.mintV2(
                uri = option.uri,
                symbol = option.symbol,
                name = option.name,
                payer = feePayerPubKey,
                leafOwner = defaultWallet.publicKey,
                merkleTree = PublicKey("3SvceL8M3fyEz7ALbqpjhqcr2W3iusijCG6g84fBX9ns"), //merkleTree.publicKey, //4RyMxX42zZLNBqC1y7KcjqPeNChnsq5Ey6ZJyWDU9M8W 14QSPv5BtZCh8itGrUCu2j7e7A88fwZo3cAjxi4R5Fgj 7GzoPkZRSCaHvH3yYFpFTfm2pQGhdXZ8Tp1rTB3ughBb
                sellerFeeBasisPoints = option.sellerFeeBasisPoints,
                coreCollection = PublicKey(option.collection),
                treeCreatorOrDelegate = feePayerPubKey,
                collectionAuthority = defaultWallet.publicKey
            )

//            val tree  = MPLCore.createTreeV2(
//                rpc = rpc,
//                payer = feePayerPubKey,
//                merkleTree = KeyPair.generate().publicKey,
//                treeCreator = feePayerPubKey,
//                maxDepth = 14,
//                maxBufferSize = 64,
//                isPublic = true,
//            )
            // println("Mint ix: $mintIx")
            val blockhashInfo =
                rpc.getLatestBlockhash(commitment = Commitment.confirmed)
            println("latest blockhash $blockhashInfo")
            //val payerKeypair = KeyPair.solanaKeyPairFromMnemonic("bring record van away man person trouble clay rebuild review dust pond")
            val tx = AltudeTransactionBuilder()
                .setFeePayer(feePayerPubKey)
                //.addRangeInstruction(ixs)
                .addRangeInstruction(mintIx)
                .setRecentBlockHash(blockhashInfo.blockhash)
                //.setSigners(listOf(HotSigner(defaultWallet)))//HotSigner(merkleTree), ,HotSigner(defaultWallet)
                .build()
            //val stx = tx.serialize()
//            val res = rpc.sendTransaction(
//                stx,
//                configuration = RpcSendTransactionConfiguration(commitment = Commitment.confirmed)
//            )

            tx.sign(listOf(HotSigner(defaultWallet)))
            val compiled = tx.compileMessage().serialize()
            println(compiled.joinToString("") { "%02x".format(it) })
            val compiled1 = tx.compileMessage().serialize()
            println("KT Compiled Message Hex: ${compiled1.joinToString("") { "%02x".format(it) }}")
            // NOTE: tx.serialize(requireAllSignatures=false) means you still need to sign
            // required signers: fee payer (if signing), mintAccount (new account), defaultWallet (update authority) as needed
            val serialized = Base64.encodeToString(
                tx.serialize(
                    SerializeConfig(
                        requireAllSignatures = false,
                        verifySignatures = false
                    )
                ),
                Base64.NO_WRAP
            )
            println("Partial Signed tx $serialized")
            // You must sign & send the tx (server-side or client-side):
            // rpc.sendTransaction(tx, listOf(feePayerKeypair, mintAccount /*, defaultWallet if needed */)).getOrThrow()

            Result.success(serialized)
        } catch (e: Exception) {
            Result.failure(e)

        }
    }
}