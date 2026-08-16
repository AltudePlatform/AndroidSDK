package com.altude.core.config

import com.altude.core.model.TransactionSigner
import foundation.metaplex.solanapublickeys.PublicKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SdkConfig]'s signer registry.
 *
 * Gas Station initialization registers a Core-managed or custom [TransactionSigner] through
 * [SdkConfig.setSigner]. These tests validate the registry behavior: `clearSigner()` removes
 * the current signer and later calls to `setSigner()` replace it.
 */
class SignerRoutingTest {

    /** Minimal fake signer — no Vault/HotSigner dependency needed for these tests. */
    private class FakeTransactionSigner(
        override val publicKey: PublicKey,
        private val signature: ByteArray = ByteArray(64)
    ) : TransactionSigner {
        override suspend fun signMessage(message: ByteArray): ByteArray = signature
    }

    private val signerA = FakeTransactionSigner(PublicKey("11111111111111111111111111111111"))
    private val signerB = FakeTransactionSigner(PublicKey("ComputeBudget111111111111111111111111111111"))

    @Before
    fun setUp() {
        // Ensure a clean slate — SdkConfig is a process-wide singleton.
        SdkConfig.clearSigner()
    }

    @After
    fun tearDown() {
        SdkConfig.clearSigner()
    }

    @Test
    fun currentSigner_isNullByDefault() {
        assertNull("No signer should be configured until the app explicitly sets one", SdkConfig.currentSigner)
    }

    @Test
    fun setSigner_updatesCurrentSigner() {
        SdkConfig.setSigner(signerA)

        assertEquals("Should reflect the app-provided signer", signerA, SdkConfig.currentSigner)
    }

    @Test
    fun setSigner_calledAgain_replacesPreviousSigner() {
        SdkConfig.setSigner(signerA)
        SdkConfig.setSigner(signerB)

        assertEquals("Last signer set should be active", signerB, SdkConfig.currentSigner)
        assertNotEquals("Previous signer should no longer be active", signerA, SdkConfig.currentSigner)
    }

    @Test
    fun clearSigner_removesConfiguredSigner() {
        SdkConfig.setSigner(signerA)
        assertEquals(signerA, SdkConfig.currentSigner)

        SdkConfig.clearSigner()

        assertNull("Signer should be cleared", SdkConfig.currentSigner)
    }
}
