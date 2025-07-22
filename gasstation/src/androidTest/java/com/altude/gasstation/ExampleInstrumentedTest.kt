package com.altude.gasstation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altude.core.api.SendTransactionRequest
import com.altude.core.api.TransactionResponse
import com.altude.core.api.TransactionService
import com.altude.core.config.SdkConfig
import com.altude.core.model.Token
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    @Test
    fun testTransferToken() = runBlocking {
        // 👇 Replace with your actual key
        GasStationSdk.setApiKey("your_actual_api_key")

        val options = TransferOptions(
            source = "chenGqdufWByiUyxqg7xEhUVMqF3aS9sxYLSzDNmwqu",//"chenQmpQGpVwvFqGNqbJ8tGPxDYM97SF6jSDvLwdm4E",
            destination = "BMRmo31USZuEga32JTk1Ub242JGcod982JtmynMK3fqv",
            amount = 0.0001,
            mintToken = Token.SOL
        )

        // Wrap the callback in a suspendable way (like a suspendCoroutine)
        val result = GasStationSdk.transferToken(options)

        result
            .onSuccess { println("✅ Sent: $it") }
            .onFailure { println("❌ Failed: ${it.message}") }

        // Add an assert if needed
        assert(result.isSuccess)
    }
//    @Test
//    fun testTransferToken() = runBlocking {
//        GasStationSdk.setApiKey("your_actual_api_key")
//        val service = SdkConfig.createService(TransactionService::class.java)
//
//        val request = SendTransactionRequest("test") // Use an actual base64-encoded transaction later
//        val response = service.sendTransaction(request).execute()
//
//        if (response.isSuccessful) {
//            println("✅ API response: ${response.body()?.message}")
//        } else {
//            println("❌ API error: ${response.errorBody()?.string()}")
//        }
//    }

}