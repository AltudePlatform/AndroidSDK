package com.altude.core

import android.R
import android.util.Base64
import com.altude.core.model.MintLayout
import com.altude.core.model.SendOptions
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.Message
import com.solana.transaction.Transaction
import com.solana.transaction.TransactionInstruction
import diglol.crypto.Ed25519
import diglol.crypto.KeyPair
import foundation.metaplex.rpc.Commitment
import foundation.metaplex.rpc.RPC
import foundation.metaplex.rpc.RpcGetLatestBlockhashConfiguration
import foundation.metaplex.rpc.serializers.BorshAsBase64JsonArraySerializer
import foundation.metaplex.solana.programs.SystemProgram
import foundation.metaplex.solanaeddsa.Keypair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import foundation.metaplex.solanapublickeys.PublicKey
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.IOException
import org.bouncycastle.crypto.signers.Ed25519Signer
import kotlin.math.pow
import kotlin.collections.listOf
import kotlin.math.log




object TransactionTransferBuilder {

    suspend fun TransferTokenTransaction(
        option: SendOptions
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val rpc = RPC("https://multi-ultra-frost.solana-devnet.quiknode.pro/417151c175bae42230bf09c1f87acda90dc21968/")
            val feePayerPubKeypair = SolanaPublicKey.from("chenGqdufWByiUyxqg7xEhUVMqF3aS9sxYLSzDNmwqu")
            val privateKeyBytes = byteArrayOf(
                235.toByte(), 144.toByte(), 12, 215.toByte(), 112, 178.toByte(), 249.toByte(), 227.toByte(), 180.toByte(), 112, 121, 214.toByte(), 13, 190.toByte(), 158.toByte(), 91,
                208.toByte(), 118, 253.toByte(), 192.toByte(), 48, 6, 252.toByte(), 37, 111, 169.toByte(), 209.toByte(), 238.toByte(), 174.toByte(), 78, 210.toByte(), 184.toByte(),
                9, 37, 75, 1, 98, 80, 44, 48, 119, 25, 193.toByte(), 156.toByte(), 161.toByte(), 185.toByte(), 250.toByte(), 119,
                160.toByte(), 54, 62, 93, 4, 130.toByte(), 200.toByte(), 226.toByte(), 100, 255.toByte(), 215.toByte(), 170.toByte(), 26, 226.toByte(), 213.toByte(), 28
            )
            val feePayer = KeyPair(feePayerPubKeypair.bytes, privateKeyBytes)

            val decimals = getTokenDecimals(rpc, option.mint)

            val instruction = createSplTokenTransfer(
                sourceAta = option.source,
                destinationAta = option.destination,
                feePayer = feePayerPubKeypair,
                mint = option.mint,
                amount = option.amount,
                decimals = decimals
            )
            val blockhashInfo = rpc.getLatestBlockhash(
                RpcGetLatestBlockhashConfiguration(commitment = Commitment.finalized)
            )

            val recentBlockhash = blockhashInfo.blockhash
            val message = Message.Builder()
                .addInstruction(instruction)
                .setRecentBlockhash(recentBlockhash)
                .build()

            // 👇 Fix: unwrap Result<Transaction> and check for success
            val signed = SignTransaction(feePayer.privateKey, message)
            //if (signed.isFailure) return@withContext Result.failure(signed.exceptionOrNull()!!)

            val serialized = Base64.encodeToString(signed.serialize(), Base64.NO_WRAP)
            Result.success(serialized)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    fun createSplTokenTransfer(
        sourceAta: String,
        destinationAta: String,
        feePayer: SolanaPublicKey,
        mint: String,
        amount: Double,
        decimals: Int
    ): TransactionInstruction {
        val amountInBaseUnits = (amount * 10.0.pow(decimals)).toLong()
        val data = byteArrayOf(
            12, // transferChecked instruction index
            amountInBaseUnits.toByte(), // amount in lamports
            decimals.toByte()
        )

        return TransactionInstruction(
            programId = SolanaPublicKey.from("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"),
            accounts = listOf(
                AccountMeta(SolanaPublicKey.from(sourceAta), isSigner = false, isWritable = true),
                AccountMeta(SolanaPublicKey.from(mint), isSigner = false, isWritable = false),
                AccountMeta(SolanaPublicKey.from(destinationAta), isSigner = false, isWritable = true),
                AccountMeta(SolanaPublicKey.from(feePayer.string()), isSigner = true, isWritable = false),
            ),
            data = data
        )
    }
    fun Long.toLittleEndian(size: Int): ByteArray {
        return ByteArray(size) { i ->
            ((this shr (8 * i)) and 0xFF).toByte()
        }
    }
//    suspend fun getTokenDecimals(
//        rpc: RPC,
//        mintAddress: String
//    ): Int {
//        val pubkey = PublicKey(mintAddress)
//        val accountInfo = rpc.getAccountInfo<MintLayout>(
//            pubkey,
//            null,
//            MintLayout.serializer()
//        )
//
//        val decimals = accountInfo?.data?.decimals?.toInt()
//            ?: throw Exception("Failed to get decimals from token mint layout")
//
//        return decimals
//    }
    suspend fun getTokenDecimals(rpc: RPC, mintAddress: String): Int {
        val pubkey = PublicKey(mintAddress)

        val jsonObject = getAccountInfoRaw(mintAddress)

            val dataArray = jsonObject["result"]?.jsonObject
                ?.get("value")?.jsonObject

        val parsedDecimals = jsonObject["result"]
            ?.jsonObject?.get("value")
            ?.jsonObject?.get("data")
            ?.jsonObject?.get("parsed")
            ?.jsonObject?.get("info")
            ?.jsonObject?.get("decimals")
            ?.jsonPrimitive?.int

        return parsedDecimals ?: throw Exception("Unable to parse decimals")
        //return 9
    }

    suspend fun SignTransaction(
        privateKeyBytes: ByteArray,
        message: Message
    ) :  Transaction {
        val seed32 = privateKeyBytes.copyOfRange(0, 32) // Only the seed
        val keyPair = Ed25519.generateKeyPair(seed32)
        val signer = object : Ed25519Signer() {
            val publicKey: ByteArray get() = keyPair.publicKey
            suspend fun signPayload(payload: ByteArray): ByteArray = Ed25519.sign(keyPair, payload)
        }
        val signature = signer.signPayload(message.serialize());
        // Build Transaction
        val transaction = Transaction(listOf(signature), message)
        return  transaction;
    }
    suspend fun getAccountInfoRaw(publicKey: String): JsonObject {
        val client = OkHttpClient()

        val payload = """
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "getAccountInfo",
          "params": [
            "$publicKey",
            {
              "encoding": "jsonParsed"
            }
          ]
        }
    """.trimIndent()
        val mediaType = "application/json".toMediaType()

        val request = Request.Builder()
            .url("https://multi-ultra-frost.solana-devnet.quiknode.pro/417151c175bae42230bf09c1f87acda90dc21968/")
            .post(RequestBody.create(mediaType, payload))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw IOException("No response")
        return Json.parseToJsonElement(body).jsonObject
    }
}

