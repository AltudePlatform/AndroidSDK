package com.altude.vault.model

import junit.framework.TestCase.*
import org.junit.Test
import com.solana.core.KeyPair
import com.solana.core.PublicKey

/**
 * Unit tests for VaultSession - TTL validation and expiration
 *
 * Tests validate:
 * - Session validity within TTL
 * - Session expiration at TTL boundary
 * - Remaining time calculation
 * - Edge cases around expiration
 */
class VaultSessionTest {
    
    private fun createTestKeypair(): KeyPair {
        // Create a test keypair (mocked or derived)
        return KeyPair.newInstance()
    }
    
    @Test
    fun testNewSession_IsValidImmediately() {
        // Given: A newly created session
        val keypair = createTestKeypair()
        val currentTime = System.currentTimeMillis()
        val expiresAt = currentTime + 45_000 // Expires in 45 seconds
        
        val session = VaultSession(
            keypair = keypair,
            publicKey = keypair.publicKey,
            expiresAtMs = expiresAt,
            appId = "com.example.app",
            walletIndex = 0
        )
        
        // When: Check validity immediately
        val isValid = session.isValid()
        
        // Then: Session should be valid
        assertTrue("New session should be valid", isValid)
    }
    
    @Test
    fun testSessionExpiration_IsInvalidAfterTTL() {
        // Given: A session that expired 1 second ago
        val keypair = createTestKeypair()
        val currentTime = System.currentTimeMillis()
        val expiresAt = currentTime - 1000 // Expired 1 second ago
        
        val session = VaultSession(
            keypair = keypair,
            publicKey = keypair.publicKey,
            expiresAtMs = expiresAt,
            appId = "com.example.app",
            walletIndex = 0
        )
        
        // When: Check validity
        val isValid = session.isValid()
        
        // Then: Expired session should be invalid
        assertFalse("Expired session should be invalid", isValid)
    }
    
    @Test
    fun testSessionAtBoundary_ExactlyAtExpiration() {
        // Given: A session at exact expiration time
        val keypair = createTestKeypair()
        val boundary = System.currentTimeMillis()
        
        val session = VaultSession(
            keypair = keypair,
            publicKey = keypair.publicKey,
            expiresAtMs = boundary,
            appId = "com.example.app",
            walletIndex = 0
        )
        
        // When: Check validity at boundary
        // Note: Most implementations treat boundary as expired
        val isValid = session.isValid()
        
        // Then: Should be expired (time >= expireAt means expired)
        assertFalse("Session at exact expiration should be expired", isValid)
    }
    
    @Test
    fun testRemainingTime_CalculatedCorrectly() {
        // Given: A session with known remaining time
        val keypair = createTestKeypair()
        val currentTime = System.currentTimeMillis()
        val remainingSeconds = 25L // 25 seconds remaining
        val expiresAt = currentTime + (remainingSeconds * 1000)
        
        val session = VaultSession(
            keypair = keypair,
            publicKey = keypair.publicKey,
            expiresAtMs = expiresAt,
            appId = "com.example.app",
            walletIndex = 0
        )
        
        // When: Get remaining time
        val remaining = session.remainingTimeMs()
        
        // Then: Remaining time should be approximately correct (within 100ms tolerance)
        assertTrue("Remaining time should be positive", remaining > 0)
        assertTrue("Remaining time should be close to expected",
            remaining >= (remainingSeconds * 1000) - 500)
    }
    
    @Test
    fun testRemainingTime_ZeroWhenExpired() {
        // Given: An expired session
        val keypair = createTestKeypair()
        val expiresAt = System.currentTimeMillis() - 5000 // Expired 5 seconds ago
        
        val session = VaultSession(
            keypair = keypair,
            publicKey = keypair.publicKey,
            expiresAtMs = expiresAt,
            appId = "com.example.app",
            walletIndex = 0
        )
        
        // When: Get remaining time
        val remaining = session.remainingTimeMs()
        
        // Then: Remaining time should be zero or negative
        assertTrue("Expired session should have no remaining time", remaining <= 0)
    }
    
    @Test
    fun testLongTTL_SessionValidFor24Hours() {
        // Given: A session with 24-hour TTL
        val keypair = createTestKeypair()
        val currentTime = System.currentTimeMillis()
        val expiresAt = currentTime + (24 * 60 * 60 * 1000) // 24 hours
        
        val session = VaultSession(
            keypair = keypair,
            publicKey = keypair.publicKey,
            expiresAtMs = expiresAt,
            appId = "com.example.app",
            walletIndex = 0
        )
        
        // When: Check validity
        val isValid = session.isValid()
        
        // Then: Should still be valid
        assertTrue("24-hour session should be valid", isValid)
        
        // And: Should have significant remaining time
        val remaining = session.remainingTimeMs()
        assertTrue("Should have 23+ hours remaining",
            remaining > 23 * 60 * 60 * 1000)
    }
    
