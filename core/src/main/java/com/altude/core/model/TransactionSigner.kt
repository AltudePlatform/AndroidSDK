package com.altude.core.model

import com.metaplex.signer.Signer
import foundation.metaplex.solanapublickeys.PublicKey

/**
 * TransactionSigner is the Altude SDK abstraction for signing operations.
 * It extends the Metaplex Signer interface to provide a unified interface
 * for all transaction signing implementations across the SDK.
 *
 * The application owns key custody — the SDK never generates, stores, or
 * defaults to a signer implementation. All Gas Station signing flows route
 * through this abstraction, implemented by the host application, for example:
 * - HotSigner (in-memory keys, provided by this SDK for apps to construct explicitly)
 * - Hardware-backed or KMS-backed signers
 * - External signers (hardware wallets, multi-sig, etc.)
 * - Future signers (MPC, custodians, etc.)
 *
 * Implementations must provide:
 * 1. Public key retrieval for transaction construction
 * 2. Message signing (Ed25519 for Solana compatibility)
 *
 * The interface is designed to be:
 * - Explicitly provided by the application (via AltudeGasStation.init, SdkConfig.setSigner,
 *   or per-operation overrides)
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
