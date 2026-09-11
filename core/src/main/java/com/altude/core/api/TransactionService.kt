package com.altude.core.api

import com.altude.core.data.BatchTransactionRequest
import com.altude.core.data.SendTransactionRequest
import com.altude.core.data.SwapTransactionRequest
import kotlinx.serialization.Contextual
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

interface ISendTransactionRequest{
    val SignedTransaction: String
}

interface IBatchTransactionRequest{
    val SignedTransaction: String
}

@Serializable
data class QuickNodeResponse(
    val token: String,
)
@Serializable
data class ConfigResponse @OptIn(ExperimentalTime::class) constructor(
    val FeePayer: String = "",
    val RpcUrl: String  = "",
    val Token: String = "",
    val RpcEnvironment: String = "",
    @Contextual
    val TokenExpiration: Instant? = null
)
interface TransactionService {

    @POST("api/transaction/send")
    fun sendTransaction(
        @Body body: SendTransactionRequest
    ): Call<JsonElement>
    @POST("api/transaction/swap")
    fun swapTransaction(
        @Body body: SwapTransactionRequest
    ): Call<JsonElement>

    @POST("api/transaction/sendbatch")
    fun sendBatchTransaction(
        @Body body: BatchTransactionRequest
    ): Call<JsonElement>

    @POST("api/account/create")
    fun createAccount(
        @Body body: SendTransactionRequest
    ): Call<JsonElement>

    @POST("api/account/close")
    fun closeAccount(
        @Body body: SendTransactionRequest
    ): Call<JsonElement>

    @POST("api/account/gethistory")
    fun getHistory(
        @Query("Page") page: String,
        @Query("PageSize") pageSize: String,
        @Query("walletAddress") address: String,
    ): Call<JsonElement>

    @GET("api/transaction/config")
    fun getConfig(): Call<ConfigResponse>
}
