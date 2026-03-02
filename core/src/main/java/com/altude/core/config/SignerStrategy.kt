package com.altude.core.config

import com.altude.core.model.TransactionSigner

/**
 * Sealed class defining the signer strategy for transaction signing.
 * This is the primary API for selecting how transactions are signed.
 *
 * Allows developers to choose between Vault (client-side encrypted) or custom external signers
 * (hardware wallets, multi-sig, custodians, MPC, etc.).
 *
 * Design principles:
 * - Easy switching: same API regardless of signer strategy
 * - Default secure: VaultSigner with biometrics is the default
 * - Extensible: sealed class allows new strategies without breaking existing code
 * - Type-safe: sealed class ensures exhaustive when statements
 *
 * Example usage:
 * ```
 * // Default: Vault with biometrics
 * AltudeGasStation.init(context, apiKey)
 *
 * // Custom settings for Vault
 * AltudeGasStation.init(context, apiKey, InitOptions(
 *     signerStrategy = SignerStrategy.vaultWithBiometric(sessionTTLSeconds = 30)
 * ))
 *
 * // External hardware wallet
 * AltudeGasStation.init(context, apiKey, InitOptions(
 *     signerStrategy = SignerStrategy.external(hardwareWalletSigner)
 * ))
 *
 * // Future: Multi-sig, MPC, custodian
 * AltudeGasStation.init(context, apiKey, InitOptions(
 *     signerStrategy = SignerStrategy.multiSig(addresses, threshold)
 * ))
 * ```
 */
sealed class SignerStrategy {
    /**
     * Use Vault as the signer (client-side encrypted key management).
     * This is the default and recommended strategy for most applications.
     *
     * Vault provides:
     * - End-to-end encrypted key storage (never sent to servers)
     * - Biometric/device credential authentication
     * - Session-based caching (no re-prompt for each transaction)
     * - Multi-wallet support (multiple keys from one seed)
     * - Deterministic key derivation (recovery support)
     *
     * @param enableBiometric Whether to require biometric/device credential for key access
     *                         (default: true; false disables biometric requirement)
     * @param sessionTTLSeconds How long to keep vault session open after first unlock in seconds
     *                          (default: 45s; balance between security and UX)
     * @param appId Unique identifier for key derivation and domain separation
     *              (typically app package name; if empty, uses package name automatically)
     * @param walletIndex Which wallet to derive in multi-wallet scenarios
     *                    (default: 0 for single-wallet apps)
     */
    data class VaultDefault(
        val enableBiometric: Boolean = true,
        val sessionTTLSeconds: Int = 45,
        val appId: String = "",
        val walletIndex: Int = 0
    ) : SignerStrategy() {
        init {
            require(sessionTTLSeconds > 0) {
                "sessionTTLSeconds must be positive, got $sessionTTLSeconds"
            }
            require(walletIndex >= 0) {
                "walletIndex must be non-negative, got $walletIndex"
            }
        }
    }

    /**
     * Use an external signer implementation (hardware wallet, multi-sig, custodian, MPC, etc.).
     * Developers provide their own TransactionSigner implementation.
     *
     * External signers are useful for:
     * - Hardware wallets (Ledger, Trezor via custom Signer implementation)
     * - Multi-signature schemes (M-of-N threshold signing)
     * - Custodial solutions (where a service holds keys)
     * - MPC (multi-party computation) signers
     * - Custom key management (BYOK - bring your own key)
     *
     * @param signer Custom TransactionSigner implementation to use for all transactions.
     *               Must implement both publicKey property and signMessage() method.
     */
    data class External(val signer: TransactionSigner) : SignerStrategy()

    companion object {
        /**
         * Convenience factory for Vault with biometric enabled (most common use case).
         *
         * @param sessionTTLSeconds Session timeout in seconds (default: 45)
         * @param appId Vault app identifier (default: empty string, auto-filled from app package)
         * @param walletIndex Wallet index for multi-wallet (default: 0)
         * @return VaultDefault strategy with biometric enabled
         */
        fun vaultWithBiometric(
            sessionTTLSeconds: Int = 45,
            appId: String = "",
            walletIndex: Int = 0
        ): VaultDefault = VaultDefault(
            enableBiometric = true,
            sessionTTLSeconds = sessionTTLSeconds,
            appId = appId,
            walletIndex = walletIndex
        )

        /**
         * Convenience factory for Vault without biometric requirement.
         * Not recommended for production unless you have specific security requirements.
         *
         * @param sessionTTLSeconds Session timeout in seconds (default: 45)
         * @param appId Vault app identifier (default: empty string, auto-filled from app package)
         * @param walletIndex Wallet index for multi-wallet (default: 0)
         * @return VaultDefault strategy with biometric disabled
         */
        fun vaultWithoutBiometric(
            sessionTTLSeconds: Int = 45,
            appId: String = "",
            walletIndex: Int = 0
        ): VaultDefault = VaultDefault(
            enableBiometric = false,
            sessionTTLSeconds = sessionTTLSeconds,
            appId = appId,
            walletIndex = walletIndex
        )

        /**
         * Convenience factory for external signers.
         * Useful for hardware wallets, multi-sig, custodial solutions, etc.
         *
         * @param signer Your custom TransactionSigner implementation
         * @return External strategy wrapping the provided signer
         */
        fun external(signer: TransactionSigner): External = External(signer)

        /**
         * Get the default strategy (Vault with biometric enabled).
         * Recommended strategy for most applications.
         *
         * @return VaultDefault with recommended settings
         */
        fun default(): VaultDefault = VaultDefault()
    }
}
