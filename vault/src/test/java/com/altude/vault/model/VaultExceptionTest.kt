package com.altude.vault.model

import junit.framework.TestCase.*
import org.junit.Test

/**
 * Unit tests for VaultException - Error codes and exception hierarchy
 *
 * Tests validate:
 * - All exception types are properly defined
 * - Error codes are unique and follow naming convention
 * - Remediation messages are present and helpful
 * - Exception hierarchy and inheritance
 */
class VaultExceptionTest {
    
    @Test
    fun testBiometricNotAvailableException_HasCorrectErrorCode() {
        // Given/When: Create exception
        val exception = BiometricNotAvailableException()
        
        // Then: Error code should be 0201
        assertEquals("Error code should be VAULT-0201",
            VaultErrorCodes.BIOMETRIC_UNAVAILABLE,
            exception.errorCode)
    }
    
    @Test
    fun testBiometricNotAvailableException_HasRemediation() {
        // Given/When: Create exception
        val exception = BiometricNotAvailableException()
        
        // Then: Should have remediation text
        assertNotNull("Remediation should not be null", exception.remediation)
        assertTrue("Remediation should not be empty",
            exception.remediation.isNotEmpty())
        assertTrue("Remediation should mention settings",
            exception.remediation.lowercase().contains("settings"))
    }
    
    @Test
    fun testBiometricInvalidatedException_HasCorrectErrorCode() {
        val exception = BiometricInvalidatedException()
        assertEquals("Error code should be VAULT-0202",
            VaultErrorCodes.BIOMETRIC_INVALIDATED,
            exception.errorCode)
    }
    
    @Test
    fun testBiometricInvalidatedException_HasRecoveryOptions() {
        val exception = BiometricInvalidatedException()
        
        assertNotNull("Remediation should exist", exception.remediation)
        assertTrue("Should mention app data",
            exception.remediation.lowercase().contains("data"))
    }
    
    @Test
    fun testBiometricAuthenticationFailedException_HasCorrectErrorCode() {
        val exception = BiometricAuthenticationFailedException()
        assertEquals("Error code should be VAULT-0203 or 0205",
            VaultErrorCodes.BIOMETRIC_AUTH_FAILED,
            exception.errorCode)
    }
    
    @Test
    fun testBiometricAuthenticationFailedException_TracksFailureReason() {
        // Given: Exception with specific failure reason
        val userCancelledEx = BiometricAuthenticationFailedException(
            failureReason = BiometricAuthenticationFailedException.FailureReason.UserCancelled
        )
        
        // Then: Should track the reason
        assertEquals("Should track user cancelled",
            BiometricAuthenticationFailedException.FailureReason.UserCancelled,
            userCancelledEx.failureReason)
    }
    
    @Test
    fun testVaultLockedException_HasCorrectErrorCode() {
        val exception = VaultLockedException()
        assertEquals("Error code should be VAULT-0401",
            VaultErrorCodes.VAULT_LOCKED,
            exception.errorCode)
    }
    
    @Test
    fun testVaultInitFailedException_HasCorrectErrorCode() {
        val exception = VaultInitFailedException()
        assertEquals("Error code should be VAULT-0101",
            VaultErrorCodes.INIT_FAILED,
            exception.errorCode)
    }
    
    @Test
    fun testVaultDecryptionFailedException_HasCorrectErrorCode() {
        val exception = VaultDecryptionFailedException()
        assertEquals("Error code should be VAULT-0301",
            VaultErrorCodes.DECRYPTION_FAILED,
            exception.errorCode)
    }
    
    @Test
    fun testVaultAlreadyInitializedException_HasCorrectErrorCode() {
        val exception = VaultAlreadyInitializedException()
        assertEquals("Error code should be VAULT-0104",
            VaultErrorCodes.ALREADY_INITIALIZED,
            exception.errorCode)
    }
    
