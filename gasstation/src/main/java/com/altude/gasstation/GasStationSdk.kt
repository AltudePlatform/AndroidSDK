package com.altude.gasstation

import com.altude.core.TransactionBuilder
import com.altude.core.api.SendTransactionRequest
import com.altude.core.api.TransactionResponse
import com.altude.core.config.SdkConfig
import com.altude.core.api.TransactionService
import com.altude.core.`interface`.SendOptions
import com.altude.core.model.SolanaKeypair
import com.altude.core.model.Token
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Response
import retrofit2.Callback

data class TransferOptions (
    override val owner: SolanaKeypair,
    override val destination: String,
    override val amount: Double,
    val mintToken: Token
) : SendOptions {
    override val mint: String
    get() = mintToken.mint
}




class GasStationSdk {

    fun setApiKey(apiKey: String) {
        SdkConfig.setApiKey(apiKey)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun transferToken(
        options: TransferOptions
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val result = TransactionBuilder.TransferToken(options)

            // Check if signing was successful
            if (result.isFailure) return@withContext result

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)

            // Suspend manually using suspendCoroutine
            val request =  SendTransactionRequest( signedTransaction)
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
