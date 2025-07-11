package com.altude.gasstation

import com.altude.core.TransactionTransferBuilder
import com.altude.core.api.SignedTransactionRequest
import com.altude.core.config.SdkConfig
import com.altude.core.api.TransactionService
import com.altude.core.crypto.KeyUtils
import java.security.PrivateKey
import com.altude.core.model.SendOptions

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
        SdkConfig.initialize("")
    }

    suspend fun transferToken(
        options: TransferOptions
    ) {

        val singedTransaction = TransactionTransferBuilder.TransferTokenTransaction(options)

        val service = SdkConfig.createService(TransactionService::class.java)

        service.sendTransaction(singedTransaction).enqueue(object : retrofit2.Callback<com.altude.core.api.TransactionResponse> {
            override fun onResponse(
                call: retrofit2.Call<com.altude.core.api.TransactionResponse>,
                response: retrofit2.Response<com.altude.core.api.TransactionResponse>
            ) {
                println("✅ Token transfer sent: ${response.body()?.message}")
            }

            override fun onFailure(
                call: retrofit2.Call<com.altude.core.api.TransactionResponse>,
                t: Throwable
            ) {
                println("❌ Failed to send: ${t.message}")
            }
        })
    }
}
