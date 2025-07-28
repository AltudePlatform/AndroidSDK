package com.altude.gasstation

import com.altude.core.TransactionManager
import com.altude.core.api.SendTransactionRequest
import com.altude.core.api.TransactionResponse
import com.altude.core.config.SdkConfig
import com.altude.core.api.TransactionService
import com.altude.core.data.SendOptions
import foundation.metaplex.rpc.Commitment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Response
import retrofit2.Callback

data class TransferOptions (
    override val account: String = "",
    override val toAddress: String,
    override val amount: Double,
    override val token: String,
    override val commitment: Commitment = Commitment.finalized
) : SendOptions {

}




class GasStationSdk {

    fun setApiKey(apiKey: String) {
        SdkConfig.setApiKey(apiKey)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun transfer(
        options: TransferOptions
    ): Result<TransactionResponse> = withContext(Dispatchers.IO) {
        try {
            val result = TransactionManager.TransferToken(options)

            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = SendTransactionRequest(signedTransaction)

            suspendCancellableCoroutine<Result<TransactionResponse>> { cont ->
                service.sendTransaction(request)
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
