package com.altude.smartaccount

import android.media.session.MediaSession
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altude.core.model.CreateAccountOption
import com.altude.core.model.Token
import kotlinx.coroutines.runBlocking

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
    fun testCreateAccount() = runBlocking {
        // 👇 Replace with your actual key
        AccountSDK.setApiKey("your_actual_api_key")

        val options = CreateAccountOption(
            owner = "chenQmpQGpVwvFqGNqbJ8tGPxDYM97SF6jSDvLwdm4E",
            mint  = Token.USDT.mint
        )

        // Wrap the callback in a suspendable way (like a suspendCoroutine)
        val result = AccountSDK.createAccount(options)

        result
            .onSuccess { println("✅ Sent: $it") }
            .onFailure { println("❌ Failed: ${it.message}") }

        // Add an assert if needed
        assert(result.isSuccess)
    }
}