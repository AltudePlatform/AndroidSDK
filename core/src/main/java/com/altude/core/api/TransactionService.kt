package com.altude.core.api

import com.google.gson.annotations.SerializedName
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

data class SendTransactionRequest(
    val SignedTransaction: String
)

data class BatchTransactionRequest(
    val signedTransactions: List<String>
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

    @POST("api/transaction/batch")
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
}