    @Test
    fun testVaultConfigurationException_HasCorrectErrorCode() {
        val exception = VaultConfigurationException()
        assertEquals("Error code should be VAULT-0501",
            VaultErrorCodes.INVALID_CONFIG,
            exception.errorCode)
    }
    
    @Test
    fun testAllExceptionsExtendVaultException() {
        // Given: All exception types
        val exceptions = listOf(
            BiometricNotAvailableException(),
            BiometricInvalidatedException(),
            BiometricAuthenticationFailedException(),
            VaultLockedException(),
            VaultInitFailedException(),
            VaultDecryptionFailedException(),
            VaultAlreadyInitializedException(),
            VaultConfigurationException()
        )
        
        // Then: All should be VaultException instances
        exceptions.forEach { exception ->
            assertTrue("${exception.javaClass.simpleName} should extend VaultException",
                exception is VaultException)
        }
    }
    
    @Test
    fun testAllExceptionsExtendException() {
        // Given: All exception types
        val exceptions = listOf(
            BiometricNotAvailableException(),
            BiometricInvalidatedException(),
            BiometricAuthenticationFailedException(),
            VaultLockedException(),
            VaultInitFailedException(),
            VaultDecryptionFailedException(),
            VaultAlreadyInitializedException(),
            VaultConfigurationException()
        )
        
        // Then: All should be Exception instances (throwable)
        exceptions.forEach { exception ->
            assertTrue("${exception.javaClass.simpleName} should extend Exception",
                exception is Exception)
        }
    }
    
    @Test
    fun testExceptionToString_IncludesErrorCode() {
        // Given: An exception
        val exception = BiometricNotAvailableException()
        
        // When: Convert to string
        val stringRep = exception.toString()
        
        // Then: Should include error code
        assertTrue("toString should include error code",
            stringRep.contains(exception.errorCode))
    }
    
    @Test
    fun testExceptionWithCause_PreservesRootCause() {
        // Given: A root cause exception
        val rootCause = IOException("Device error")
        
        // When: Create vault exception with cause
        val vaultException = VaultInitFailedException(cause = rootCause)
        
        // Then: Cause should be preserved
        assertEquals("Should preserve root cause",
            rootCause,
            vaultException.cause)
    }
    
    @Test
    fun testExceptionMessage_ContainsNoSecretsOrSensitiveData() {
        // Given: All exception types
        val exceptions = listOf(
            BiometricNotAvailableException(),
            BiometricInvalidatedException(),
            BiometricAuthenticationFailedException(),
            VaultLockedException(),
            VaultInitFailedException(),
            VaultDecryptionFailedException(),
            VaultAlreadyInitializedException(),
            VaultConfigurationException()
        )
        
        // Then: Messages should not contain sensitive keywords
        exceptions.forEach { exception ->
            val message = exception.message?.lowercase() ?: ""
            assertFalse("Should not contain 'key' in context of secret key",
                message.contains("secret key") || message.contains("private key"))
            assertFalse("Should not contain 'seed'",
                message.contains("seed"))
            assertFalse("Should not contain raw hex/binary data",
                message.matches(Regex(".*[0-9a-f]{32,}.*")))
        }
    }
    
    @Test
    fun testErrorCodeConstants_AreUnique() {
        // Given: All error code constants
        val codes = listOf(
            VaultErrorCodes.INIT_FAILED,
            VaultErrorCodes.INIT_PERMISSION_DENIED,
            VaultErrorCodes.INIT_INSUFFICIENT_STORAGE,
            VaultErrorCodes.ALREADY_INITIALIZED,
            VaultErrorCodes.BIOMETRIC_UNAVAILABLE,
            VaultErrorCodes.BIOMETRIC_INVALIDATED,
            VaultErrorCodes.BIOMETRIC_AUTH_FAILED,
            VaultErrorCodes.BIOMETRIC_LOCKOUT,
            VaultErrorCodes.BIOMETRIC_CANCELLED,
            VaultErrorCodes.DECRYPTION_FAILED,
            VaultErrorCodes.ENCRYPTION_FAILED,
            VaultErrorCodes.STORAGE_CORRUPTED,
            VaultErrorCodes.KEYSTORE_ERROR,
            VaultErrorCodes.VAULT_LOCKED,
            VaultErrorCodes.SESSION_EXPIRED,
            VaultErrorCodes.INVALID_CONTEXT,
            VaultErrorCodes.INVALID_CONFIG
        )
        
        // Then: All codes should be unique
        assertEquals("All error codes should be unique",
            codes.size,
            codes.distinct().size)
    }
    
