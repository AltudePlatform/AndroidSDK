package com.altude.core.service

import com.metaplex.signer.Signer
import com.solana.programs.SystemProgram
import com.solana.publickey.SolanaPublicKey
import diglol.crypto.Ed25519
import foundation.metaplex.rpc.RPC
import foundation.metaplex.solana.transactions.AccountMeta
import foundation.metaplex.solana.transactions.TransactionInstruction
import foundation.metaplex.solanaeddsa.Keypair
import foundation.metaplex.solanaeddsa.SolanaEddsa
import foundation.metaplex.solanapublickeys.PublicKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

object  Core {

    private val TOKEN_PROGRAM_ID  = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    private val SYSTEM_PROGRAM_ID  = "11111111111111111111111111111111"
    private val ATA_PROGRAM_ID  = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    private val SYSVAR_RENT_PUBKEY  = PublicKey("SysvarRent111111111111111111111111111111111")
    private val quickNodeUrl = "https://multi-ultra-frost.solana-devnet.quiknode.pro/417151c175bae42230bf09c1f87acda90dc21968/"
    fun createSplTokenTransfer(
        sourceAta: PublicKey,
        destinationAta: PublicKey,
        feePayer: PublicKey,
        mint: PublicKey,
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
            programId = PublicKey(TOKEN_PROGRAM_ID),
            keys = listOf(
                AccountMeta(sourceAta, isSigner = false, isWritable = true),
                AccountMeta(mint, isSigner = false, isWritable = false),
                AccountMeta(destinationAta, isSigner = false, isWritable = true),
                AccountMeta(feePayer, isSigner = true, isWritable = false),
            ),
            data = data
        )
    }
    fun createTransferInstruction(
        source: PublicKey,
        destination: PublicKey,
        owner: PublicKey,
        amount: ULong
    ): TransactionInstruction {
        val keys = listOf(
            AccountMeta(source, isSigner = false, isWritable = true),
            AccountMeta(destination, isSigner = false, isWritable = true),
            AccountMeta(owner, isSigner = true, isWritable = false),
        )

        val data = byteArrayOf(3) + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(amount.toLong()).array()
        return TransactionInstruction(
            keys = keys,
            programId = PublicKey(TOKEN_PROGRAM_ID),
            data = data
        )
    }

    suspend fun createAssociatedTokenAccount(
        feePayer: PublicKey,
        owner: String,
        mint: String
    ): TransactionInstruction {
        val ownerKey = PublicKey(owner)
        val mintKey = PublicKey(mint)
        val ata = deriveAtaAddress(ownerKey, mintKey)
        return TransactionInstruction(
            programId = PublicKey(ATA_PROGRAM_ID),
            keys = listOf(
                AccountMeta(feePayer, isSigner = true, isWritable = true),
                AccountMeta(ata, isSigner = false, isWritable = true),
                AccountMeta(ownerKey, isSigner = false, isWritable = false),
                AccountMeta(mintKey, isSigner = false, isWritable = false),
                AccountMeta(PublicKey(SYSTEM_PROGRAM_ID), isSigner = false, isWritable = false),
                AccountMeta(PublicKey(TOKEN_PROGRAM_ID), isSigner = false, isWritable = false),
                AccountMeta(SYSVAR_RENT_PUBKEY, isSigner = false, isWritable = false),
            ),
            data = byteArrayOf() // No data needed for ATA creation
        )
    }
    suspend fun closeAtaAccount(
        owner: String,
        mint: String,
        destination: String,
        authority:String,
        feePayer:String,
    ): TransactionInstruction {
        val ownerKey = PublicKey(owner)
        val mintKey = PublicKey(mint)
        val ata = deriveAtaAddress(ownerKey, mintKey)
        return TransactionInstruction(
            programId = PublicKey(TOKEN_PROGRAM_ID),
            keys = listOf(
                AccountMeta(ata, isSigner = false, isWritable = true),
                AccountMeta(PublicKey(destination), isSigner = false, isWritable = true),
                AccountMeta(PublicKey(authority), isSigner = true, isWritable = false)
            ),
            data = byteArrayOf() // No data needed for ATA creation
        )
    }

    suspend fun deriveAtaAddress(owner: PublicKey, mint: PublicKey): PublicKey {
        val seeds = listOf(
            owner.toByteArray(),
            PublicKey(TOKEN_PROGRAM_ID).toByteArray(),
            mint.toByteArray()
        )
        val ata_program_id = PublicKey(ATA_PROGRAM_ID)
        return PublicKey.findProgramAddress(seeds, ata_program_id).address
    }

    fun Long.toLittleEndian(size: Int): ByteArray {
        return ByteArray(size) { i ->
            ((this shr (8 * i)) and 0xFF).toByte()
        }
    }

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

//    suspend fun SignTransaction(
//        privateKeyBytes: ByteArray,
//        message: Message
//    ) :  Transaction {
//        val seed32 = privateKeyBytes.copyOfRange(0, 32) // Only the seed
//        val keyPair = Ed25519.generateKeyPair(seed32)
//        val signer = object : Ed25519Signer() {
//            val publicKey: ByteArray get() = keyPair.publicKey
//            suspend fun signPayload(payload: ByteArray): ByteArray = Ed25519.sign(keyPair, payload)
//        }
//        val signature = signer.signPayload(message.serialize());
//        // Build Transaction
//        val transaction = Transaction(listOf(signature), message)
//        return  transaction;
//    }
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
            .url(quickNodeUrl)
            .post(payload.toRequestBody(mediaType))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw IOException("No response")
        return Json.parseToJsonElement(body).jsonObject
    }
    class SolanaKeypair(
        override val publicKey: PublicKey,
        override val secretKey: ByteArray
    ) : Keypair

    class HotSigner(private val keyPair: Keypair) : Signer {
        override val publicKey: PublicKey = keyPair.publicKey as PublicKey
        override suspend fun signMessage(message: ByteArray): ByteArray = SolanaEddsa.sign(message, keyPair)
    }
    fun createEd25519KeypairFromSeed(seed: ByteArray): Pair<ByteArray, ByteArray> {
        val privParams = Ed25519PrivateKeyParameters(seed, 0)
        val pubKey = privParams.generatePublicKey().encoded
        val privKey = privParams.encoded
        return pubKey to privKey
    }
}