package com.altude.core.network

import com.altude.core.api. QuickNodeRpcService
import com.altude.core.api.TransactionService
import com.altude.core.api.callRpcTyped
import com.altude.core.config.SdkConfig
import com.altude.core.data.AccountInfoResult
import com.altude.core.data.RpcResponse
import com.altude.core.data.BlockhashResult
import com.altude.core.data.BlockhashValue
import com.altude.core.data.CommitmentParam
import com.altude.core.data.JsonRpc20Request
import foundation.metaplex.rpc.Commitment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import kotlin.random.Random
import kotlin.random.nextUInt


class QuickNodeRpc(val endpoint: String) {

    val rpcService = RpcConfig.createService(endpoint, QuickNodeRpcService::class.java)
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }
    companion object{
        //temp token for 3 days
        private var token: String = "eyJhbGciOiJSUzI1NiIsImtpZCI6IjEiLCJ0eXAiOiJKV1QifQ.eyJzdWIiOiJxdWlja25vZGUtY2xpZW50IiwibmJmIjoxNzU4NTkxMzI2LCJleHAiOjE3NjExODM0NDYsImlhdCI6MTc1ODU5MTMyNn0.Z0Z3ym9b-OxUiYP4FKfD8eeoMsMJdVhtFaTZY3daTCnAn_elWZg-y5uTTCD5NzqzVmzrXDcqrAIBu26M0SmPKH8NQuGxF6aqsFwpXe4UhpxxJg26uboXTBmdj1j_qNr6TFefr1OK1_0zKleTJqK1Ia0FTZ2Tc5H-yf3xsCrDQS1uEB-I3YXDHBW-Q3O7Hjc4Wki_zZfiTVNEdvIogx1aN_Is6l7kWchsHjeeTsd7DRKgNM_geRjGCxLNWvysEGBpu8Myin3k4QMxE87erIKkvSwCM96JWcUL8HdjelBVJl3OlaycmuP-pi-ncStBB8pL9Zmyz3g5wq9EH7G6hlSOXg"
        private var expiry: Long = 0

        suspend fun getValidToken(): String? {
            val now = System.currentTimeMillis() / 1000
            return if (token != "" && now < expiry) token else setToken()
        }

        fun saveToken(newToken: String , expiresIn: Long) { // expiresIn seconds
            token = newToken
            println("token: $token")
            expiry = (System.currentTimeMillis() / 1000) + expiresIn
        }
        suspend fun setToken() : String? = withContext(Dispatchers.IO) {
            val service = SdkConfig.createService(TransactionService::class.java)
            val response = service.getQuickNodeJWTTOken().execute()
            if (response.isSuccessful) {
                saveToken( response.body()?.token.toString(), 60)
                token
            } else {
                null
            }
        }

    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun getLatestBlockhash(commitment: Commitment = Commitment.finalized): BlockhashValue {
// Create a list to hold JSON elements for RPC request parameters
        val params: MutableList<JsonElement> = mutableListOf()

        // Use the provided configuration or create a default one

        params.add(json.encodeToJsonElement(CommitmentParam(commitment.name)))

        getValidToken()
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
        getValidToken()
        val params: MutableList<JsonElement> = mutableListOf()
        params.add(json.encodeToJsonElement(usize))
        val rpcRequest = JsonRpc20Request(
            jsonrpc = "2.0",
            method = "getMinimumBalanceForRentExemption",
            params =  JsonArray(content = params)
        )
        val jsonBody = json.encodeToString(rpcRequest)
        println(">>> RPC Request: $jsonBody")
        val resp: RpcResponse<Long> = rpcService.callRpcTyped(json,"Bearer $token", rpcRequest)

        if (resp.error != null) {
            throw IllegalStateException("RPC error ${resp.error.code}: ${resp.error.message}")
        }

        return resp.result ?: error("No result returned")
    }
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun getAccountInfo(publicKey:String): AccountInfoResult {
        getValidToken()
        val params: MutableList<JsonElement> = mutableListOf()
        params.add(json.encodeToJsonElement(publicKey))

        val options = buildJsonObject {
            put("encoding", "jsonParsed")
        }
        params.add(options)
        val rpcRequest = JsonRpc20Request(
            jsonrpc = "2.0",
            method = "getAccountInfo",
            params =  JsonArray(content = params)
        )
        val jsonBody = json.encodeToString(rpcRequest)
        println(">>> RPC Request: $jsonBody")
        val resp: RpcResponse<AccountInfoResult> = rpcService.callRpcTyped(json,"Bearer $token", rpcRequest)

        if (resp.error != null) {
            throw IllegalStateException("RPC error ${resp.error.code}: ${resp.error.message}")
        }

        return resp.result ?: error("No result returned")
    }
//    suspend fun callRpc(endpoint: String, jwtToken: String, method: String, params: Any): String {
//        var rpc = RPC(endpoint,)
//        rpc.getMinimumBalanceForRentExemption(111.toULong())
////        rpc.getAccountInfo(
////            Utility.MPL_NOOP,
////            configuration = RpcGetAccountInfoConfiguration(),
////            serializer = SolanaResponseSerializer(
////                BlockhashResponse.serializer()
////            )
////        )
//        val requestJson = """
//            {
//              "jsonrpc":"2.0",
//              "id":1,
//              "method":"$method",
//              "params":$params
//            }
//        """.trimIndent()
//
//        val body = requestJson.toRequestBody("application/json".toMediaType())
//
//        val request = Request.Builder()
//            .url(endpoint)
//            .addHeader("Authorization", "Bearer $jwtToken")
//            .post(body)
//            .build()
//
//        client.newCall(request).execute().use { response ->
//            if (!response.isSuccessful) throw Exception("Unexpected code $response")
//            return response.body.string()
//        }
//    }
}

