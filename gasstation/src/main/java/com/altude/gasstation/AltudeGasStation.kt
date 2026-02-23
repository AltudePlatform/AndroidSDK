package com.altude.gasstation

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.altude.core.config.InitOptions
import com.altude.core.config.SdkConfig
import com.altude.core.config.SignerStrategy
import com.altude.core.helper.Mnemonic
import com.altude.core.model.TransactionSigner
import com.altude.core.service.StorageService
import com.altude.vault.manager.VaultManager
import com.altude.vault.model.VaultSigner

/**
 * ModernAltudeGasStation provides the modern SDK initialization API with Vault as the default signer.
 * This is the recommended entry point for new integrations.
 *
 * Typical usage (defaults to Vault with biometrics):
 * ```
 * AltudeGasStation.init(context, apiKey)
 * // Then use Altude object or new async APIs
 * ```
 *
 * Advanced usage (custom signer):
 * ```
 * AltudeGasStation.init(
 *     context,
 *     apiKey,
 *     InitOptions(signerStrategy = SignerStrategy.External(myCustomSigner))
 * )
 * ```
 *
 * Key differences from legacy `Altude.setApiKey()`:
 * - VaultSigner is the default (not HotSigner)
 * - Biometric authentication is required (no plaintext fallback)
 * - Session-based key management with TTL
 * - Modern error handling with remediation guidance
 */
object AltudeGasStation {

    /**
     * Initialize AltudeGasStation with the given API key and optional configuration.
     * This sets up the transaction signer, initializes Core SDK services, and prepares the vault if using Vault strategy.
     *
     * Flow:
     * 1. Validate inputs and check biometric availability (if required)
     * 2. Initialize Core SDK services (SdkConfig, StorageService)
     * 3. Generate or initialize vault based on signer strategy
     * 4. Set up the transaction signer in SdkConfig
     *
     * @param context FragmentActivity context required for biometric prompts if using Vault
     * @param apiKey Altude Gas Station API key for authentication
     * @param options Init configuration including signer strategy (defaults to Vault with biometrics)
     * @return Result indicating success or failure with remediation messaging
     *
     * @throws IllegalArgumentException if context is not FragmentActivity
     * @throws VaultException if vault initialization fails
     * @throws BiometricNotAvailableException if biometric required but not available
     */
    suspend fun init(
        context: Context,
        apiKey: String,
        options: InitOptions = InitOptions()
    ): Result<Unit> {
        return try {
            // Step 1: Ensure context is FragmentActivity for biometric support
            if (options.signerStrategy is SignerStrategy.VaultDefault && context !is FragmentActivity) {
                throw IllegalArgumentException(
                    "VaultSigner requires FragmentActivity context for biometric prompts. " +
                            "Got ${context.javaClass.simpleName} instead."
                )
            }

            // Step 2: Initialize Core SDK services
            SdkConfig.setApiKey(context, apiKey)

            // Step 3: Set up signer based on strategy
            val signer = when (options.signerStrategy) {
                is SignerStrategy.VaultDefault -> {
                    // Create VaultSigner - takes care of vault initialization internally
                    createVaultSigner(context, options)
                }

                is SignerStrategy.External -> {
                    // Use external signer directly
                    options.signerStrategy.signer
                }
            }

            // Step 4: Set the signer in SdkConfig for all subsequent operations
            SdkConfig.setSigner(signer)

            // Step 5: Initialize storage for backward compatibility with legacy APIs
            StorageService.init(context)

            // Generate mnemonic for backward compatibility (legacy Altude.setApiKey behavior)
            Altude.saveMnemonic(Mnemonic.generateMnemonic(12))

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Create and initialize a VaultSigner.
     * This internally creates the vault if it doesn't exist, then returns the initialized signer.
     *
     * @param context FragmentActivity context for biometric operations
     * @param options Init options containing vault configuration
     * @return Initialized VaultSigner ready for signing
     */
    private suspend fun createVaultSigner(
        context: Context,
        options: InitOptions
    ): VaultSigner {
        val vaultOptions = options.signerStrategy as SignerStrategy.VaultDefault

        val appId = vaultOptions.appId.ifEmpty {
            // Use package name as default app ID
            context.packageName
        }

        // Create vault if it doesn't exist
        if (!VaultManager.vaultExists(context, appId)) {
            VaultManager.createVault(
                context,
                appId,
                requireBiometric = vaultOptions.enableBiometric
            )
        }

        // Default to per-operation authentication (most secure)
        // Apps can use VaultSigner.createWithSession() afterwards for batch operations
        return VaultSigner.create(
            context = context,
            appId = appId,
            walletIndex = vaultOptions.walletIndex
        )
    }

    /**
     * Lock the current vault session (if using VaultSigner).
     * The next transaction will require biometric re-authentication.
     * Only has an effect if using a VaultSigner; other signers are unaffected.
     */
    suspend fun lockVault() {
        VaultManager.lockVault()
    }

    /**
     * Check if the vault session is currently unlocked.
     * Only relevant if using VaultSigner.
     *
     * @return true if vault is unlocked and session is valid, false otherwise
     */
    suspend fun isVaultUnlocked(): Boolean {
        return VaultManager.isVaultUnlocked()
    }

    /**
     * Clear the vault completely (destructive operation).
     * This deletes all vault data and should only be called when resetting the app or user.
     * Typically called on logout or app data clear.
     *
     * @param context Application context
     * @param appId Vault identifier (typically package name)
     * @return true if vault was deleted
     */
    suspend fun clearVault(context: Context, appId: String = context.packageName): Boolean {
        return VaultManager.clearVault(context, appId)
    }
}
