package com.altude.core.Program

import com.altude.core.data.AccountInfoResponse
import com.altude.core.data.AccountInfoValue
import foundation.metaplex.solanapublickeys.PublicKey
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

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

    suspend fun getTokenDecimals(mintAddress: String): Int {
        val response = getAccountInfo(mintAddress)

        val decimals = response
            ?.data?.parsed?.info?.decimals

        return decimals ?: throw Exception("Unable to parse token decimals from mint: $mintAddress")
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

    fun getAccountInfo(publicKey: String): AccountInfoValue? {
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

        val json = Json {
            ignoreUnknownKeys = true // <-- IMPORTANT
        }

        return json.decodeFromString<AccountInfoResponse>(body).result.value
    }


    fun createEd25519KeypairFromSeed(seed: ByteArray): Pair<ByteArray, ByteArray> {
        val privParams = Ed25519PrivateKeyParameters(seed, 0)
        val pubKey = privParams.generatePublicKey().encoded
        val privKey = privParams.encoded
        return pubKey to privKey
    }

    fun ataExists(publicKey: String): Boolean {
        val response = getAccountInfo(publicKey)
        return response != null
    }

    fun getAtaOwner(publicKey: String): String? {
        val response = getAccountInfo(publicKey)
        return response?.data?.parsed?.info?.owner
    }

    fun validateAta(publicKey: String, expectedOwner: String) {
        val response = getAccountInfo(publicKey)
        val value = response
            ?: throw Error("Associated token account does not exist!")

        val actualOwner = value.data?.parsed?.info?.owner
        if (expectedOwner != actualOwner) {
            throw Error("Authorized owner is $actualOwner, expected $expectedOwner")
        }
    }
    fun getRawQuantity(quantity: Double, decimals: Int): Long {
        return (quantity * 10.0.pow(decimals)).toLong()
    }
}