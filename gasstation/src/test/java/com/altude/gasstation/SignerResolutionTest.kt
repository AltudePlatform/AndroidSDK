package com.altude.gasstation

import com.altude.core.config.SdkConfig
import com.altude.core.model.TransactionSigner
import foundation.metaplex.solanapublickeys.PublicKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the internal [resolveSigner] function used by [GaslessManager].
 *
 * Initialization registers a Core-managed or custom [TransactionSigner]. These tests pin down
 * operation-time resolution: an explicit per-call override always wins over the configured
 * signer, and a clear error is thrown when neither is available.
 */
class SignerResolutionTest {

    private class FakeTransactionSigner(
        override val publicKey: PublicKey,
        private val signature: ByteArray = ByteArray(64)
    ) : TransactionSigner {
        override suspend fun signMessage(message: ByteArray): ByteArray = signature
    }

    private val overrideSigner = FakeTransactionSigner(PublicKey("11111111111111111111111111111111"))
    private val configuredSigner = FakeTransactionSigner(PublicKey("ComputeBudget111111111111111111111111111111"))

    @Before
    fun setUp() {
        SdkConfig.clearSigner()
    }

    @After
    fun tearDown() {
        SdkConfig.clearSigner()
    }

    @Test
    fun override_takesPrecedenceOverConfiguredSigner() {
        SdkConfig.setSigner(configuredSigner)

        val resolved = resolveSigner(overrideSigner)

        assertEquals(
            "The per-call override must win even when a signer is configured",
            overrideSigner,
            resolved
        )
    }

    @Test
    fun noOverride_fallsBackToConfiguredSigner() {
        SdkConfig.setSigner(configuredSigner)

        val resolved = resolveSigner(null)

        assertEquals(
            "With no override, the configured SdkConfig signer must be used",
            configuredSigner,
            resolved
        )
    }

    @Test
    fun noOverrideAndNoConfiguredSigner_throwsClearError() {
        // Neither an override nor a configured signer is present.
        val exception = assertThrows(IllegalArgumentException::class.java) {
            resolveSigner(null)
        }

        assertEquals(
            "No signer configured. Call AltudeGasStation.init() or Altude.setApiKey() " +
                "before using SDK methods.",
            exception.message
        )
    }
}