    @Test
    fun testErrorCodeFormat_FollowsVaultXXXXPattern() {
        // Given: All error code constants
        val codes = listOf(
            VaultErrorCodes.INIT_FAILED,
            VaultErrorCodes.BIOMETRIC_UNAVAILABLE,
            VaultErrorCodes.DECRYPTION_FAILED,
            VaultErrorCodes.VAULT_LOCKED,
            VaultErrorCodes.INVALID_CONFIG
        )
        
        // Then: All should follow VAULT-XXXX format
        codes.forEach { code ->
            assertTrue("Code should start with VAULT-: $code",
                code.startsWith("VAULT-"))
            assertTrue("Code should have 4-digit code: $code",
                code.matches(Regex("VAULT-\\d{4}")))
        }
    }
    
    @Test
    fun testErrorCodeCategories_AreCorrect() {
        // Then: Verify category boundaries
        
        // Init errors (01xx)
        assertTrue("Init errors in 01xx",
            VaultErrorCodes.INIT_FAILED.endsWith("0101"))
        assertTrue("Init errors in 01xx",
            VaultErrorCodes.ALREADY_INITIALIZED.endsWith("0104"))
        
        // Biometric errors (02xx)
        assertTrue("Biometric errors in 02xx",
            VaultErrorCodes.BIOMETRIC_UNAVAILABLE.endsWith("0201"))
        assertTrue("Biometric errors in 02xx",
            VaultErrorCodes.BIOMETRIC_CANCELLED.endsWith("0205"))
        
        // Storage errors (03xx)
        assertTrue("Storage errors in 03xx",
            VaultErrorCodes.DECRYPTION_FAILED.endsWith("0301"))
        assertTrue("Storage errors in 03xx",
            VaultErrorCodes.KEYSTORE_ERROR.endsWith("0304"))
        
        // Runtime errors (04xx)
        assertTrue("Runtime errors in 04xx",
            VaultErrorCodes.VAULT_LOCKED.endsWith("0401"))
        assertTrue("Runtime errors in 04xx",
            VaultErrorCodes.INVALID_CONTEXT.endsWith("0403"))
        
        // Config errors (05xx)
        assertTrue("Config errors in 05xx",
            VaultErrorCodes.INVALID_CONFIG.endsWith("0501"))
    }
    
    @Test
    fun testCustomMessages_OverrideDefaults() {
        // Given: Custom message and remediation
        val customMessage = "Custom error message"
        val customRemediation = "Custom remediation steps"
        
        // When: Create exception with custom values
        val exception = VaultInitFailedException(
            message = customMessage,
            remediation = customRemediation
        )
        
        // Then: Custom values should be used
        assertTrue("Should contain custom message",
            exception.message?.contains(customMessage) ?: false)
        assertEquals("Should use custom remediation",
            customRemediation,
            exception.remediation)
    }
    
    @Test
    fun testRemediationMessages_AreHelpful() {
        // Given: Exception with remediation
        val exception = BiometricNotAvailableException()
        
        // Then: Remediation should be action-oriented
        assertNotNull("Should have remediation", exception.remediation)
        assertTrue("Remediation should give clear steps",
            exception.remediation.contains("Settings") ||
            exception.remediation.contains("settings") ||
            exception.remediation.contains("enroll"))
    }
}
