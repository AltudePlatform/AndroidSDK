package com.altude.gasstation

import com.altude.gasstation.data.CloseAccountOption
import com.altude.gasstation.data.ComputeOptions
import com.altude.gasstation.data.CreateAccountOption
import com.altude.gasstation.data.SendOptions
import kotlinx.coroutines.runBlocking
import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {

    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }
    @Test
    fun testTransferTokenRealCall() = runBlocking {


        // Nothing to assert since result is printed inside the method.
        // Check log output for "✅ Token transfer sent" or "❌ Failed to send"
    }

    @Test
    fun computeOptions_defaultsAreApplied() {
        val defaults = ComputeOptions()
        assertEquals(400_000, defaults.computeUnitLimit)
        assertNull(defaults.computeUnitPriceMicroLamports)
        assertNull(defaults.heapFrameBytes)
    }

    @Test
    fun options_exposeComputeOptionsWithDefaults() {
        val send = SendOptions(toAddress = "test-address", amount = 1.0)
        val create = CreateAccountOption()
        val close = CloseAccountOption()

        assertEquals(400_000, send.computeOptions.computeUnitLimit)
        assertEquals(400_000, create.computeOptions.computeUnitLimit)
        assertEquals(400_000, close.computeOptions.computeUnitLimit)
    }
}
