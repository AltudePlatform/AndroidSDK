package com.altude.gasstation

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    @Test
    suspend fun useAppContext() {
        // Context of the app under test.
//        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
//        assertEquals("com.altude.gasstation.test", appContext.packageName)

        // 👇 Replace this with a real key
        GasStationSdk.setApiKey("your_actual_api_key")

        val options = TransferOptions(
            source = "chenQmpQGpVwvFqGNqbJ8tGPxDYM97SF6jSDvLwdm4E",
            destination = "BMRmo31USZuEga32JTk1Ub242JGcod982JtmynMK3fqv",
            amount = 0.0001,
            mintToken = Token.SOL
        )

        GasStationSdk.transferToken(options){ result ->
            result
                .onSuccess { println("✅ Sent: $it") }
                .onFailure { println("❌ Failed: ${it.message}") }
        }
    }
}