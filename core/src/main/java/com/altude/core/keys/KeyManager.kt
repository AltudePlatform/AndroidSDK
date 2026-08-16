package com.altude.core.keys

import com.altude.core.model.TransactionSigner

/**
 * Basic key lifecycle operations used by the SDK.
 *
 * Implementations own persistence and signer construction. Applications can use
 * [LocalKeyManager] for encrypted on-device keys or provide a custom
 * [TransactionSigner] directly to Gas Station.
 */
interface KeyManager {
    suspend fun getOrCreateDefaultSigner(): TransactionSigner
    suspend fun createSigner(): TransactionSigner
    suspend fun importMnemonic(mnemonic: String, passphrase: String = ""): TransactionSigner
    suspend fun importPrivateKey(privateKey: ByteArray): TransactionSigner
    suspend fun getSigner(accountAddress: String): TransactionSigner?
    suspend fun setDefaultAccount(accountAddress: String): TransactionSigner
    suspend fun listAccounts(): List<String>
    suspend fun deleteAccount(accountAddress: String): Boolean
}
