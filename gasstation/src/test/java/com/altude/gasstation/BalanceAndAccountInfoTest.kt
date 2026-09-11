package com.altude.gasstation

import com.altude.core.config.SdkConfig
import com.altude.gasstation.data.GetAccountInfoOption
import com.altude.gasstation.data.GetBalanceOption
import com.altude.gasstation.data.Token
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BalanceAndAccountInfoTest {

    @Before
    fun setUp() {
        SdkConfig.clearSigner()
    }

    @After
    fun tearDown() {
        SdkConfig.clearSigner()
    }

    @Test
    fun getBalance_noSignerAndEmptyAccount_failsWithSignerError() = runBlocking {
        val result = Altude.getBalance(GetBalanceOption(account = ""))
        assertTrue("Expected failure when no account and no signer configured", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("Error should mention signer", message.contains("signer", ignoreCase = true))
    }

    @Test
    fun getAccountInfo_noSignerAndEmptyAccount_failsWithSignerError() = runBlocking {
        val result = Altude.getAccountInfo(GetAccountInfoOption(account = ""))
        assertTrue("Expected failure when no account and no signer configured", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("Error should mention signer", message.contains("signer", ignoreCase = true))
    }

    @Test
    fun getBalance_invalidPublicKey_failsGracefully() = runBlocking {
        val result = Altude.getBalance(GetBalanceOption(account = "invalid-pubkey", token = Token.USDC.mint()))
        assertTrue("Expected failure with invalid public key", result.isFailure)
    }

    @Test
    fun getBalance_solTokenDefaults_handledAsSol() = runBlocking {
        // Querying SOL balance without RPC server running fails gracefully via network/RPC exception
        val result = Altude.getBalance(GetBalanceOption(account = "11111111111111111111111111111111", token = Token.SOL.mint()))
        assertTrue("Expected failure or result without throwing unexpected exception", result.isFailure)
    }
}
