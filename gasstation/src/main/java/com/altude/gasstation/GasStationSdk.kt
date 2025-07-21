package com.altude.gasstation

import com.altude.core.TransactionTransferBuilder
import com.altude.core.api.SendTransactionRequest
import com.altude.core.api.TransactionResponse
import com.altude.core.config.SdkConfig
import com.altude.core.api.TransactionService
import com.altude.core.model.SendOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Response
import retrofit2.Callback

data class TransferOptions (
    override val source: String,
    override val destination: String,
    override val amount: Double,
    val mintToken: Token
) : SendOptions {
    override val mint: String
    get() = mintToken.mint
}


enum class Token(val mint: String) {
    SOL("So11111111111111111111111111111111111111112"),
    USDT("Es9vMFraZcxrRTpQHsyc7DF1mz77XCxSY3vWS4Sxrt4"),
    USDC("EPjFWdd5AufqSSqeM2qSX2mVesERs85x3n5wjLWe1RtK"),
    LINK("2wpTofQ8SkACrk5xNdqk1ZUidPDZcN6h4FJfB9RkqWxN"),
    RNDR("RnwpAb5AF5JZKt4dFHb2wAHXQ2VuqsQvjA5PwMZKzp"),
    WIF("2gcXhWhChhAAXSw1esijF7LfopdSbCCGzG04wTtdBSS"),
    GRT("AZZre0B3UbkGwbUtEsqSiLDsDU2M7co26g7Egezheh"),
    BONK("8HXycfvhrtRpyL9WcnjxpQfQMsXik6jhbSahQXegaEg"),
    AR("6zHcd4Z3YAH8PfQ4Niupqv3MFf2kcKEDLRRUKn4rG"),
    PYTH("8mQo4EG4m4sEH9D5cFsw9mqqmZbofzjysA8HkRXsLtwY")
}

object GasStationSdk {

    fun setApiKey(apiKey: String) {
        SdkConfig.setApiKey(apiKey)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun transferToken(
        options: TransferOptions
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val result = TransactionTransferBuilder.TransferTokenTransaction(options)

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
