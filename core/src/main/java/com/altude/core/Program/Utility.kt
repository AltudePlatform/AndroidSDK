package com.altude.core.Program

import foundation.metaplex.rpc.RPC
import foundation.metaplex.solanapublickeys.PublicKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.nio.ByteBuffer
import java.nio.ByteOrder

object  Utility {

    val TOKEN_PROGRAM_ID  = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    val SYSTEM_PROGRAM_ID  = "11111111111111111111111111111111"
    val ATA_PROGRAM_ID  = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    val SYSVAR_RENT_PUBKEY  = PublicKey("SysvarRent111111111111111111111111111111111")
    val quickNodeUrl = "https://multi-ultra-frost.solana-devnet.quiknode.pro/417151c175bae42230bf09c1f87acda90dc21968/" //change this with envi variable



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


    fun buildSetAuthorityData(
        authorityType: Int,           // e.g. 2 for AccountOwner
        newAuthority: PublicKey       // the new owner's public key
    ): ByteArray {
        val instruction: Byte = 6 // SPL Token: SetAuthority instruction
        val authorityTypeByte = authorityType.toByte()

        val buffer = ByteBuffer.allocate(1 + 1 + 1 + 32)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(instruction)          // 1 byte: instruction (6)
        buffer.put(authorityTypeByte)    // 1 byte: authority type (2 for AccountOwner)
        buffer.put(1.toByte())           // 1 byte: "Option<Pubkey>" = Some = 1
        buffer.put(newAuthority.toByteArray()) // 32 bytes: new authority public key

        return buffer.array()
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
    fun getAccountInfoRaw(publicKey: String): JsonObject {
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
        val body = response.body.string()
        return Json.parseToJsonElement(body).jsonObject
    }


    fun createEd25519KeypairFromSeed(seed: ByteArray): Pair<ByteArray, ByteArray> {
        val privParams = Ed25519PrivateKeyParameters(seed, 0)
        val pubKey = privParams.generatePublicKey().encoded
        val privKey = privParams.encoded
        return pubKey to privKey
    }

    fun ataExists(publicKey: String): Boolean {
        val result = getAccountInfoRaw(publicKey)
        val value = result["result"]?.jsonObject?.get("value")
        return value != null && value !is JsonNull
    }
    fun getAtaOwner(publicKey: String): String? {
        val result = getAccountInfoRaw(publicKey)

        val value = result["result"]?.jsonObject?.get("value")?.jsonObject
            ?: return null

        return value["data"]?.jsonObject
            ?.get("parsed")?.jsonObject
            ?.get("info")?.jsonObject
            ?.get("owner")?.jsonPrimitive?.content
    }
    fun validateAta(publicKey: String, owner: String) {
        val result = getAccountInfoRaw(publicKey)

        val value = result["result"]?.jsonObject?.get("value")?.jsonObject
        if (value == null)
            throw Error("Associated token account does not exist!")
        val authorized =  value["data"]?.jsonObject
            ?.get("parsed")?.jsonObject
            ?.get("info")?.jsonObject
            ?.get("closeAuthority")?.jsonPrimitive?.content
        if(owner !=  authorized)
            throw Error("Authorized owner is $authorized")
    }
    fun getRawQuantity(quantity: Double, decimals: Int): Long {
        return (quantity * Math.pow(10.0, decimals.toDouble())).toLong()
    }
}