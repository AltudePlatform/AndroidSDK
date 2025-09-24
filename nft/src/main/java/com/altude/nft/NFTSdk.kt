package com.altude.nft

import android.content.Context
import com.altude.core.api.TransactionService
import com.altude.core.config.SdkConfig
import com.altude.core.data.CreateNFTCollectionOption
import com.altude.core.data.MintOption
import com.altude.nft.data.SendTransactionRequest
import com.altude.nft.interfaces.ITransactionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response



object NFTSdk {

    suspend fun setApiKey(context: Context, apiKey: String) {
        SdkConfig.setApiKey(context, apiKey)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun createNFTCollection(
        option: CreateNFTCollectionOption
    ): Result<ITransactionResponse> = withContext(Dispatchers.IO) {
        try {
            val result = TransactionManager.createCollectionNft(option)

            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = SendTransactionRequest(signedTransaction)

            suspendCancellableCoroutine { cont ->
                service.postCreateCollectionNft(request)
                    .enqueue(object : Callback<JsonElement> {
                        override fun onResponse(
                            call: Call<JsonElement>,
                            response: Response<JsonElement>
                        ) {
                            val body = response.body()
                            if (response.isSuccessful && body != null) {
                                cont.resume(Result.success(deCodeJson(body)), onCancellation = null)
                            } else {
                                val error = Throwable("Error: ${response.code()} - ${response.message()}")
                                cont.resume(Result.failure(error), onCancellation = null)
                            }
                        }
                        override fun onFailure(call: Call<JsonElement>, t: Throwable) {
                            cont.resume(Result.failure(t), onCancellation = null)
                        }
                    })
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun mint(
        option: MintOption
    ): Result<ITransactionResponse> = withContext(Dispatchers.IO) {
        try {
            val result = TransactionManager.mint(option)

            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = SendTransactionRequest(signedTransaction)

            suspendCancellableCoroutine { cont ->
                service.postMint(request)
                    .enqueue(object : Callback<JsonElement> {
                        override fun onResponse(
                            call: Call<JsonElement>,
                            response: Response<JsonElement>
                        ) {
                            val body = response.body()
                            if (response.isSuccessful && body != null) {
                                cont.resume(Result.success(deCodeJson(body)), onCancellation = null)
                            } else {
                                val error = Throwable("Error: ${response.code()} - ${response.message()}")
                                cont.resume(Result.failure(error), onCancellation = null)
                            }
                        }

                        override fun onFailure(call: Call<JsonElement>, t: Throwable) {
                            cont.resume(Result.failure(t), onCancellation = null)
                        }
                    })
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
    private inline fun <reified T> deCodeJson(jsonElement: JsonElement): T{
        return Json.decodeFromJsonElement<T>(jsonElement)
    }
}