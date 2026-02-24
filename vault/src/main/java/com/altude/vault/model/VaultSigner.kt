package com.altude.vault.model

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.altude.core.model.TransactionSigner
import com.altude.vault.crypto.VaultCrypto
import com.altude.vault.manager.VaultManager
import foundation.metaplex.solanapublickeys.PublicKey

/**
 * Authentication mode for Vault transactions.
 * 
 * @param PerOperation Prompt for biometric on every signing operation (most secure, default)
 * @param SessionBased Prompt once, cache keypair for TTL seconds (better UX but less secure)
 */
sealed class VaultAuthMode {
    /**
     * Ask for biometric/device credential on every signing operation.
     * This is the default and most secure approach.
     * 
     * Behavior:
     * - Every transaction prompts user
     * - No session caching
     * - Maximum security; no risk of key leakage from stale session
     * 
     * Best for: Most applications, high-security requirements
     */
    object PerOperation : VaultAuthMode()
    
    /**
     * Prompt once, cache the keypair for a configurable TTL.
     * Reduces repeated prompts for batch operations or rapid transactions.
     * 
     * Behavior:
     * - First transaction prompts
     * - Keypair cached in memory for sessionTTLSeconds
     * - Subsequent transactions reuse cached keypair (no prompt)
     * - After TTL, next transaction re-prompts
     * 
     * @param sessionTTLSeconds Time to keep keypair cached (45s good balance)
     * 
     * Best for: High-volume traders, batch operations, power users
     */
    data class SessionBased(val sessionTTLSeconds: Int = 45) : VaultAuthMode() {
        init {
            require(sessionTTLSeconds > 0) {
                "sessionTTLSeconds must be positive, got $sessionTTLSeconds"
            }
        }
    }
}

/**
 * VaultSigner implements the TransactionSigner interface using Vault-encrypted keys.
 * This is the default signer for AltudeGasStation and handles all transaction signing.
 *
 * Authentication Modes:
 * 1. PerOperation (default): Biometric prompt on every signing operation
 *    - Most secure
 *    - Each transaction requires user interaction
 *    - Best for most applications
 * 
 * 2. SessionBased (advanced): Prompt once, cache keypair for TTL
 *    - Better UX for frequent operations (batch transfers, DeFi trading)
 *    - Configurable 45-second default session TTL
 *    - Keypair stays in memory; no persistent cache
 *
 * Signing Flow (PerOperation):
 * 1. App calls sign()
 * 2. Check if Vault initialized
 * 3. BiometricHandler shows prompt
 * 4. User authenticates (fingerprint, face, PIN)
 * 5. VaultStorage decrypts root seed
 * 6. VaultCrypto derives Ed25519 keypair
 * 7. Message signed with keypair
 * 8. Session cleared immediately
 *
 * Signing Flow (SessionBased):
 * 1. First sign(): [as above, then cache keypair+session]
 * 2. Second sign() (within TTL): Reuse cached keypair, skip biometric
 * 3. Third sign() (after TTL): Step 1 again (re-prompt)
 *
 * @param context FragmentActivity context needed for biometric prompts
 * @param appId Unique app identifier for key derivation
 * @param authMode Authentication mode (PerOperation by default, SessionBased for advanced)
 * @param walletIndex Which wallet to use (supports multi-wallet, default: 0)
 * @param authMessages Custom messages for biometric prompts (optional)
 */
