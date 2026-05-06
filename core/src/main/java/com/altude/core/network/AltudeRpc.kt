package com.altude.core.network

import com.altude.core.api. RpcService
import com.altude.core.api.TransactionService
import com.altude.core.api.callRpcTyped
import com.altude.core.config.SdkConfig
import com.altude.core.data.RpcResponse
import com.altude.core.data.BlockhashResult
import com.altude.core.data.BlockhashValue
import com.altude.core.data.CommitmentParam
import com.altude.core.data.JsonRpc20Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import retrofit2.await
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime


class AltudeRpc(val endpoint: String) {

    val rpcService = RpcConfig.createService(endpoint, RpcService::class.java)

    companion object{
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }
        //temp token for 3 days
        var token: String = SdkConfig.apiConfig.Token  // may be blank until setApiKey() is called
        private var expiry: Long = 0

        @OptIn(ExperimentalTime::class)
        suspend fun getValidToken(): String {
            if (!SdkConfig.isConfigured) {
                throw IllegalStateException(
                    "SdkConfig.apiConfig is not initialised. " +
                    "Call SdkConfig.setApiKey(context, apiKey) before making network requests."
                )
            }
            val token = SdkConfig.apiConfig.Token
            val expiry = SdkConfig.apiConfig.TokenExpiration
            val now = Clock.System.now()
            println("now: $now")
            println("expiry: $expiry")
            if (token.isNotBlank() && expiry != null && now < expiry.minus(30.seconds)) {
                return token
            }

            println("Token expired or missing, refreshing...")
            val newToken = setToken()
            return newToken ?: error("Failed to refresh token")
        }


