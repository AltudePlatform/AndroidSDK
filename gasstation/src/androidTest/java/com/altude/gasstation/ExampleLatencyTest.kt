package com.altude.gasstation

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.altude.gasstation.data.CloseAccountOption
import com.altude.gasstation.data.Commitment
import com.altude.gasstation.data.CreateAccountOption
import com.altude.gasstation.data.SendOptions
import com.altude.gasstation.data.Token
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ExampleLatencyTest {
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
    suspend fun <T>measureLatency(label: String, action: suspend () -> T): Long {
        val start = System.currentTimeMillis()
        val result = action()
        val elapsed = System.currentTimeMillis() - start
        //println()
        //println("$label test took ${elapsed} ms")
        return elapsed
    }
    @Test
    fun testLatencySample() = runBlocking {

        val requestFrequency = 10
        val createAccountLatency = mutableListOf<Long>()
        val closeAccount = mutableListOf<Long>()
        val send = mutableListOf<Long>()

        for (i in 0..requestFrequency) { // includes requestFrequency
            createAccountLatency.add(measureLatency("createAccount"){
                val options = CreateAccountOption(
                    tokens = listOf(Token.USDC.mint()),
                    commitment = Commitment.finalized,
                )
                Altude .createAccount(options)
            })
            Thread.sleep(15000) // wait 15 seconds
            closeAccount.add(measureLatency("closeAccount"){
                val options = CloseAccountOption (
                    //account = keypair.publicKey.toBase58(),
                    tokens = listOf(Token.USDC.mint()),
                    commitment = Commitment.finalized,

                    )
                Altude .closeAccount(options)
            })
            Altude.savePrivateKey(accountPrivateKey)
            Thread.sleep(15000) // wait 15 seconds
            // Wrap the callback in a suspendable way (like a suspendCoroutine)

            send.add(measureLatency("send"){
                val options = SendOptions(
                    account = "chenGqdufWByiUyxqg7xEhUVMqF3aS9sxYLSzDNmwqu", //optional
                    toAddress = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq",
                    amount = 0.00001,
                    token = Token.KIN .mint(),
                    commitment =  Commitment.finalized
                )
                Altude.send(options)
            })
        }
        println("Create Account: $createAccountLatency")
        println("Average: ${createAccountLatency.average()} ms")
        println("Min: ${createAccountLatency.minOrNull()} ms")
        println("Max: ${createAccountLatency.maxOrNull()} ms")
        println("Close Account: $closeAccount")
        println("Average: ${closeAccount.average()} ms")
        println("Min: ${closeAccount.minOrNull()} ms")
        println("Max: ${closeAccount.maxOrNull()} ms")
        println("Send: $send")
        println("Average: ${send.average()} ms")
        println("Min: ${send.minOrNull()} ms")
        println("Max: ${send.maxOrNull()} ms")


        assertEquals(4, 2 + 2)
    }
}