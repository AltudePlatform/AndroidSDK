package com.altude.vault.crypto

import foundation.metaplex.solanaeddsa.Ed25519
import foundation.metaplex.solanaeddsa.keypairs.SolanaKeypair
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.util.DigestFactory
import java.nio.charset.StandardCharsets

/**
 * Vault cryptographic utilities for key derivation and signing.
 * Implements deterministic Ed25519 key derivation from a root seed using HKDF-SHA256.
 *
 * All key derivation is domain-separated by appId and walletIndex to prevent key collision
 * across multiple apps or wallet instances.
 */
object VaultCrypto {

    private const val HKDF_INFO_PREFIX = "altude:vault:solana"
    private const val HKDF_SALT = "altude_vault_salt"
    private const val SEED_LENGTH = 32

    /**
     * Derive an Ed25519 keypair from a root seed using HKDF-SHA256.
     * The derivation is deterministic and domain-separated by appId and walletIndex.
     *
     * Process:
     * 1. Perform HKDF extraction with salt "altude_vault_salt"
     * 2. Expand with domain: "altude:vault:solana::<appId>:<walletIndex>"
     * 3. Take first 32 bytes as Ed25519 seed
     * 4. Derive keypair from seed for Solana Ed25519 signing
     *
     * @param seed Root seed (minimum 32 bytes)
     * @param appId Application identifier for domain separation (typically package name)
     * @param walletIndex Wallet index for multi-wallet support (default: 0)
     * @return SolanaKeypair containing derived public and private keys compatible with Solana
     * @throws IllegalArgumentException if seed is too short
     */
    fun deriveKeypair(
        seed: ByteArray,
        appId: String,
        walletIndex: Int = 0
    ): SolanaKeypair {
        require(seed.size >= SEED_LENGTH) {
            "Seed must be at least $SEED_LENGTH bytes, got ${seed.size}"
        }

        // Create HKDF with SHA256
        val hkdf = HKDFBytesGenerator(DigestFactory.createSHA256())

        // Prepare salt and info for domain separation
        val salt = HKDF_SALT.toByteArray(StandardCharsets.UTF_8)
        val info = "$HKDF_INFO_PREFIX::$appId:$walletIndex"
            .toByteArray(StandardCharsets.UTF_8)

        // Initialize with seed (as IKM), salt (optional), and info
        val params = HKDFParameters(seed, salt, info)
        hkdf.init(params)

        // Generate 32 bytes for Ed25519 seed
        val derivedSeed = ByteArray(SEED_LENGTH)
        hkdf.generateBytes(derivedSeed, 0, SEED_LENGTH)

        // Create Ed25519 keypair from derived seed using Solana's Ed25519 implementation
        val keypair = Ed25519.createKeypairFromSeed(derivedSeed)

        return SolanaKeypair(keypair.publicKey, keypair.secretKey)
    }

    /**
     * Sign a message using an Ed25519 keypair.
     * This is a convenience wrapper around the Metaplex Ed25519 signing.
     *
     * @param message Message bytes to sign
     * @param keypair The keypair to sign with
     * @return 64-byte Ed25519 signature
     */
    fun signMessage(message: ByteArray, keypair: SolanaKeypair): ByteArray {
        return Ed25519.sign(message, keypair.secretKey)
    }

    /**
     * Generate a cryptographically secure random seed for vault initialization.
     * Uses SecureRandom via Java crypto provider.
     *
     * @param lengthBytes Length of seed to generate (default: 32 bytes for Ed25519)
     * @return Random seed bytes
     */
    fun generateRandomSeed(lengthBytes: Int = SEED_LENGTH): ByteArray {
        val seed = ByteArray(lengthBytes)
        java.security.SecureRandom().nextBytes(seed)
        return seed
    }
}
