package com.altude.gasstation

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altude.core.data.CloseAccountOption
import com.altude.core.data.CreateAccountOption
import com.altude.core.data.GetAccountInfoOption
import com.altude.core.data.GetBalanceOption
import com.altude.core.data.GetHistoryOption
import com.altude.core.data.TransferOptions
import com.altude.core.helper.Mnemonic
import com.altude.core.model.KeyPair
import com.altude.core.model.Token
import com.altude.core.service.StorageService
import foundation.metaplex.rpc.Commitment
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
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        Altude.setApiKey(context,"myAPIKey")
    }

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
    fun testCreateAccount() = runBlocking {
        Altude.saveMnemonic("size timber faint hip peasant dilemma priority woman dwarf market record fee")

        val options = CreateAccountOption(
            //owner = ownerKepair.publicKey.toBase58(),
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

        Altude.saveMnemonic("size timber faint hip peasant dilemma priority woman dwarf market record fee")

        val options = CloseAccountOption(
            account = "",   //optional
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
    fun testTransferToken() = runBlocking {
        Altude.savePrivateKey(accountPrivateKey)

        val options = TransferOptions(
            account = "", //optional
            toAddress = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq",
            amount = 0.00001,
            token = Token.KIN.mint(),
        )

        // Wrap the callback in a suspendable way (like a suspendCoroutine)
        val result = Altude.transfer(options)

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
        Altude.saveMnemonic("size timber faint hip peasant dilemma priority woman dwarf market record fee")

        val options =listOf(
//            TransferOptions(
//                toAddress = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq",
//                amount = 0.00001,
//                token = Token.KIN.mint(),
            //),
            TransferOptions(
                account = "ALZ8NJcf8JDL7j7iVfoyXM8u3fT3DoBXsnAU6ML7Sb5W",
                toAddress = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq",
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
        val result = Altude.batchtransfer(options)

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

        val option = GetBalanceOption(
            token = "KinDesK3dYWo3R2wDk6Ucaf31tvQCCSYyL8Fuqp33GX"
        )

        // Wrap the callback in a suspendable way (like a suspendCoroutine)
        val result = Altude.getBalance(option)
        println(result)
    }
    @Test
    fun testGetAccountInfo() = runBlocking {
        val option = GetAccountInfoOption(
            account = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq"
        )

        // Wrap the callback in a suspendable way (like a suspendCoroutine)
        val result = Altude.getAccountInfo(option)
        println(result)
    }

    @Test
    fun testGetHistory() = runBlocking {

        val options = GetHistoryOption(
            account = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq",
            limit = 1,
            offset =2
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