class VaultSigner(
    private val context: Context,
    private val appId: String,
    private val authMode: VaultAuthMode = VaultAuthMode.PerOperation,
    private val walletIndex: Int = 0,
    private val authMessages: AuthMessages = AuthMessages()
) : TransactionSigner {

    // Cache public key after first derivation
    private var cachedPublicKey: PublicKey? = null

    /**
     * Custom messages for biometric authentication prompts.
     * 
     * @param title Title shown in biometric prompt (e.g., "Confirm Your Identity")
     * @param description Description/instruction for user
     * @param cancelButton Text for cancel button
     */
    data class AuthMessages(
        val title: String = "Confirm Your Identity",
        val description: String = "Use your biometric or device credential to sign transaction",
        val cancelButton: String = "Cancel"
    )

    /**
     * Get the public key for this vault signer.
     * This property is accessed frequently; we derive once and cache.
     *
     * Requires vault to be already initialized.
     * Will throw an exception if vault doesn't exist.
     */
    override val publicKey: PublicKey
        get() {
            // If we have a cached key, return it
            cachedPublicKey?.let { return it }

            // Otherwise, derive from current session or throw error
            val error = VaultLockedException(
                "Cannot access public key: Vault session expired or not initialized",
                "Sign a transaction first to authenticate and unlock the vault"
            )
            throw error
        }

    /**
     * Update the cached public key (called after successful unlock).
     * Internal use only.
     */
    internal fun setCachedPublicKey(publicKey: PublicKey) {
        cachedPublicKey = publicKey
    }

    /**
     * Sign a message with the vault's Ed25519 keypair.
     * This is the core signing operation used by all transaction builders.
     *
     * Authentication behavior depends on authMode:
     * - PerOperation: Always prompts user for biometric
     * - SessionBased: Prompts if session expired, reuses cached keypair otherwise
     *
     * @param message Transaction message bytes to sign
     * @return 64-byte Ed25519 signature
     * @throws VaultException if signing fails (biometric unavailable, invalidated, or user cancelled)
     * @throws IllegalArgumentException if context is not FragmentActivity
     */
    override suspend fun signMessage(message: ByteArray): ByteArray {
        if (context !is FragmentActivity) {
            throw IllegalArgumentException(
                "VaultSigner requires FragmentActivity context for biometric prompts. " +
                        "Got ${context.javaClass.simpleName} instead."
            )
        }

        // Get keypair based on auth mode
        val keypair = when (authMode) {
            is VaultAuthMode.PerOperation -> {
                // Always unlock (will prompt if not using session mode)
                VaultManager.unlockVault(
                    context = context,
                    appId = appId,
                    walletIndex = walletIndex,
                    sessionTTLSeconds = 0, // 0 means no session caching
                    authMessages = authMessages
                )
            }
            is VaultAuthMode.SessionBased -> {
                // Unlock with TTL-based session
                VaultManager.unlockVault(
                    context = context,
                    appId = appId,
                    walletIndex = walletIndex,
                    sessionTTLSeconds = authMode.sessionTTLSeconds,
                    authMessages = authMessages
                )
            }
        }

        // Cache public key after first successful unlock
        cachedPublicKey = PublicKey(keypair.publicKey.toByteArray())

        // Sign message with Ed25519
        return VaultCrypto.signMessage(message, keypair)
    }

    companion object {
        /**
         * Factory function to create a VaultSigner with per-operation biometric prompting.
         * This is the default and most secure approach.
         *
         * @param context FragmentActivity context
         * @param appId Unique app identifier (typically package name)
         * @param walletIndex Wallet index for multi-wallet support (default: 0)
         * @param authMessages Custom messages for biometric prompts
         * @return VaultSigner with PerOperation auth mode
         */
        fun create(
            context: Context,
            appId: String,
            walletIndex: Int = 0,
            authMessages: AuthMessages = AuthMessages()
        ): VaultSigner {
            return VaultSigner(
                context = context,
                appId = appId,
                authMode = VaultAuthMode.PerOperation,
                walletIndex = walletIndex,
                authMessages = authMessages
            )
        }

        /**
         * Factory function to create a VaultSigner with session-based caching.
         * Advanced option for better UX at the cost of slightly reduced security.
         *
         * @param context FragmentActivity context
         * @param appId Unique app identifier (typically package name)
         * @param sessionTTLSeconds How long to cache the keypair (default: 45s, typical 30-60s)
         * @param walletIndex Wallet index for multi-wallet support (default: 0)
         * @param authMessages Custom messages for biometric prompts
         * @return VaultSigner with SessionBased auth mode
         */
        fun createWithSession(
            context: Context,
            appId: String,
            sessionTTLSeconds: Int = 45,
            walletIndex: Int = 0,
            authMessages: AuthMessages = AuthMessages()
        ): VaultSigner {
            return VaultSigner(
                context = context,
                appId = appId,
                authMode = VaultAuthMode.SessionBased(sessionTTLSeconds),
                walletIndex = walletIndex,
                authMessages = authMessages
            )
        }
    }
}

