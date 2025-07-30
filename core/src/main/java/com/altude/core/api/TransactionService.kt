package com.altude.core.api

import com.altude.core.data.GetHistoryData
import com.google.gson.annotations.SerializedName
import foundation.metaplex.solana.transactions.Transaction
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

data class SendTransactionRequest(
    val SignedTransaction: String
)

data class BatchTransactionRequest(
    val SignedTransaction: String
)


data class TransactionResponse(
    //@SerializedName("Status")
    val status: String, // Match C# string type

    //@SerializedName("Message")
    val message: String,

    //@SerializedName("Signature")
    val signature: String
)

interface TransactionService {

    @POST("api/transaction/send")
    fun sendTransaction(
        @Body SignedTransaction: SendTransactionRequest
    ): Call<TransactionResponse>

    @POST("api/transaction/sendbatch")
    fun sendBatchTransaction(
        @Body body: BatchTransactionRequest
    ): Call<TransactionResponse>

    @POST("api/account/create")
    fun createAccount(
        @Body body: SendTransactionRequest
    ): Call<TransactionResponse>

    @POST("api/account/close")
    fun closeAccount(
        @Body body: SendTransactionRequest
    ): Call<TransactionResponse>
    @POST("api/account/close")
    fun getbalance(
        @Body body: SendTransactionRequest
    ): Call<TransactionResponse>

    @POST("api/account/gethistory")
    fun getHistory(
        @Query("Page") page: String,
        @Query("PageSize") pageSize: String,
        @Query("address") address: String,
    ): Call<GetHistoryData>
}
