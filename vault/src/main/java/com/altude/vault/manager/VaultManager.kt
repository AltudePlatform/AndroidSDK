package com.altude.vault.manager

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.altude.vault.crypto.BiometricHandler
import com.altude.vault.crypto.VaultCrypto
import com.altude.vault.model.VaultSession
import com.altude.vault.storage.VaultStorage
import foundation.metaplex.solanaeddsa.Keypair
import foundation.metaplex.solanapublickeys.PublicKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * VaultManager orchestrates vault lifecycle:
 * - Vault creation (seed generation + encryption)
 * - Vault unlocking (biometric auth + decryption)
 * - Session management (TTL-based key caching)
 * - Vault cleanup
 *
 * All operations are thread-safe via mutex locking.
 * Session is kept in memory with TTL; expired sessions require re-authentication.
 */
object VaultManager {

    private val mutex = Mutex()

    // Current vault session (in-memory, cleared on expiration or explicit logout)
    private var currentSession: VaultSession? = null

    // Configuration for next initialization
    private var initConfig: VaultInitConfig? = null

    /**
     * VaultManager configuration set during initialization.
     */
    data class VaultInitConfig(
        val appId: String,
        val requireBiometric: Boolean,
        val sessionTTLSeconds: Int
    )

    /**
     * Initialize and create a new vault.
     * This generates a root seed, encrypts it, and stores it.
     *
     * Process:
     * 1. Generate cryptographically secure random seed (32 bytes)
     * 2. Initialize keystore with optional biometric gating
     * 3. Encrypt and store seed
     *
     * @param context Application context
     * @param appId Unique app identifier (typically package name)
     * @param requireBiometric Whether to require biometric auth (default: true)
     * @throws VaultException if initialization fails
     */
    suspend fun createVault(
        context: Context,
        appId: String,
        requireBiometric: Boolean = true
    ) {
        mutex.withLock {
            // Generate random seed
            val seed = VaultCrypto.generateRandomSeed()

            // Initialize keystore with optional biometric
            VaultStorage.initializeKeystore(context, appId, requireBiometric)

            // Encrypt and store seed
            VaultStorage.storeSeed(context, appId, seed)

            // Store config for later use
            initConfig = VaultInitConfig(appId, requireBiometric, 45)

            // Clear any existing session
            currentSession = null
        }
    }

    /**
     * Unlock vault and get a keypair for signing.
     * This prompts for biometric auth, decrypts the seed, derives a keypair, and optionally creates a session.
     *
     * Behavior depends on sessionTTLSeconds:
     * - sessionTTLSeconds > 0: Creates a TTL-based session; subsequent calls reuse cached keypair
     * - sessionTTLSeconds = 0: Always re-authenticates; no session caching (most secure)
     *
     * Process:
     * 1. Check if session is valid and can be reused (if sessionTTLSeconds > 0)
     * 2. Prompt for biometric authentication via BiometricHandler
     * 3. Decrypt seed from storage using biometric callback
     * 4. Derive keypair using appId + walletIndex
     * 5. Optionally create new session with TTL
     *
     * @param context Application context (Activity required for biometric prompt)
     * @param appId App identifier (must match what was used in createVault)
     * @param walletIndex Which wallet to derive (default: 0)
     * @param sessionTTLSeconds Session TTL in seconds
     *                          - > 0: Create and reuse session
     *                          - = 0: Always authenticate (PerOperation mode)
     * @param authMessages Custom messages for biometric prompt
     * @return Keypair for signing operations
     * @throws VaultException if unlock fails
     */
    suspend fun unlockVault(
        context: Context,
        appId: String,
        walletIndex: Int = 0,
        sessionTTLSeconds: Int = 45,
        authMessages: com.altude.vault.model.VaultSigner.AuthMessages = com.altude.vault.model.VaultSigner.AuthMessages()
    ): Keypair {
        mutex.withLock {
            // Check if existing session is valid (only for session-based mode, not per-operation)
            if (sessionTTLSeconds > 0 &&
                currentSession?.isValid() == true &&
                currentSession?.appId == appId &&
                currentSession?.walletIndex == walletIndex
            ) {
                // Reuse cached session keypair (session-based mode)
                return currentSession!!.keypair
            }

            // Need to prompt for authentication - context must be FragmentActivity
            if (context !is FragmentActivity) {
                throw IllegalArgumentException(
                    "Context must be FragmentActivity for biometric prompt"
                )
            }

            // Prompt user for biometric authentication with custom messages
            val seed = BiometricHandler.authenticate(
                context,
                title = authMessages.title,
                description = authMessages.description
            ) {
                // Callback executed after successful auth
                VaultStorage.retrieveSeed(context, appId)
            }

            // Derive keypair from seed using HKDF + Ed25519
            val keypair = VaultCrypto.deriveKeypair(seed, appId, walletIndex)
            val publicKey = PublicKey(keypair.publicKey.toByteArray())

            // Only create session if sessionTTLSeconds > 0 (session-based mode)
            if (sessionTTLSeconds > 0) {
                val expiresAtMs = System.currentTimeMillis() + (sessionTTLSeconds * 1000L)

                // Create and store session for reuse within TTL
                val session = VaultSession(
                    keypair = keypair,
                    publicKey = publicKey,
                    expiresAtMs = expiresAtMs,
                    appId = appId,
                    walletIndex = walletIndex
                )

                currentSession = session
            } else {
                // Per-operation mode: clear any existing session
                currentSession = null
            }

            return keypair
        }
    }

    /**
     * Get the current active session if valid.
     * Returns null if no session exists or it has expired.
     *
     * @return VaultSession if valid, null otherwise
     */
    suspend fun getSession(): VaultSession? {
        mutex.withLock {
            return if (currentSession?.isValid() == true) currentSession else null
        }
    }

    /**
     * Explicitly lock the vault (clear session).
     * The next signing operation will require biometric re-authentication.
     */
    suspend fun lockVault() {
        mutex.withLock {
            currentSession = null
        }
    }

    /**
     * Check if vault is currently unlocked (session is valid).
     *
     * @return true if session exists and is valid
     */
    suspend fun isVaultUnlocked(): Boolean {
        mutex.withLock {
            return currentSession?.isValid() == true
        }
    }

    /**
     * Clear vault completely (destructive operation).
     * This deletes all vault data from storage and clears the session.
     * Typically called when resetting the app.
     *
     * @param context Application context
     * @param appId App identifier
     * @return true if vault was deleted, false if didn't exist
     */
    suspend fun clearVault(context: Context, appId: String): Boolean {
        mutex.withLock {
            currentSession = null
            initConfig = null
            return VaultStorage.clearVault(context, appId)
        }
    }

    /**
     * Check if a vault exists for the given app.
     *
     * @param context Application context
     * @param appId App identifier
     * @return true if vault file exists
     */
    fun vaultExists(context: Context, appId: String): Boolean {
        return VaultStorage.vaultExists(context, appId)
    }
}
