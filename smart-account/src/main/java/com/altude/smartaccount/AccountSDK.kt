package com.altude.smartaccount

import com.altude.core.TransactionManager
import com.altude.core.api.SendTransactionRequest
import com.altude.core.api.TransactionResponse
import com.altude.core.api.TransactionService
import com.altude.core.config.SdkConfig
import com.altude.core.data.CloseAccountOption
import com.altude.core.data.CreateAccountOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class AccountSDK (){
    fun setApiKey(apiKey: String) {
        SdkConfig.setApiKey(apiKey)
    }
    fun set(apiKey: String) {
        SdkConfig.setApiKey(apiKey)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun createAccount(
        options: CreateAccountOption
    ): Result<TransactionResponse> = withContext(Dispatchers.IO) {
        try {
            val result = TransactionManager.createAccount(options)

            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = SendTransactionRequest(signedTransaction)

            suspendCancellableCoroutine<Result<TransactionResponse>> { cont ->
                service.createAccount(request)
                    .enqueue(object : Callback<TransactionResponse> {
                        override fun onResponse(
                            call: Call<TransactionResponse>,
                            response: Response<TransactionResponse>
                        ) {
                            val body = response.body()
                            if (response.isSuccessful && body != null) {
                                cont.resume(Result.success(body), onCancellation = null)
                            } else {
                                val error = Throwable("Error: ${response.code()} - ${response.message()}")
                                cont.resume(Result.failure(error), onCancellation = null)
                            }
                        }

                        override fun onFailure(call: Call<TransactionResponse>, t: Throwable) {
                            cont.resume(Result.failure(t), onCancellation = null)
                        }
                    })
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun closeAccount(
        options: CloseAccountOption
    ): Result<TransactionResponse> = withContext(Dispatchers.IO) {
        try {
            val result = TransactionManager.closeAccount(options)

            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = SendTransactionRequest(signedTransaction)

            suspendCancellableCoroutine<Result<TransactionResponse>> { cont ->
                service.closeAccount(request)
                    .enqueue(object : Callback<TransactionResponse> {
                        override fun onResponse(
                            call: Call<TransactionResponse>,
                            response: Response<TransactionResponse>
                        ) {
                            val body = response.body()
                            if (response.isSuccessful && body != null) {
                                cont.resume(Result.success(body), onCancellation = null)
                            } else {
                                val error = Throwable("Error: ${response.code()} - ${response.message()}")
                                cont.resume(Result.failure(error), onCancellation = null)
                            }
                        }

                        override fun onFailure(call: Call<TransactionResponse>, t: Throwable) {
                            cont.resume(Result.failure(t), onCancellation = null)
                        }
                    })
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

}