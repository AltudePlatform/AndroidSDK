package com.altude.gasstation

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.altude.gasstation.data.CloseAccountOption
import com.altude.gasstation.data.CreateAccountOption
import com.altude.gasstation.data.GetAccountInfoOption
import com.altude.gasstation.data.GetBalanceOption
import com.altude.gasstation.data.GetHistoryOption
import com.altude.gasstation.data.SendOptions
import com.altude.core.helper.Mnemonic
import com.altude.gasstation.data.KeyPair
import com.altude.gasstation.data.Token
import com.altude.core.service.StorageService
import com.altude.gasstation.data.Commitment
import com.altude.gasstation.data.SwapOption
import kotlinx.coroutines.runBlocking
import org.junit.Before

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    private lateinit var service: StorageService
    private lateinit var context: Context
    val accountPrivateKey = byteArrayOf(
      0    )
    @Before
    fun setup()=runBlocking{
        context = InstrumentationRegistry.getInstrumentation().targetContext//ApplicationProvider.getApplicationContext()
        Altude.setApiKey(context,"ak_NfZS-i3gPQJH5FJ8lKFikRFXMkp3g1fX597A_uPAgdU")
    }

//    @Test
//    fun testRPC()=runBlocking{
//        val res = QuickNodeRpc("https://multi-ultra-frost.solana-devnet.quiknode.pro/417151c175bae42230bf09c1f87acda90dc21968/")
//        //res.getLatestBlockhash()
//        val ata = AssociatedTokenAccountProgram.deriveAtaAddress(PublicKey("EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq"), PublicKey(Token.KIN.mint()))
//        println("blockhash: ${res.getLatestBlockhash()}")
//        println("getAccountInfo: ${res.getAccountInfo(ata.toBase58()).value?.data?.parsed?.info}")
//        println("getMinimumBalanceForRentExemption: ${res.getMinimumBalanceForRentExemption(165.toULong())}")
//
//    }
    @Test
    fun testStorage()= runBlocking{
        Altude.saveMnemonic("")
        val seedData2 = StorageService.getDecryptedSeed("")
        assertEquals(seedData2?.mnemonic, "")

        Altude.savePrivateKey(accountPrivateKey)
        val seedData = StorageService.getDecryptedSeed("chenGqdufWByiUyxqg7xEhUVMqF3aS9sxYLSzDNmwqu")
        val decodedPrivateKey = seedData?.privateKey
        assert(accountPrivateKey.contentEquals(decodedPrivateKey))

        val list = StorageService.getDecryptedSeeds()
        assertEquals(list.count(), 2)
        StorageService.deleteWallet("chenGqdufWByiUyxqg7xEhUVMqF3aS9sxYLSzDNmwqu")
        val seedData3 = StorageService.getDecryptedSeed("chenGqdufWByiUyxqg7xEhUVMqF3aS9sxYLSzDNmwqu")
        assertEquals(seedData3, null)
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
        //Altude.saveMnemonic("size timber faint hip peasant dilemma priority woman dwarf market record fee")
        val keypair = KeyPair.generate()
        Altude.savePrivateKey(keypair.secretKey)
        val options = CreateAccountOption(
            account = keypair.publicKey.toBase58(),
            tokens = listOf(Token.KIN.mint()),
            commitment = Commitment.finalized,

            )
        Altude.storedWallet()
        // Wrap the callback in a suspendable way (like a suspendCoroutine)
        val result = Altude.createAccount(options)

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
        val closeresult = Altude.closeAccount(closeoptions)

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
        //Altude.saveMnemonic("size timber faint hip peasant dilemma priority woman dwarf market record fee")
        val keypair = Altude.generateKeyPair()
        Altude.savePrivateKey(keypair.secretKey)
        val options = CreateAccountOption(
            account = keypair.publicKey.toBase58(),
            tokens = listOf(Token.KIN.mint()),
            commitment = Commitment.finalized,

            )

        // Wrap the callback in a suspendable way (like a suspendCoroutine)
        val result = Altude.createAccount(options)

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
        Altude.saveMnemonic("")

        val options = CloseAccountOption(
            account = "BW9UiAzLfMTBrzUcMeLhpMUqhWZa3NMTLCF79dSStXuk",   //optional
            tokens  = listOf(Token.KIN.mint())
        )

        // Wrap the callback in a suspendable way (like a suspendCoroutine)
        val result = Altude.closeAccount(options)

        result
            .onSuccess { println("✅ Sent: $it") }
            .onFailure {
                println("❌ Failed: ${it.message}")
            }

        // Add an assert if needed
        assert(result.isSuccess)
    }

    @Test
    fun testStorageList() =runBlocking {

        //Altude.saveMnemonic("size timber faint hip peasant dilemma priority woman dwarf market record fee")
        val addresses =StorageService.listStoredWalletAddresses()
        println("addresses $addresses")
        assertEquals(1, addresses.size)

    }
    @Test
    fun testTransferToken() = runBlocking  {

        Altude.saveMnemonic("pause trial leisure wife deliver save crack sniff exact village claim upset")

        val options = SendOptions(
            account = "BG8ttfjfSdUVxJB5saKq59gfFdtpvDBeVTwg1X3ZBUyS", //optional
            toAddress = "6iyTeuuMzqKyGncSYQE7xbivtcTFMfS1aYM1t5xsyDU6",
            amount = 47749.11222,
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
        Altude.savePrivateKey(accountPrivateKey)
        //Altude.saveMnemonic("size timber faint hip peasant dilemma priority woman dwarf market record fee")

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
//            TransferOptions(
//                account = "chenGqdufWByiUyxqg7xEhUVMqF3aS9sxYLSzDNmwqu",
//                toAddress = "GRicVJoBc9Gxg7aqE11xAuSGej6Q2DAf1Wo72ggYzaSw",
//                amount = 0.00001,
//                token = Token.KIN.mint(),
//            )
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
        Altude.savePrivateKey(accountPrivateKey )
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
        //Altude.savePrivateKey(accountPrivateKey )
        Altude.saveMnemonic("")
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

//            val result2 = Altude.swap2(option)
//            result
//                .onSuccess { println("✅ Sent: ${it.Signature}") }
//                .onFailure {
//                    println("❌ Failed: ${it.message}")
//                }

        assert(result.isSuccess)
        assert(result.isSuccess)

    }


    @Test
    fun testGetAccountInfo() = runBlocking {
//        val pda1 = MPLCore.findTreeConfigPda(PublicKey("14QSPv5BtZCh8itGrUCu2j7e7A88fwZo3cAjxi4R5Fgj"))
        Altude.savePrivateKey(accountPrivateKey )
        val option = GetAccountInfoOption(
            account = "chenGqdufWByiUyxqg7xEhUVMqF3aS9sxYLSzDNmwqu"
        )
//
// println("account:pda, ${pda1.toBase58()}" )
//        // Wrap the callback in a suspendable way (like a suspendCoroutine)
        val result = Altude.getAccountInfo(option)
        println("getAccountInfo: $result")
//        println("account:pda, ${pda1.toBase58()}" )
//        println("account:14QSPv5BtZCh8itGrUCu2j7e7A88fwZo3cAjxi4R5Fgj, $result" )

//        val pda2 = MPLCore.findTreeConfigPda(PublicKey("7GzoPkZRSCaHvH3yYFpFTfm2pQGhdXZ8Tp1rTB3ughBb"))
//        val option2 = GetAccountInfoOption(
//            account = pda2.toBase58(),
//                useBase64 = true
//        )
//        println("account:pda, ${pda2.toBase58()}" )
//        // Wrap the callback in a suspendable way (like a suspendCoroutine)
//        val result2 = Altude.getAccountInfo(option2)
//        println("account:7GzoPkZRSCaHvH3yYFpFTfm2pQGhdXZ8Tp1rTB3ughBb, $result2")
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