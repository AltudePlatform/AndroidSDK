package com.altude.core.api

import com.altude.core.data.BatchTransactionRequest
import com.altude.core.data.MintData
import com.altude.core.data.SendTransactionRequest
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.Serializable

interface ISendTransactionRequest{
    val SignedTransaction: String
}

interface IBatchTransactionRequest{
    val SignedTransaction: String
}

@Serializable
data class GetBalanceRequest(
    val accountAddress: String,
    val mintAddress: String
)

@Serializable
data class GetAccountInfoRequest(
    val accountAddress: String,
)

data class MintRequest(
    val userId: String,
    val transaction: String,
    val code: String
)


data class MintResponse(
    val status: String,
    val data: MintData?
)
@Serializable
data class QuickNodeResponse(
    val token: String,
)
@Serializable
data class ConfigResponse(
    val feePayer: String = "",
    val rpcUrl: String  = "",
)
interface TransactionService {

    @POST("api/transaction/send")
    fun sendTransaction(
        @Body body: SendTransactionRequest
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

    @POST("api/account/balance")
    fun getBalance(
        @Body body: GetBalanceRequest
    ): Call<JsonElement>

    @POST("api/account/getaccountinfo")
    fun getAccountInfo(
        @Body body: GetAccountInfoRequest
    ): Call<JsonElement>

    @POST("api/account/gethistory")
    fun getHistory(
        @Query("Page") page: String,
        @Query("PageSize") pageSize: String,
        @Query("walletAddress") address: String,
    ): Call<JsonElement>

    @POST("api/nft/mint")
    fun postMint(
        @Body body: ISendTransactionRequest
    ): Call<JsonElement>

    @POST("api/nft/createNFTCollection")
    fun postCreateCollectionNft(
        @Body body: ISendTransactionRequest
    ): Call<JsonElement>

    @GET("api/auth/token")
    suspend fun getQuickNodeJWTToken(): JsonElement

    @GET("api/transaction/config")
    fun getConfig(): Call<ConfigResponse>
}

