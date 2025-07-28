package com.altude.gasstation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altude.core.config.SdkConfig
import com.altude.core.model.KeyPair
import com.altude.core.model.Token
import kotlinx.coroutines.runBlocking

import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    val ownerKey = byteArrayOf(
        235.toByte(), 144.toByte(), 12, 215.toByte(), 112, 178.toByte(), 249.toByte(), 227.toByte(), 180.toByte(), 112, 121, 214.toByte(), 13, 190.toByte(), 158.toByte(), 91,
        208.toByte(), 118, 253.toByte(), 192.toByte(), 48, 6, 252.toByte(), 37, 111, 169.toByte(), 209.toByte(), 238.toByte(), 174.toByte(), 78, 210.toByte(), 184.toByte(),
        9, 37, 75, 1, 98, 80, 44, 48, 119, 25, 193.toByte(), 156.toByte(), 161.toByte(), 185.toByte(), 250.toByte(), 119,
        160.toByte(), 54, 62, 93, 4, 130.toByte(), 200.toByte(), 226.toByte(), 100, 255.toByte(), 215.toByte(), 170.toByte(), 26, 226.toByte(), 213.toByte(), 28
    )
    @Test
    fun testTransferToken() = runBlocking {
        // 👇 Replace with your actual key
        val altude = GasStationSdk()
        altude.setApiKey("your_actual_api_key")
        //val ownerKepair = KeyPair.solanaKeyPairFromPrivateKey(ownerKey.copyOfRange(0,32))
        SdkConfig.setPrivateKey(ownerKey)
        val options = TransferOptions(
            //account = ownerKepair.publicKey.toBase58(),//"chenQmpQGpVwvFqGNqbJ8tGPxDYM97SF6jSDvLwdm4E",
            toAddress = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq",
            amount = 0.000001,
            token = Token.KIN.mint(),
        )

        // Wrap the callback in a suspendable way (like a suspendCoroutine)
        val result = altude.transfer(options)

        result
            .onSuccess { println("✅ Sent: ${it.signature}") }
            .onFailure {
                println("❌ Failed: ${it.message}")
            }

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