package com.altude.core.config

/**
 * Configuration options for AltudeGasStation initialization.
 * Allows customization of the signer strategy, biometric requirements, and session behavior.
 *
 * This is the main configuration point for wiring up transaction signing in the Altude SDK.
 * The API is designed to be:
 * - Intuitive: sensible defaults work for most cases
 * - Flexible: easy to customize for advanced use cases
 * - Future-proof: sealed class allows new signer strategies without breaking existing code
 *
 * ## Basic Usage (recommended - Vault with biometrics)
 * ```
 * // One-liner initialization with all defaults
 * AltudeGasStation.init(context, apiKey)
 * ```
 *
 * ## Custom Vault Settings
 * ```
 * AltudeGasStation.init(
 *     context,
 *     apiKey,
 *     InitOptions(
 *         signerStrategy = SignerStrategy.vaultWithBiometric(sessionTTLSeconds = 30)
 *     )
 * )
 * ```
 *
 * ## Custom Vault (no biometric)
 * ```
 * AltudeGasStation.init(
 *     context,
 *     apiKey,
 *     InitOptions(
 *         signerStrategy = SignerStrategy.vaultWithoutBiometric()
 *     )
 * )
 * ```
 *
 * ## External Signer (hardware wallet, multi-sig, etc.)
 * ```
 * val customSigner = MyHardwareWalletSigner()
 * AltudeGasStation.init(
 *     context,
 *     apiKey,
 *     InitOptions(
 *         signerStrategy = SignerStrategy.external(customSigner)
 *     )
 * )
 * ```
 *
 * ## Multi-wallet Vault
 * ```
 * AltudeGasStation.init(
 *     context,
 *     apiKey,
 *     InitOptions(
 *         signerStrategy = SignerStrategy.vaultWithBiometric(walletIndex = 1)
 *     )
 * )
 * ```
 *
 * @param signerStrategy The signer strategy to use (VaultDefault by default, which provides Vault with biometrics)
 * @param enableBiometric Deprecated: use signerStrategy = SignerStrategy.vaultWithBiometric() instead
 * @param sessionTTLSeconds Deprecated: use signerStrategy = SignerStrategy.vaultWithBiometric(sessionTTLSeconds = X) instead
 * @throws IllegalArgumentException if sessionTTLSeconds is not greater than 0
 */
data class InitOptions(
    val signerStrategy: SignerStrategy = SignerStrategy.VaultDefault(),
    val enableBiometric: Boolean = true,
    val sessionTTLSeconds: Int = 45
) {
    init {
        require(sessionTTLSeconds > 0) {
            "sessionTTLSeconds must be greater than 0, got $sessionTTLSeconds"
        }
    }

    companion object {
        /**
         * Factory for default initialization options (Vault with biometrics).
         * This is the recommended configuration for most applications.
         *
         * Equivalent to: InitOptions()
         *
         * @return Default InitOptions with Vault and biometric enabled
         */
        fun default(): InitOptions = InitOptions()

        /**
         * Factory for Vault-only initialization (with biometrics).
         *
         * @param sessionTTLSeconds Session timeout in seconds (default: 45)
         * @param appId Vault app identifier
         * @param walletIndex Wallet index for multi-wallet
         * @return InitOptions configured for Vault with biometric
         */
        fun vault(
            sessionTTLSeconds: Int = 45,
            appId: String = "",
            walletIndex: Int = 0
        ): InitOptions = InitOptions(
            signerStrategy = SignerStrategy.vaultWithBiometric(
                sessionTTLSeconds = sessionTTLSeconds,
                appId = appId,
                walletIndex = walletIndex
            )
        )

        /**
         * Factory for custom external signer initialization.
         *
         * @param signer Your custom TransactionSigner implementation
         * @return InitOptions configured for external signer
         */
        fun custom(signer: com.altude.core.model.TransactionSigner): InitOptions = InitOptions(
            signerStrategy = SignerStrategy.external(signer)
        )

        /**
         * Factory for Vault without biometric requirement.
         * Not recommended for production.
         *
         * @param sessionTTLSeconds Session timeout in seconds (default: 45)
         * @param appId Vault app identifier
         * @param walletIndex Wallet index for multi-wallet
         * @return InitOptions configured for Vault without biometric
         */
        fun vaultNoBiometric(
            sessionTTLSeconds: Int = 45,
            appId: String = "",
            walletIndex: Int = 0
        ): InitOptions = InitOptions(
            signerStrategy = SignerStrategy.vaultWithoutBiometric(
                sessionTTLSeconds = sessionTTLSeconds,
                appId = appId,
                walletIndex = walletIndex
            )
        )
    }
}