    @Test
    fun testShortTTL_SessionExpiresToo() {
        // Given: A session with 1-second TTL
        val keypair = createTestKeypair()
        val currentTime = System.currentTimeMillis()
        val expiresAt = currentTime + 1000 // 1 second
        
        val session = VaultSession(
            keypair = keypair,
            publicKey = keypair.publicKey,
            expiresAtMs = expiresAt,
            appId = "com.example.app",
            walletIndex = 0
        )
        
        // When: Check immediately and after delay
        val isValidImmediately = session.isValid()
        Thread.sleep(1100) // Wait 1.1 seconds
        val isValidAfter = session.isValid()
        
        // Then: Valid immediately, expired after delay
        assertTrue("Should be valid immediately", isValidImmediately)
        assertFalse("Should expire after 1 second", isValidAfter)
    }
    
    @Test
    fun testSessionContainsAllMetadata() {
        // Given: A session with metadata
        val keypair = createTestKeypair()
        val appId = "com.example.app"
        val walletIndex = 3
        val expiresAt = System.currentTimeMillis() + 45_000
        
        val session = VaultSession(
            keypair = keypair,
            publicKey = keypair.publicKey,
            expiresAtMs = expiresAt,
            appId = appId,
            walletIndex = walletIndex
        )
        
        // When/Then: All metadata is accessible and correct
        assertEquals("AppId should match", appId, session.appId)
        assertEquals("Wallet index should match", walletIndex, session.walletIndex)
        assertEquals("Public key should match", keypair.publicKey.toBase58(),
            session.publicKey.toBase58())
        assertEquals("Expiry should match", expiresAt, session.expiresAtMs)
    }
    
    @Test
    fun testSessionKeypairCanSignMessages() {
        // Given: A session with valid keypair
        val keypair = createTestKeypair()
        val expiresAt = System.currentTimeMillis() + 45_000
        
        val session = VaultSession(
            keypair = keypair,
            publicKey = keypair.publicKey,
            expiresAtMs = expiresAt,
            appId = "com.example.app",
            walletIndex = 0
        )
        
        // When: Sign a message with session's keypair
        val message = "Test message".toByteArray()
        val signature = session.keypair.sign(message)
        
        // Then: Should produce valid signature
        assertNotNull("Signature should not be null", signature)
        assertTrue("Signature should have content", signature.isNotEmpty())
    }
    
    @Test
    fun testMultipleSessions_HaveDifferentExpirations() {
        // Given: Multiple sessions created at different times
        val keypair = createTestKeypair()
        
        val session1 = VaultSession(
            keypair = keypair,
            publicKey = keypair.publicKey,
            expiresAtMs = System.currentTimeMillis() + 10_000,
            appId = "app",
            walletIndex = 0
        )
        
        Thread.sleep(100)
        
        val session2 = VaultSession(
            keypair = keypair,
            publicKey = keypair.publicKey,
            expiresAtMs = System.currentTimeMillis() + 10_000,
            appId = "app",
            walletIndex = 0
        )
        
        // When: Compare remaining times
        val remaining1 = session1.remainingTimeMs()
        val remaining2 = session2.remainingTimeMs()
        
        // Then: Session2 should have slightly more time (created later)
        assertTrue("Session2 should have more remaining time",
            remaining2 >= remaining1)
    }
    
    @Test
    fun testNegativeRemainingTime_StaysNegative() {
        // Given: An old expired session
        val keypair = createTestKeypair()
        val expiresAt = System.currentTimeMillis() - 60_000 // Expired 60 seconds ago
        
        val session = VaultSession(
            keypair = keypair,
            publicKey = keypair.publicKey,
            expiresAtMs = expiresAt,
            appId = "app",
            walletIndex = 0
        )
        
        // When: Get remaining time
        val remaining = session.remainingTimeMs()
        
        // Then: Should be significantly negative
        assertTrue("Remaining time should be negative for expired session", remaining < 0)
        assertTrue("Should be less than -60 seconds",
            remaining < -60_000)
    }
    
    @Test
    fun testSessionComparison_SameKeypairDifferentExpiry() {
        // Given: Same keypair but different expiry times
        val keypair = createTestKeypair()
        val now = System.currentTimeMillis()
        
        val session1 = VaultSession(
            keypair = keypair,
            publicKey = keypair.publicKey,
            expiresAtMs = now + 10_000,
            appId = "app",
            walletIndex = 0
        )
        
        val session2 = VaultSession(
            keypair = keypair,
            publicKey = keypair.publicKey,
            expiresAtMs = now + 20_000,
            appId = "app",
            walletIndex = 0
        )
        
        // When: Compare validity and remaining time
        val remaining1 = session1.remainingTimeMs()
        val remaining2 = session2.remainingTimeMs()
        
        // Then: Session2 has more time
        assertTrue("Session2 should have more remaining time",
            remaining2 > remaining1)
    }
}
