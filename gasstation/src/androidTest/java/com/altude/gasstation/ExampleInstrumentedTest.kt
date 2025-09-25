package com.altude.gasstation

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.altude.core.Programs.AssociatedTokenAccountProgram
import com.altude.core.Programs.MPLCore
import com.altude.gasstation.data.CloseAccountOption
import com.altude.gasstation.data.CreateAccountOption
import com.altude.gasstation.data.GetAccountInfoOption
import com.altude.gasstation.data.GetBalanceOption
import com.altude.gasstation.data.GetHistoryOption
import com.altude.gasstation.data.SendOptions
import com.altude.core.helper.Mnemonic
import com.altude.gasstation.data.KeyPair
import com.altude.gasstation.data.Token
import com.altude.core.network.QuickNodeRpc
import com.altude.core.service.StorageService
import com.altude.gasstation.data.Commitment
import foundation.metaplex.solanapublickeys.PublicKey
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
        235.toByte(), 144.toByte(), 12, 215.toByte(), 112, 178.toByte(), 249.toByte(), 227.toByte(), 180.toByte(), 112, 121, 214.toByte(), 13, 190.toByte(), 158.toByte(), 91,
        208.toByte(), 118, 253.toByte(), 192.toByte(), 48, 6, 252.toByte(), 37, 111, 169.toByte(), 209.toByte(), 238.toByte(), 174.toByte(), 78, 210.toByte(), 184.toByte(),
        9, 37, 75, 1, 98, 80, 44, 48, 119, 25, 193.toByte(), 156.toByte(), 161.toByte(), 185.toByte(), 250.toByte(), 119,
        160.toByte(), 54, 62, 93, 4, 130.toByte(), 200.toByte(), 226.toByte(), 100, 255.toByte(), 215.toByte(), 170.toByte(), 26, 226.toByte(), 213.toByte(), 28
    )
    @Before
    fun setup()=runBlocking{
        context = InstrumentationRegistry.getInstrumentation().targetContext//ApplicationProvider.getApplicationContext()
        Altude.setApiKey(context,"myAPIKey")
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
        Altude.saveMnemonic("bring record van away man person trouble clay rebuild review dust pond")
        val seedData2 = StorageService.getDecryptedSeed("BjLvdmqDjnyFsewJkzqPSfpZThE8dGPqCAZzVbJtQFSr")
        assertEquals(seedData2?.mnemonic, "bring record van away man person trouble clay rebuild review dust pond")

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
        val keypair =  KeyPair.solanaKeyPairFromMnemonic("bring record van away man person trouble clay rebuild review dust pond")
        assertEquals(keypair.publicKey.toBase58(), "BjLvdmqDjnyFsewJkzqPSfpZThE8dGPqCAZzVbJtQFSr")

        val keypair2 =  KeyPair.solanaKeyPairFromMnemonic("size timber faint hip peasant dilemma priority woman dwarf market record fee")
        assertEquals(keypair2.publicKey.toBase58(), "ALZ8NJcf8JDL7j7iVfoyXM8u3fT3DoBXsnAU6ML7Sb5W")

        val keypair3 =  KeyPair.solanaKeyPairFromMnemonic("profit admit clean purchase wagon cake cattle they favorite diamond rigid present twin devote busy rack float catch route menu short beyond inherit sight")
        assertEquals(keypair3.publicKey.toBase58(), "GRicVJoBc9Gxg7aqE11xAuSGej6Q2DAf1Wo72ggYzaSw")

        val keypair4 =  KeyPair.solanaKeyPairFromMnemonic("wisdom hospital stable flavor payment slice cannon dirt galaxy capital side hunt parent surprise rate upon jaguar ketchup keen swim mammal kite net omit")
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

        // Wrap the callback in a suspendable way (like a suspendCoroutine)
        val result = Altude.createAccount(options)

        result
            .onSuccess {
                println("✅ Sent: $it")
                assert(it.signature.isNotEmpty())
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
                assert(it.signature.isNotEmpty())
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
        Altude.saveMnemonic("size timber faint hip peasant dilemma priority woman dwarf market record fee")

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

        Altude.savePrivateKey(accountPrivateKey)

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
            .onSuccess { println("✅ Sent: ${it.signature}") }
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
            .onSuccess { println("✅ Sent: ${it.signature}") }
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