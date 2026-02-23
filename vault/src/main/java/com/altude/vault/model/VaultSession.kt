package com.altude.vault.model

import com.solana.core.PublicKey
import foundation.metaplex.solanaeddsa.keypairs.Keypair

/**
 * Represents an active vault session with a decrypted keypair and TTL.
 * Sessions are time-limited to balance security and user experience.
 *
 * @param keypair The decrypted keypair valid for this session
 * @param publicKey The public key associated with this session
 * @param expiresAtMs Absolute expiration time in milliseconds (System.currentTimeMillis())
 * @param appId Application identifier used during key derivation
 * @param walletIndex Wallet index for multi-wallet support
 */
data class VaultSession(
    val keypair: Keypair,
    val publicKey: PublicKey,
    val expiresAtMs: Long,
    val appId: String,
    val walletIndex: Int
) {
    /**
     * Check if this session is still valid (not expired).
     */
    fun isValid(): Boolean {
        return System.currentTimeMillis() < expiresAtMs
    }

    /**
     * Get remaining time in milliseconds before expiration.
     * Returns 0 if already expired.
     */
    fun remainingTimeMs(): Long {
        val remaining = expiresAtMs - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0
    }
}
