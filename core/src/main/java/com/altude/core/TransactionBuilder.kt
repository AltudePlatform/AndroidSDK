package com.altude.core

import android.util.Base64
import com.altude.core.model.CloseAccountOption
import com.altude.core.model.CreateAccountOption
import com.altude.core.model.SendOptions
import com.altude.core.service.Core
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.Message
import foundation.metaplex.rpc.Commitment
import foundation.metaplex.rpc.RPC
import foundation.metaplex.rpc.RpcGetLatestBlockhashConfiguration
import foundation.metaplex.solana.transactions.SolanaTransactionBuilder
import foundation.metaplex.solanaeddsa.SolanaEddsa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import foundation.metaplex.solanapublickeys.PublicKey
import kotlin.collections.listOf


object TransactionBuilder {

    private val quickNodeUrl = "https://multi-ultra-frost.solana-devnet.quiknode.pro/417151c175bae42230bf09c1f87acda90dc21968/"
    private val rpc = RPC(quickNodeUrl)
    val feePayerPubKeypair = PublicKey("chenGqdufWByiUyxqg7xEhUVMqF3aS9sxYLSzDNmwqu")
    val privateKeyBytes = byteArrayOf(
        235.toByte(), 144.toByte(), 12, 215.toByte(), 112, 178.toByte(), 249.toByte(), 227.toByte(), 180.toByte(), 112, 121, 214.toByte(), 13, 190.toByte(), 158.toByte(), 91,
        208.toByte(), 118, 253.toByte(), 192.toByte(), 48, 6, 252.toByte(), 37, 111, 169.toByte(), 209.toByte(), 238.toByte(), 174.toByte(), 78, 210.toByte(), 184.toByte(),
        9, 37, 75, 1, 98, 80, 44, 48, 119, 25, 193.toByte(), 156.toByte(), 161.toByte(), 185.toByte(), 250.toByte(), 119,
        160.toByte(), 54, 62, 93, 4, 130.toByte(), 200.toByte(), 226.toByte(), 100, 255.toByte(), 215.toByte(), 170.toByte(), 26, 226.toByte(), 213.toByte(), 28
    )
    //val feePayer = KeyPair(feePayerPubKeypair.toByteArray(), privateKeyBytes)
    suspend fun TransferToken(
        option: SendOptions
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {


            val decimals = Core.getTokenDecimals(rpc, option.mint)

            val instruction = Core.createSplTokenTransfer(
                sourceAta = PublicKey(option.source),
                destinationAta = PublicKey(option.destination),
                feePayer = feePayerPubKeypair,
                mint = PublicKey(option.mint),
                amount = option.amount,
                decimals = decimals
            )
            val blockhashInfo = rpc.getLatestBlockhash(
                RpcGetLatestBlockhashConfiguration(commitment = Commitment.finalized)
            )
            //val privateKey = "4Z7cXSyeFR8wNGMVXUE1TwtKn5D5Vu7FzEv69dokLv7KrQk7h6pu4LF8ZRR9yQBhc7uSM6RTTZtU1fmaxiNrxXrs".decodeBase58().copyOfRange(0, 32)

            val k  = SolanaEddsa.createKeypairFromSecretKey(privateKeyBytes.copyOfRange(0, 32))//Core.createEd25519KeypairFromSeed(privateKeyBytes.copyOfRange(0, 32))
            val signer = Core.HotSigner(Core.SolanaKeypair(k.publicKey, k.secretKey))

            val recentBlockhash = blockhashInfo.blockhash
            val build = SolanaTransactionBuilder()
                .addInstruction(instruction)
                .setRecentBlockHash(recentBlockhash)
                .setSigners(listOf(signer))
                .build()

            //val sign = Core.SignTransaction(privateKeyBytes,message)
            val serialized = Base64.encodeToString(build.serialize(), Base64.NO_WRAP)
            Result.success(serialized)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun CreateAccount(
        option: CreateAccountOption
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {

            val instruction = Core.createAssociatedTokenAccount(
                feePayer = feePayerPubKeypair,
                owner = option.owner,
                mint = option.mint
            )

            val k  = SolanaEddsa.createKeypairFromSecretKey(privateKeyBytes.copyOfRange(0, 32))//Core.createEd25519KeypairFromSeed(privateKeyBytes.copyOfRange(0, 32))
            val signer = Core.HotSigner(Core.SolanaKeypair(k.publicKey, k.secretKey))


            val blockhashInfo = rpc.getLatestBlockhash(
                RpcGetLatestBlockhashConfiguration(commitment = Commitment.finalized)
            )

            val recentBlockhash = blockhashInfo.blockhash
            val build = SolanaTransactionBuilder()
                .addInstruction(instruction)
                .setRecentBlockHash(recentBlockhash)
                .setSigners(listOf(signer))
                .build()

            //val sign = Core.SignTransaction(privateKeyBytes,message)
            val serialized = Base64.encodeToString(build.serialize(), Base64.NO_WRAP)
            Result.success(serialized)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    suspend fun CloseAccount(
        option: CloseAccountOption
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {

            val instruction = Core.createAssociatedTokenAccount(
                feePayer = feePayerPubKeypair,
                owner = option.owner.publicKey.toBase58(),
                mint = option.mint
            )

            val k  = SolanaEddsa.createKeypairFromSecretKey(privateKeyBytes.copyOfRange(0, 32))//Core.createEd25519KeypairFromSeed(privateKeyBytes.copyOfRange(0, 32))
            val signer = Core.HotSigner(Core.SolanaKeypair(k.publicKey, k.secretKey))
            val authorized = Core.HotSigner(Core.SolanaKeypair(option.owner.publicKey, option.owner.secretKey))


            val blockhashInfo = rpc.getLatestBlockhash(
                RpcGetLatestBlockhashConfiguration(commitment = Commitment.finalized)
            )

            val recentBlockhash = blockhashInfo.blockhash
            val build = SolanaTransactionBuilder()
                .addInstruction(instruction)
                .setRecentBlockHash(recentBlockhash)
                .setSigners(listOf(signer,authorized))
                .build()

            //val sign = Core.SignTransaction(privateKeyBytes,message)
            val serialized = Base64.encodeToString(build.serialize(), Base64.NO_WRAP)
            Result.success(serialized)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

}

