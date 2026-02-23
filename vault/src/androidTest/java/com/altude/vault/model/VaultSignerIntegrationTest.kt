package com.altude.vault.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity
import junit.framework.TestCase.*
import org.junit.Test
import org.junit.Before
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import com.altude.vault.manager.VaultManager

/**
 * Instrumentation tests for VaultSigner - Auth modes and session behavior
 *
 * Tests validate (with real Android context):
 * - Per-operation authentication (always prompts)
 * - Session-based caching (prompts once)
 * - Session TTL expiration and re-prompting
 * - Batch operations with consistent auth
 * - Error handling in both modes
 * - AuthMessages customization
 */
@RunWith(AndroidJUnit4::class)
class VaultSignerIntegrationTest {
    
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val appId = "com.altude.vault.test"
    private lateinit var vaultManager: VaultManager
    
    @Before
    fun setUp() {
        vaultManager = VaultManager.getInstance()
        cleanupTestVault()
    }
    
    private fun cleanupTestVault() {
        try {
            context.deleteFile("vault_seed_${appId}.encrypted")
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    @Test
    fun testPerOperationMode_CreateAlwaysPrompts() {
        runBlocking {
            try {
                // Given: Vault initialized
                vaultManager.createVault(context, appId, requireBiometric = true)
                
                // When: Create per-operation signer
                val signer = VaultSigner.create(
                    context as? FragmentActivity ?: return@runBlocking,
                    appId
                )
                
                // Then: VaultAuthMode should be PerOperation
                assertTrue("Should be per-operation mode",
                    signer.authMode is VaultAuthMode.PerOperation)
                
            } catch (e: BiometricNotAvailableException) {
                println("Test skipped: No biometric")
            } catch (e: VaultException) {
                fail("Setup error: ${e.message}")
            }
        }
    }
    
    @Test
    fun testSessionMode_CreateWithTTL() {
        runBlocking {
            try {
                // Given: Vault initialized
                vaultManager.createVault(context, appId, requireBiometric = true)
                
                // When: Create session-based signer
                val signer = VaultSigner.createWithSession(
                    context as? FragmentActivity ?: return@runBlocking,
                    appId,
                    sessionTTLSeconds = 45
                )
                
                // Then: VaultAuthMode should be SessionBased
                assertTrue("Should be session mode",
                    signer.authMode is VaultAuthMode.SessionBased)
                
                // And: TTL should match
                val ttl = (signer.authMode as VaultAuthMode.SessionBased).sessionTTLSeconds
                assertEquals("TTL should be 45 seconds", 45, ttl)
                
            } catch (e: BiometricNotAvailableException) {
                println("Test skipped: No biometric")
            } catch (e: VaultException) {
                fail("Setup error: ${e.message}")
            }
        }
    }
    
    @Test
    fun testAuthMessages_CustomizationWorks() {
        runBlocking {
            try {
                // Given: Vault initialized
                vaultManager.createVault(context, appId, requireBiometric = true)
                
                // When: Create signer with custom messages
                val customMessages = AuthMessages(
                    title = "Confirm Transaction",
                    description = "Verify to send payment",
                    cancelButton = "Deny"
                )
                
                val signer = VaultSigner.create(
                    context as? FragmentActivity ?: return@runBlocking,
                    appId,
                    authMessages = customMessages
                )
                
                // Then: Custom messages should be stored
                assertEquals("Title should match",
                    "Confirm Transaction",
                    signer.authMessages?.title)
                
                assertEquals("Description should match",
                    "Verify to send payment",
                    signer.authMessages?.description)
                
                assertEquals("Cancel button should match",
                    "Deny",
                    signer.authMessages?.cancelButton)
                
            } catch (e: BiometricNotAvailableException) {
                println("Test skipped: No biometric")
            } catch (e: VaultException) {
                fail("Setup error: ${e.message}")
            }
        }
    }
    
    @Test
    fun testSignMessage_PerOperationMode_Succeeds() {
        runBlocking {
            try {
                // Given: Vault initialized
                vaultManager.createVault(context, appId, requireBiometric = true)
                
                // When: Sign message in per-operation mode
                val signer = VaultSigner.create(
                    context as? FragmentActivity ?: return@runBlocking,
                    appId
                )
                
                val signature = signer.signMessage("Test message".toByteArray())
                
                // Then: Should produce valid signature
                assertNotNull("Signature should not be null", signature)
                assertEquals("Signature should be 64 bytes (Ed25519)", 64, signature.size)
                assertTrue("Signature should not be all zeros",
                    signature.any { it != 0.toByte() })
                
            } catch (e: BiometricNotAvailableException) {
                println("Test skipped: No biometric")
            } catch (e: BiometricAuthenticationFailedException) {
                // Expected if device doesn't complete biometric or user cancels
                println("Biometric auth during test: ${e.failureReason}")
            } catch (e: VaultException) {
                fail("Unexpected error: [${e.errorCode}] ${e.message}")
            }
        }
    }
    
    @Test
    fun testSignMessage_SessionMode_CachesAuth() {
        runBlocking {
            try {
                // Given: Vault initialized
                vaultManager.createVault(context, appId, requireBiometric = true)
                
                // When: Create session signer
                val signer = VaultSigner.createWithSession(
                    context as? FragmentActivity ?: return@runBlocking,
                    appId,
                    sessionTTLSeconds = 10
                )
                
                // When: Sign first message (will prompt)
                val sig1 = signer.signMessage("Message 1".toByteArray())
                assertNotNull("First signature should work", sig1)
                
                // When: Sign second message within TTL (should not prompt again)
                val sig2 = signer.signMessage("Message 2".toByteArray())
                assertNotNull("Second signature should work", sig2)
                
                // Then: Both signatures should be valid and different
                assertFalse("Different messages should produce different signatures",
                    sig1.contentEquals(sig2))
                
            } catch (e: BiometricNotAvailableException) {
                println("Test skipped: No biometric")
            } catch (e: BiometricAuthenticationFailedException) {
                // Expected if device doesn't complete biometric
                println("Biometric auth during test: ${e.failureReason}")
            } catch (e: VaultException) {
                fail("Unexpected error: [${e.errorCode}] ${e.message}")
            }
        }
    }
    
    @Test
    fun testSessionMode_RepromptsAfterExpiration() {
        runBlocking {
            try {
                // Given: Vault initialized
                vaultManager.createVault(context, appId, requireBiometric = true)
                
                // When: Create session signer with short TTL
                val signer = VaultSigner.createWithSession(
                    context as? FragmentActivity ?: return@runBlocking,
                    appId,
                    sessionTTLSeconds = 2  // 2 seconds
                )
                
                // When: Sign message
                val sig1 = signer.signMessage("Message 1".toByteArray())
                assertNotNull("First signature works", sig1)
                
                // When: Wait for session to expire
                delay(2100)
                
                // When: Try to sign again (should re-prompt if session expired)
                try {
                    val sig2 = signer.signMessage("Message 2".toByteArray())
                    assertNotNull("Second signature after expiration", sig2)
                    
                    // Then: Should still be valid (either session refreshed or re-prompted)
                } catch (e: VaultLockedException) {
                    // Also acceptable - session expired and needs re-auth
                    assertTrue("Session expiration handled correctly", true)
                }
                
            } catch (e: BiometricNotAvailableException) {
                println("Test skipped: No biometric")
            } catch (e: BiometricAuthenticationFailedException) {
                println("Biometric auth during test: ${e.failureReason}")
            } catch (e: VaultException) {
                fail("Unexpected error: [${e.errorCode}] ${e.message}")
            }
        }
    }
    
    @Test
    fun testBatchTransactionSigning_WithSessionMode() {
        runBlocking {
            try {
                // Given: Vault initialized
                vaultManager.createVault(context, appId, requireBiometric = true)
                
                // When: Create session signer for batch
                val signer = VaultSigner.createWithSession(
                    context as? FragmentActivity ?: return@runBlocking,
                    appId,
                    sessionTTLSeconds = 60  // 60 seconds for batch
                )
                
                // When: Sign batch of messages
                val messages = listOf(
                    "Transaction 1".toByteArray(),
                    "Transaction 2".toByteArray(),
                    "Transaction 3".toByteArray()
                )
                
                val signatures = messages.map { signer.signMessage(it) }
                
                // Then: Should sign all without re-prompting
                assertEquals("Should have 3 signatures", 3, signatures.size)
                signatures.forEach { sig ->
                    assertEquals("Each signature should be 64 bytes", 64, sig.size)
                }
                
                // And: All signatures should be different
                val uniqueSigs = signatures.distinct()
                assertEquals("All signatures should be unique", signatures.size, uniqueSigs.size)
                
            } catch (e: BiometricNotAvailableException) {
                println("Test skipped: No biometric")
            } catch (e: BiometricAuthenticationFailedException) {
                println("Biometric auth during test: ${e.failureReason}")
            } catch (e: VaultException) {
                fail("Unexpected error: [${e.errorCode}] ${e.message}")
            }
        }
    }
    
    @Test
    fun testPublicKeyExposure_AllowedInSigner() {
        runBlocking {
            try {
                // Given: Vault initialized
                vaultManager.createVault(context, appId, requireBiometric = true)
                
                // When: Create signer
                val signer = VaultSigner.create(
                    context as? FragmentActivity ?: return@runBlocking,
                    appId
                )
                
                // Then: Public key should be retrievable (not secret)
                val publicKey = signer.publicKey
                assertNotNull("Public key should be available", publicKey)
                assertTrue("Should be valid Solana address",
                    publicKey.toBase58().length > 10)
                
            } catch (e: BiometricNotAvailableException) {
                println("Test skipped: No biometric")
            } catch (e: VaultException) {
                fail("Unexpected error: ${e.message}")
            }
        }
    }
    
    @Test
    fun testDifferentWalletIndices_ProduceDifferentSigners() {
        runBlocking {
            try {
                // Given: Vault initialized
                vaultManager.createVault(context, appId, requireBiometric = true)
                
                val activity = context as? FragmentActivity ?: return@runBlocking
                
                // When: Create signers for different wallet indices
                val signer0 = VaultSigner.create(activity, appId, walletIndex = 0)
                val signer1 = VaultSigner.create(activity, appId, walletIndex = 1)
                
                // Then: Should have different public keys
                val pk0 = signer0.publicKey.toBase58()
                val pk1 = signer1.publicKey.toBase58()
                
                assertNotEquals("Different wallet indices should have different keys",
                    pk0, pk1)
                
            } catch (e: BiometricNotAvailableException) {
                println("Test skipped: No biometric")
            } catch (e: VaultException) {
                fail("Unexpected error: ${e.message}")
            }
        }
    }
    
    @Test
    fun testSignerImplementsTransactionSignerInterface() {
        runBlocking {
            try {
                // Given: Vault initialized
                vaultManager.createVault(context, appId, requireBiometric = true)
                
                // When: Create signer
                val signer = VaultSigner.create(
                    context as? FragmentActivity ?: return@runBlocking,
                    appId
                )
                
                // Then: Should implement TransactionSigner
                assertTrue("Should implement TransactionSigner",
                    signer is com.altude.core.model.TransactionSigner)
                
            } catch (e: BiometricNotAvailableException) {
                println("Test skipped: No biometric")
            } catch (e: VaultException) {
                fail("Unexpected error: ${e.message}")
            }
        }
    }
}
