package com.altude.core.model

import com.metaplex.signer.Signer
import com.solana.core.PublicKey

/**
 * TransactionSigner is the Altude SDK abstraction for signing operations.
 * It extends the Metaplex Signer interface to provide a unified interface
 * for all transaction signing implementations across the SDK.
 *
 * All Gas Station signing flows route through this abstraction:
 * - VaultSigner (client-side encrypted keys with biometric auth)
 * - HotSigner (in-memory keys)
 * - External signers (hardware wallets, multi-sig, etc.)
 * - Future signers (MPC, custodians, etc.)
 *
 * Implementations must provide:
 * 1. Public key retrieval for transaction construction
 * 2. Message signing (Ed25519 for Solana compatibility)
 *
 * The interface is designed to be:
 * - Transparent to most developers (auto-selected via SdkConfig)
 * - Overrideable per-transaction for advanced use cases
 * - Future-proof for new signing mechanisms
 */
interface TransactionSigner : Signer {
    /**
     * Get the public key associated with this signer.
     * Used for constructing transactions and transaction validation.
     *
     * @return PublicKey of the signer
     */
    override val publicKey: PublicKey

    /**
     * Sign a transaction message with this signer's key.
     * All Gas Station transactions route through this method.
     *
     * @param message Transaction message bytes to sign
     * @return 64-byte Ed25519 signature
     * @throws Exception if signing fails (e.g., biometric unavailable, key derivation error)
     */
    override suspend fun signMessage(message: ByteArray): ByteArray
}
