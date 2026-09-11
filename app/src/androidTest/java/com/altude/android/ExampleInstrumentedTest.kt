package com.altude.android

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altude.core.model.HotSigner
import com.altude.gasstation.Altude
import com.altude.gasstation.data.CloseAccountOption
import com.altude.gasstation.data.Commitment
import com.altude.gasstation.data.CreateAccountOption
import com.altude.gasstation.data.GetBalanceOption
import com.altude.gasstation.data.KeyPair
import com.altude.gasstation.data.Token
import kotlinx.coroutines.runBlocking

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*
import org.junit.Before

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Before
    fun setup() {
        runBlocking {
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            // Use an explicit signer here so each test has deterministic key material.
            val placeholderSigner = HotSigner(KeyPair.generate())
            Altude.setApiKey(appContext, "", placeholderSigner)
        }
    }
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.altude.android", appContext.packageName)
    }

    @Test
    fun testCreateandCloseAccount() = runBlocking {
        val keypair = KeyPair.generate()
        val signer = HotSigner(keypair)
        val options = CreateAccountOption(
            account = keypair.publicKey.toBase58(),
            tokens = listOf(Token.KIN.mint()),
            commitment = Commitment.finalized,

            )

        // Wrap the callback in a suspendable way (like a suspendCoroutine)
        val result = Altude.createAccount(options, signer)

        result
            .onSuccess {
                println("✅ Sent: $it")
                assert(it.Signature.isNotEmpty())
            }
            .onFailure {
                println("❌ Failed: ${it.message}")
            }

        // Add an assert if needed
        assert(result.isSuccess)
        Thread.sleep(15000) // wait 2 seconds

        val closeoptions = CloseAccountOption(
            account = keypair.publicKey.toBase58(),   //optional
            tokens = listOf(Token.KIN.mint())
        )

        // Wrap the callback in a suspendable way (like a suspendCoroutine)
        val closeresult = Altude.closeAccount(closeoptions, signer)

        closeresult
            .onSuccess {
                println("✅ Sent: $it")
                assert(it.Signature.isNotEmpty())
            }
            .onFailure {
                println("❌ Failed: ${it.message}")
            }

        // Add an assert if needed
        assert(closeresult.isSuccess)
    }

    @Test
    fun testBalance() = runBlocking {
        val options = GetBalanceOption(
            account = "ALTn7gyjm29WthZGgs4z6WVAK2PK5U6w4FAtPg3TPY71",
            //token = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU",
            )

        // Wrap the callback in a suspendable way (like a suspendCoroutine)
        val result = Altude.getBalance(options)

        result
            .onSuccess {
                println("✅ Sent: $it")
            }
            .onFailure {
                println("❌ Failed: ${it.message}")
            }

        // Add an assert if needed
        assertTrue("Expected balance query to succeed", result.isSuccess)

    }
}
