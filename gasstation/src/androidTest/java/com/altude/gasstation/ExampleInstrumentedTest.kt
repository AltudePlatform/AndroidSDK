package com.altude.gasstation

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.altude.core.helper.Mnemonic
import com.altude.core.model.HotSigner
import com.altude.core.service.StorageService
import com.altude.gasstation.data.CloseAccountOption
import com.altude.gasstation.data.Commitment
import com.altude.gasstation.data.CreateAccountOption
import com.altude.gasstation.data.GetAccountInfoOption
import com.altude.gasstation.data.GetBalanceOption
import com.altude.gasstation.data.GetHistoryOption
import com.altude.gasstation.data.KeyPair
import com.altude.gasstation.data.SendOptions
import com.altude.gasstation.data.SwapOption
import com.altude.gasstation.data.Token
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * These are manual/live-network smoke tests that hit a real Altude API and Solana devnet.
 * The app owns key custody: every test that needs to sign a transaction constructs its own
 * [HotSigner] and passes it explicitly (either as the operation's `signer` argument or via
 * [Altude.setApiKey]). There is no SDK-owned storage/mnemonic fallback.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setup()=runBlocking{
        context = InstrumentationRegistry.getInstrumentation().targetContext//ApplicationProvider.getApplicationContext()
        // Use an explicit signer here so each test has deterministic key material.
        val placeholderSigner = HotSigner(KeyPair.generate())
        Altude.setApiKey(context, "", placeholderSigner)
    }

    @Test
    fun testMnemonicToKeyPair()= runBlocking{
        val keypair =  KeyPair.solanaKeyPairFromMnemonic("")
        assertEquals(keypair.publicKey.toBase58(), "BjLvdmqDjnyFsewJkzqPSfpZThE8dGPqCAZzVbJtQFSr")

        val keypair2 =  KeyPair.solanaKeyPairFromMnemonic("")
        assertEquals(keypair2.publicKey.toBase58(), "ALZ8NJcf8JDL7j7iVfoyXM8u3fT3DoBXsnAU6ML7Sb5W")

        val keypair3 =  KeyPair.solanaKeyPairFromMnemonic("")
        assertEquals(keypair3.publicKey.toBase58(), "GRicVJoBc9Gxg7aqE11xAuSGej6Q2DAf1Wo72ggYzaSw")

        val keypair4 =  KeyPair.solanaKeyPairFromMnemonic("")
        assertEquals(keypair4.publicKey.toBase58(), "HPzZhuj27KQLpAJqS4QZAeHpz4c1t5fgihzXXoFsSTDo")
    }

    @Test
    fun testGenerateMnemonic()= runBlocking{
        val mnemonic = Mnemonic.generateMnemonic(24)
        println(mnemonic)
        val mnemonic3 = Mnemonic(mnemonic)
        val keypair3 =  mnemonic3.getKeyPair()
        println(keypair3)
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
        Altude.storedWallet()
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
    fun testCreateAccount() = runBlocking {
        val keypair = Altude.generateKeyPair()
        val signer = HotSigner(keypair)
        val options = CreateAccountOption(
            account = keypair.publicKey.toBase58(),
            tokens = listOf(Token.KIN.mint()),
            commitment = Commitment.finalized,

            )

        // Wrap the callback in a suspendable way (like a suspendCoroutine)
        val result = Altude.createAccount(options, signer)

        result
            .onSuccess { println("✅ Sent: $it") }
            .onFailure {
                println("❌ Failed: ${it.message}")
            }

        // Add an assert if needed
        assert(result.isSuccess)
    }

    @Test
    fun testCloseAccount() = runBlocking {

        StorageService.listStoredWalletAddresses()

        val options = CloseAccountOption(
            account = "BW9UiAzLfMTBrzUcMeLhpMUqhWZa3NMTLCF79dSStXuk",   //optional
            tokens  = listOf(Token.KIN.mint())
        )

        // Wrap the callback in a suspendable way (like a suspendCoroutine)
        // No signer override is supplied here: this call relies on the signer configured
        // in setup(), which will not match `account`. This is expected to fail fast with a
        // clear "signer public key does not match requested account" error rather than
        // silently signing with the wrong key.
        val result = Altude.closeAccount(options)

        result
            .onSuccess { println("✅ Sent: $it") }
            .onFailure {
                println("❌ Failed: ${it.message}")
            }
    }

    @Test
    fun testStorageList() =runBlocking {
        val addresses = StorageService.listStoredWalletAddresses()
        println("addresses $addresses")
    }
    @Test
    fun testTransferToken() = runBlocking  {

        val options = SendOptions(
            account = "chenGqdufWByiUyxqg7xEhUVMqF3aS9sxYLSzDNmwqu", //optional
            toAddress = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq",
            amount = 0.00001,
            token = Token.KIN .mint(),
            commitment =  Commitment.finalized
        )

        // Wrap the callback in a suspendable way (like a suspendCoroutine)
        val result = Altude.send(options)

        result
            .onSuccess { println("✅ Sent: ${it.Signature}") }
            .onFailure {
                println("❌ Failed: ${it.message}")
            }

        // Add an assert if needed
        assert(result.isSuccess)
    }
    @Test
    fun testBatchTransferToken() = runBlocking {
        val options =listOf(
            SendOptions(
                account = "chenGqdufWByiUyxqg7xEhUVMqF3aS9sxYLSzDNmwqu",
                toAddress = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq",
                amount = 0.00001,
                token = Token.KIN.mint(),
            ),
            SendOptions(
                account = "chenGqdufWByiUyxqg7xEhUVMqF3aS9sxYLSzDNmwqu",
                toAddress = "ALZ8NJcf8JDL7j7iVfoyXM8u3fT3DoBXsnAU6ML7Sb5W",
                amount = 0.00001,
                token = Token.KIN.mint(),
            ),
        )

        // Wrap the callback in a suspendable way (like a suspendCoroutine)
        val result = Altude.sendBatch(options)

        result
            .onSuccess { println("✅ Sent: ${it.Signature}") }
            .onFailure {
                println("❌ Failed: ${it.message}")
            }

        // Add an assert if needed
        assert(result.isSuccess)
    }

    @Test
    fun testGetBalance() = runBlocking {
        val option = GetBalanceOption(
            account = "chenGqdufWByiUyxqg7xEhUVMqF3aS9sxYLSzDNmwqu",
            token = Token.KIN.mint()
        )

        // Wrap the callback in a suspendable way (like a suspendCoroutine)
        val result = Altude.getBalance(option)
        println("Balance: $result")
    }

    @Test
    fun testSwap() = runBlocking {
        val option = SwapOption(
            account = "BG8ttfjfSdUVxJB5saKq59gfFdtpvDBeVTwg1X3ZBUyS",
            inputMint = Token.SOL.mint(),
            outputMint = Token.USDC.mint(),
            amount = 0.000001,
            commitment = Commitment.finalized,
            //slippageBps = 50
        )

        // Wrap the callback in a suspendable way (like a suspendCoroutine)
        val result = Altude.swap(option)
        result
            .onSuccess { println("✅ Sent: ${it.Signature}") }
            .onFailure {
                println("❌ Failed: ${it.message}")
            }
    }


    @Test
    fun testGetAccountInfo() = runBlocking {
        val option = GetAccountInfoOption(
            account = "chenGqdufWByiUyxqg7xEhUVMqF3aS9sxYLSzDNmwqu"
        )
        val result = Altude.getAccountInfo(option)
        println("getAccountInfo: $result")
    }

    @Test
    fun testGetHistory() = runBlocking {

        val options = GetHistoryOption(
            account = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq",
            limit = 1,
            offset = 2,
            walletAddress = ""
        )

        // Wrap the callback in a suspendable way (like a suspendCoroutine)
        val result = Altude.getHistory(options)

        result
            .onSuccess { println("✅ Sent: $it") }
            .onFailure {
                println("❌ Failed: ${it.message}")
            }

        // Add an assert if needed
        assert(result.isSuccess)
    }

}
