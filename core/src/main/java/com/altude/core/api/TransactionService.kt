package com.altude.core.api

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
    val success: Boolean,
    val message: String
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
}
