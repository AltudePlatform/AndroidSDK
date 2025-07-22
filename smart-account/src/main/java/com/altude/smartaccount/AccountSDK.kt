package com.altude.smartaccount

import com.altude.core.TransactionBuilder
import com.altude.core.api.SendTransactionRequest
import com.altude.core.api.TransactionResponse
import com.altude.core.api.TransactionService
import com.altude.core.config.SdkConfig
import com.altude.core.model.CreateAccountOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

object AccountSDK {
    fun setApiKey(apiKey: String) {
        SdkConfig.setApiKey(apiKey)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun createAccount(
        options: CreateAccountOption
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val result = TransactionBuilder.CreateAccount(options)

            // Check if signing was successful
            if (result.isFailure) return@withContext result

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)

            // Suspend manually using suspendCoroutine
            val request = SendTransactionRequest( signedTransaction)
            suspendCancellableCoroutine<Result<String>> { cont ->
                service.createAccount(request)
                    .enqueue(object : Callback<TransactionResponse> {
                        override fun onResponse(
                            call: Call<TransactionResponse>,
                            response: Response<TransactionResponse>
                        ) {
                            val msg = response.body()?.message ?: "No message"
                            cont.resume(Result.success(msg), onCancellation = null)
                        }

                        override fun onFailure(call: Call<TransactionResponse>, t: Throwable) {
                            cont.resume(Result.failure(t), onCancellation = null)
                        }
                    })
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun closeAccount(
        options: CreateAccountOption
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val result = TransactionBuilder.CreateAccount(options)

            // Check if signing was successful
            if (result.isFailure) return@withContext result

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)

            // Suspend manually using suspendCoroutine
            val request = SendTransactionRequest( signedTransaction)
            suspendCancellableCoroutine<Result<String>> { cont ->
                service.sendTransaction(request)
                    .enqueue(object : Callback<TransactionResponse> {
                        override fun onResponse(
                            call: Call<TransactionResponse>,
                            response: Response<TransactionResponse>
                        ) {
                            val msg = response.body()?.message ?: "No message"
                            cont.resume(Result.success(msg), onCancellation = null)
                        }

                        override fun onFailure(call: Call<TransactionResponse>, t: Throwable) {
                            cont.resume(Result.failure(t), onCancellation = null)
                        }
                    })
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}