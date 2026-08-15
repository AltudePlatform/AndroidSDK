package com.altude.android

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altude.core.model.HotSigner
import com.altude.gasstation.Altude
import com.altude.gasstation.data.CloseAccountOption
import com.altude.gasstation.data.Commitment
import com.altude.gasstation.data.CreateAccountOption
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
    fun setup()=runBlocking{
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // The app owns key custody: Altude.setApiKey requires a real TransactionSigner.
        // This placeholder signer is only used to satisfy initialization; individual
        // tests provide their own signer explicitly for the operations they exercise.
        val placeholderSigner = HotSigner(KeyPair.generate())
        Altude.setApiKey(appContext, "my_apikey", placeholderSigner)
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
}
