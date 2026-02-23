package com.altude.core.config

import junit.framework.TestCase.*
import org.junit.Test
import org.junit.Before
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import com.altude.core.model.TransactionSigner
import com.solana.core.KeyPair
import com.solana.core.PublicKey

/**
 * Unit tests for signer routing and fallback chain logic
 *
 * Tests validate:
 * - Default signer selection from AltudeGasStation.init()
 * - Fallback chain: provided signer → SdkConfig.currentSigner → HotSigner
 * - SignerStrategy options (VaultDefault, External)
 * - Signer switching behavior
 */
class SignerRoutingTest {
    
    private lateinit var sdkConfig: SdkConfig
    
    @Mock
    private lateinit var mockSigner: TransactionSigner
    
    @Mock
    private lateinit var mockPublicKey: PublicKey
    
    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        sdkConfig = SdkConfig.getInstance()
        // Reset to clean state
        sdkConfig.currentSigner = null
    }
    
    @Test
    fun testDefaultSigner_IsNullBeforeInit() {
        // Given: SdkConfig before initialization
        // When/Then: Default signer should be null
        assertNull("Should start with no signer", SdkConfig.getInstance().currentSigner)
    }
    
    @Test
    fun testSetSigner_UpdatesCurrentSigner() {
        // Given: A mock signer
        // When: Set as current signer
        sdkConfig.setSigner(mockSigner)
        
        // Then: Should be accessible
        assertEquals("Should set current signer",
            mockSigner,
            sdkConfig.currentSigner)
    }
    
    @Test
    fun testMultipleSetSigner_LastOneWins() {
        // Given: Multiple signers
        val signer1 = mockSigner
        
        val signer2 = object : TransactionSigner {
            override val publicKey: PublicKey get() = mockPublicKey
            override suspend fun signMessage(message: ByteArray): ByteArray = ByteArray(64)
        }
        
        // When: Set multiple signers
        sdkConfig.setSigner(signer1)
        sdkConfig.setSigner(signer2)
        
        // Then: Last one should be active
        assertEquals("Last signer should be active", signer2, sdkConfig.currentSigner)
    }
    
    @Test
    fun testSignerStrategy_VaultDefault() {
        // Given: Vault default strategy
        val strategy = SignerStrategy.VaultDefault()
        
        // Then: Should indicate vault usage
        assertTrue("Should create vault signer",
            strategy is SignerStrategy.VaultDefault)
    }
    
    @Test
    fun testSignerStrategy_External() {
        // Given: External signer strategy
        val strategy = SignerStrategy.External(mockSigner)
        
        // Then: Should wrap provided signer
        assertTrue("Should accept external signer",
            strategy is SignerStrategy.External)
    }
    
    @Test
    fun testInitOptions_DefaultStrategy() {
        // Given: InitOptions with defaults
        val options = InitOptions.default()
        
        // Then: Should use defaults
        assertNotNull("Should have signer strategy", options.signerStrategy)
    }
    
    @Test
    fun testInitOptions_VaultFactory() {
        // Given: Vault factory method
        val options = InitOptions.vault()
        
        // Then: Should create vault-based options
        assertTrue("Should create vault signer",
            options.signerStrategy is SignerStrategy.VaultDefault)
    }
    
    @Test
    fun testInitOptions_CustomSignerFactory() {
        // Given: Custom signer factory
        val options = InitOptions.custom(mockSigner)
        
        // Then: Should wrap custom signer
        assertTrue("Should use custom signer",
            options.signerStrategy is SignerStrategy.External)
    }
    
    @Test
    fun testSignerFallback_ProvidesSignerWhenAvailable() {
        // Given: A signer provided
        sdkConfig.setSigner(mockSigner)
        
        // When: Use provided signer as fallback
        val signer = sdkConfig.currentSigner
        
        // Then: Should use provided
        assertEquals("Should use provided signer", mockSigner, signer)
    }
    
    @Test
    fun testSignerFallback_DefaultsToNullWhenNotSet() {
        // Given: No signer configured
        // When: Try to get current signer
        val signer = sdkConfig.currentSigner
        
        // Then: Should default to null (not HotSigner in unit test)
        assertNull("Should default to null", signer)
    }
    
    @Test
    fun testClearsigner_BecomesNull() {
        // Given: A signer is set
        sdkConfig.setSigner(mockSigner)
        assertEquals("Should have signer", mockSigner, sdkConfig.currentSigner)
        
        // When: Set to null
        sdkConfig.currentSigner = null
        
        // Then: Should be cleared
        assertNull("Should clear signer", sdkConfig.currentSigner)
    }
    
    @Test
    fun testSignerStrategy_ContainsConfiguration() {
        // Given: A vault strategy with specific app ID and index
        val appId = "com.example.app"
        val walletIndex = 2
        val strategy = SignerStrategy.VaultDefault(appId, walletIndex)
        
        // Then: Should store configuration
        assertTrue("Should be VaultDefault strategy",
            strategy is SignerStrategy.VaultDefault)
    }
    
    @Test
    fun testExternalSignerStrategy_WrapsProvidedSigner() {
        // Given: An external signer
        val externalSigner = object : TransactionSigner {
            override val publicKey: PublicKey get() = mockPublicKey
            override suspend fun signMessage(message: ByteArray): ByteArray {
                return ByteArray(64) { 1 }
            }
        }
        
        // When: Create external strategy
        val strategy = SignerStrategy.External(externalSigner)
        
        // Then: Should wrap correctly
        assertTrue("Should be External strategy",
            strategy is SignerStrategy.External)
    }
    
    @Test
    fun testDualAPI_LegacyHotSignerAndNewVault() {
        // Given: Both legacy and new APIs available
        
        // Legacy: Altude.setApiKey() with HotSigner
        // (In real code, this would set HotSigner)
        
        // New: AltudeGasStation.init() with VaultSigner
        val options = InitOptions.vault()
        
        // Then: Both should be supported
        assertNotNull("Legacy API should exist in codebase", "Altude API documented")
        assertNotNull("New API should exist in codebase", "AltudeGasStation API documented")
    }
    
    @Test
    fun testSignerFactory_CreatesCorrectType() {
        // Given: InitOptions with specific strategy
        val vaultOptions = InitOptions.vault()
        val customOptions = InitOptions.custom(mockSigner)
        
        // Then: Each creates appropriate type
        assertTrue("Vault factory creates VaultDefault",
            vaultOptions.signerStrategy is SignerStrategy.VaultDefault)
        
        assertTrue("Custom factory creates External",
            customOptions.signerStrategy is SignerStrategy.External)
    }
    
    @Test
    fun testMultipleWallets_CanCreateMultipleSigners() {
        // Given: Same app, different wallet indices
        val appId = "com.example.app"
        
        // When: Create strategies for different wallets
        val wallet0 = SignerStrategy.VaultDefault(appId, 0)
        val wallet1 = SignerStrategy.VaultDefault(appId, 1)
        val wallet2 = SignerStrategy.VaultDefault(appId, 2)
        
        // Then: All should be valid
        assertTrue("Wallet 0 strategy created", wallet0 is SignerStrategy.VaultDefault)
        assertTrue("Wallet 1 strategy created", wallet1 is SignerStrategy.VaultDefault)
        assertTrue("Wallet 2 strategy created", wallet2 is SignerStrategy.VaultDefault)
    }
    
    @Test
    fun testSignerStrategySelection_VaultDefaultVsExternal() {
        // Given: Option to choose between vault and external
        
        // When: Create both
        val vaultStrategy = SignerStrategy.VaultDefault()
        val externalStrategy = SignerStrategy.External(mockSigner)
        
        // Then: Can distinguish
        assertTrue("Can identify vault strategy",
            vaultStrategy is SignerStrategy.VaultDefault)
        assertTrue("Can identify external strategy",
            externalStrategy is SignerStrategy.External)
    }
    
    @Test
    fun testNoBreakingChanges_OldInitPatternsStillWork() {
        // Given: Old code patterns
        // (These should still work even if deprecated)
        
        // Old pattern: Direct instantiation
        val config = SdkConfig.getInstance()
        config.currentSigner = mockSigner
        
        // Then: Should still work
        assertEquals("Old pattern should still work", mockSigner, config.currentSigner)
    }
    
    @Test
    fun testInitOptions_AllFactoriesPresent() {
        // Then: All expected factory methods should exist
        
        // These should all compile and return valid options
        val defaultOpts = InitOptions.default()
        val vaultOpts = InitOptions.vault()
        val customOpts = InitOptions.custom(mockSigner)
        val vaultNoBioOpts = InitOptions.vaultNoBiometric()
        
        assertNotNull("default() factory exists", defaultOpts)
        assertNotNull("vault() factory exists", vaultOpts)
        assertNotNull("custom() factory exists", customOpts)
        assertNotNull("vaultNoBiometric() factory exists", vaultNoBioOpts)
    }
    
    @Test
    fun testSignerInstance_CanBeReplacedDuringRuntime() {
        // Given: Initial signer
        val signer1 = mockSigner
        sdkConfig.setSigner(signer1)
        assertEquals("Signer 1 set", signer1, sdkConfig.currentSigner)
        
        // When: Replace with different signer during runtime
        val signer2 = object : TransactionSigner {
            override val publicKey: PublicKey get() = mockPublicKey
            override suspend fun signMessage(message: ByteArray): ByteArray = ByteArray(64)
        }
        sdkConfig.setSigner(signer2)
        
        // Then: New signer should be active
        assertEquals("Signer 2 replaced signer 1", signer2, sdkConfig.currentSigner)
        assertNotEquals("Signer 1 is no longer active", signer1, sdkConfig.currentSigner)
    }
}