        //        fun saveToken(newToken: String , expiresIn: Long) { // expiresIn seconds
//            token = newToken
//            expiry = (System.currentTimeMillis() / 1000) + expiresIn
//        }
        @OptIn(ExperimentalTime::class)
        suspend fun setToken(): String? = withContext(Dispatchers.IO) {
            val service = SdkConfig.createService(TransactionService::class.java)
            try {
                val config = service.getConfig().await()

                // Prevent overwriting with empty response
                if (config.Token.isBlank()) {
                    println("Warning: Received empty token from backend")
                    return@withContext null
                }

                SdkConfig.apiConfig = config
                token = config.Token
                println("✅ Token refreshed: expires at ${config.TokenExpiration}")
                token
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun getLatestBlockhash(commitment: String = "finalized"): BlockhashValue {
        token = getValidToken()?: error("No valid token")
// Create a list to hold JSON elements for RPC request parameters
        val params: MutableList<JsonElement> = mutableListOf()

        // Use the provided configuration or create a default one

        params.add(json.encodeToJsonElement(CommitmentParam(commitment)))

        val rpcRequest = JsonRpc20Request(
            jsonrpc = "2.0",
            method = "getLatestBlockhash",
            params = JsonArray(content = params),
            id = "${Random.nextUInt()}",
        )

        val blockhashInfo: RpcResponse<BlockhashResult> = rpcService.callRpcTyped(
            json,
            "Bearer $token",
            rpcRequest
        )
        return blockhashInfo.result?.value?: error("No result returned")
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun getMinimumBalanceForRentExemption(usize: ULong): Long {
        token = getValidToken()?: error("No valid token")
        val params: MutableList<JsonElement> = mutableListOf()
        params.add(json.encodeToJsonElement(usize))
        val rpcRequest = JsonRpc20Request(
            jsonrpc = "2.0",
            method = "getMinimumBalanceForRentExemption",
            params =  JsonArray(content = params)
        )
        val resp: RpcResponse<Long> = rpcService.callRpcTyped(json,"Bearer $token", rpcRequest)

        if (resp.error != null) {
            throw IllegalStateException("RPC error ${resp.error.code}: ${resp.error.message}")
        }

        return resp.result ?: error("No result returned")
    }
    @OptIn(ExperimentalSerializationApi::class)
    suspend inline fun <reified T>getAccountInfo(publicKey:String, isBase64: Boolean = false): T {
        token = getValidToken()
        val params: MutableList<JsonElement> = mutableListOf()
        params.add(json.encodeToJsonElement(publicKey))

        val options = buildJsonObject {
            if(!isBase64)put("encoding", "jsonParsed")
            else put("encoding", "base64")
        }
        params.add(options)
        val rpcRequest = JsonRpc20Request(
            jsonrpc = "2.0",
            method = "getAccountInfo",
            params =  JsonArray(content = params)
        )
        val resp: RpcResponse<T> = rpcService.callRpcTyped(json,"Bearer $token", rpcRequest)

        if (resp.error != null) {
            throw IllegalStateException("RPC error ${resp.error.code}: ${resp.error.message}")
        }

        return resp.result ?: error("No result returned")
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun getAddressLookupTable(
        lookupTableAddress: String,
        commitment: String = "finalized"
    ): AddressLookupTableAccount {
        token = getValidToken() ?: error("No valid token")

        val params: MutableList<JsonElement> = mutableListOf()
        params.add(json.encodeToJsonElement(listOf(lookupTableAddress)))
        params.add(json.encodeToJsonElement(CommitmentParam(commitment)))

        val rpcRequest = JsonRpc20Request(
            jsonrpc = "2.0",
            method = "getAddressLookupTable",
            params = JsonArray(content = params),
            id = "${Random.nextUInt()}"
        )

        val response: RpcResponse<GetAddressLookupTableResult> = rpcService.callRpcTyped(
            json,
            "Bearer $token",
            rpcRequest
        )

        val table = response.result?.value?.state ?: error("Lookup table not found")
        return table
    }

    // --- Data classes for the RPC response ---
    @kotlinx.serialization.Serializable
    data class GetAddressLookupTableResult(
        val context: RpcContext,
        val value: AddressLookupTableValue?
    )

    @kotlinx.serialization.Serializable
    data class AddressLookupTableValue(
        val state: AddressLookupTableAccount
    )

    @kotlinx.serialization.Serializable
    data class AddressLookupTableAccount(
        val deactivationSlot: Long,
        val lastExtendedSlot: Long,
        val lastExtendedSlotStartIndex: Int,
        val authority: String?,
        val addresses: List<String> // these are the pubkeys inside the table
    )

    @kotlinx.serialization.Serializable
    data class RpcContext(
        val slot: Long,
        // Present in many RPC responses; optional so older payloads still decode.
        val apiVersion: String? = null
    )

    // ── Solana account (base64 encoding) ─────────────────────────────────────

    /**
     * Some Solana RPC fields are u64 and can exceed Kotlin Long.
     * Example: rentEpoch = 18446744073709551615 (u64::MAX)
     *
     * This serializer accepts either a JSON number or string and always returns a String.
     */
    object U64AsStringSerializer : KSerializer<String> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("U64AsString", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): String {
            // Prefer JSON-aware decoding so we can accept numeric primitives.
            val jsonDecoder = decoder as? JsonDecoder
            if (jsonDecoder != null) {
                val element = jsonDecoder.decodeJsonElement() as? JsonPrimitive
                    ?: return "0"
                // JsonPrimitive.content works for both numeric and string primitives and avoids Long overflow.
                return element.content
            }
            return runCatching { decoder.decodeString() }.getOrElse { "0" }
        }

        override fun serialize(encoder: Encoder, value: String) {
            encoder.encodeString(value)
        }
    }

    /**
     * Raw account data returned by `getAccountInfo` with `encoding: "base64"`.
     * [data] is a two-element array: [base64EncodedBytes, "base64"].
     */
    @Serializable
    data class SolanaAccountValue(
        val data:       List<String> = emptyList(),
        val executable: Boolean      = false,
        val lamports:   Long         = 0L,
        val owner:      String       = "",
        // Solana RPC returns u64 here; use String to avoid overflow.
        @Serializable(with = U64AsStringSerializer::class)
        val rentEpoch:  String       = "0",
        // Included in base64 getAccountInfo responses.
        val space:      Int?         = null
    )

    /**
     * Top-level `result` wrapper for `getAccountInfo`.
     * Use with `getAccountInfo<SolanaAccountResult>(pubkey, isBase64 = true)`.
     */
    @Serializable
    data class SolanaAccountResult(
        val context: RpcContext,
        val value:   SolanaAccountValue?
    )
}
