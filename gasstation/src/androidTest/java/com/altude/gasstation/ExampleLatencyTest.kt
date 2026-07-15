package com.altude.gasstation

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.altude.gasstation.data.CloseAccountOption
import com.altude.gasstation.data.Commitment
import com.altude.gasstation.data.CreateAccountOption
import com.altude.gasstation.data.GetHistoryOption
import com.altude.gasstation.data.SendOptions
import com.altude.gasstation.data.Token
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ExampleLatencyTest {
    private lateinit var context: Context
    val accountPrivateKey = byteArrayOf()
    @Before
    fun setup()=runBlocking{
        context = InstrumentationRegistry.getInstrumentation().targetContext//ApplicationProvider.getApplicationContext()
        Altude.setApiKey(context,"")
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

    @Test
    fun tesGetHistorytLatencySample() = runBlocking {

        val requestFrequency = 10
        val getHistory = mutableListOf<Long>()

        for (i in 0..requestFrequency) { // includes requestFrequency
            getHistory.add(measureLatency("createAccount"){
                val options = GetHistoryOption(
                    limit = 100,
                    offset = 0,
                    walletAddress = "chenGqdufWByiUyxqg7xEhUVMqF3aS9sxYLSzDNmwqu",
                    account = "chenGqdufWByiUyxqg7xEhUVMqF3aS9sxYLSzDNmwqu",
                )
                Altude .getHistory(options)
            })

        }
        println("Create Account: $getHistory")
        println("Average: ${getHistory.average()} ms")
        println("Min: ${getHistory.minOrNull()} ms")
        println("Max: ${getHistory.maxOrNull()} ms")
        assertEquals(4, 2 + 2)
    }
}