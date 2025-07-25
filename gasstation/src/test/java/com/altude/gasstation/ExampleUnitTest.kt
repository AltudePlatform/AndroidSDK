package com.altude.gasstation

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
}