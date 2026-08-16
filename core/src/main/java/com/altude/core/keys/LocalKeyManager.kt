package com.altude.core.keys

import android.content.Context
import com.altude.core.helper.Mnemonic
import com.altude.core.model.HotSigner
import com.altude.core.model.TransactionSigner
import com.altude.core.service.StorageService
import foundation.metaplex.solanaeddsa.SolanaEddsa
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Simple encrypted on-device key management backed by Android Keystore.
 *
 * This implementation is intended for straightforward local-wallet integrations.
 * Applications with hardware, biometric, MPC, or remote-signing requirements can
 * provide their own [TransactionSigner] instead.
 */
class LocalKeyManager(context: Context) : KeyManager {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    init {
        StorageService.init(context)
    }

    override suspend fun getOrCreateDefaultSigner(): TransactionSigner = keyMutex.withLock {
        val configuredAddress = preferences.getString(DEFAULT_ACCOUNT_KEY, null)
        configuredAddress?.let { loadSigner(it) }?.let { return@withLock it }

        var firstLoadFailure: IllegalStateException? = null
        for (address in StorageService.listStoredWalletAddresses().sorted()) {
            try {
                loadSigner(address)?.let {
                    setDefaultAddress(address)
                    return@withLock it
                }
            } catch (error: IllegalStateException) {
                if (firstLoadFailure == null) {
                    firstLoadFailure = error
                }
            }
        }

        firstLoadFailure?.let { throw it }
        createSignerLocked(setAsDefault = true)
    }

    override suspend fun createSigner(): TransactionSigner = keyMutex.withLock {
        createSignerLocked(setAsDefault = !preferences.contains(DEFAULT_ACCOUNT_KEY))
    }

    override suspend fun importMnemonic(
        mnemonic: String,
        passphrase: String
    ): TransactionSigner = keyMutex.withLock {
        require(mnemonic.isNotBlank()) { "Mnemonic must not be blank" }
        val keyPair = Mnemonic(mnemonic, passphrase).getKeyPair()
        StorageService.storeMnemonic(mnemonic, passphrase)
        if (!preferences.contains(DEFAULT_ACCOUNT_KEY)) {
            setDefaultAddress(keyPair.publicKey.toBase58())
        }
        HotSigner(keyPair)
    }

    override suspend fun importPrivateKey(privateKey: ByteArray): TransactionSigner =
        keyMutex.withLock {
            require(privateKey.size >= PRIVATE_KEY_SEED_LENGTH) {
                "Private key must contain at least $PRIVATE_KEY_SEED_LENGTH bytes"
            }
            val keyPair = SolanaEddsa.createKeypairFromSecretKey(
                privateKey.copyOfRange(0, PRIVATE_KEY_SEED_LENGTH)
            )
            StorageService.storePrivateKeyByteArray(privateKey.copyOf())
            if (!preferences.contains(DEFAULT_ACCOUNT_KEY)) {
                setDefaultAddress(keyPair.publicKey.toBase58())
            }
            HotSigner(keyPair)
        }

    override suspend fun getSigner(accountAddress: String): TransactionSigner? =
        keyMutex.withLock {
            requireValidAddress(accountAddress)
            loadSigner(accountAddress)
        }

    override suspend fun setDefaultAccount(accountAddress: String): TransactionSigner =
        keyMutex.withLock {
            requireValidAddress(accountAddress)
            val signer = requireNotNull(loadSigner(accountAddress)) {
                "No locally managed account found for $accountAddress"
            }
            setDefaultAddress(accountAddress)
            signer
        }

    override suspend fun listAccounts(): List<String> = keyMutex.withLock {
        StorageService.listStoredWalletAddresses().sorted()
    }

    override suspend fun deleteAccount(accountAddress: String): Boolean =
        keyMutex.withLock {
            requireValidAddress(accountAddress)
            val deleted = StorageService.deleteWallet(accountAddress)
            if (deleted && preferences.getString(DEFAULT_ACCOUNT_KEY, null) == accountAddress) {
                preferences.edit().remove(DEFAULT_ACCOUNT_KEY).apply()
            }
            deleted
        }

    private suspend fun createSignerLocked(setAsDefault: Boolean): TransactionSigner {
        val mnemonic = Mnemonic.generateMnemonic()
        val keyPair = Mnemonic(mnemonic).getKeyPair()
        StorageService.storeMnemonic(mnemonic)
        if (setAsDefault) {
            setDefaultAddress(keyPair.publicKey.toBase58())
        }
        return HotSigner(keyPair)
    }

    private suspend fun loadSigner(accountAddress: String): TransactionSigner? =
        StorageService.getDecryptedSeedKeyPair(accountAddress)?.let(::HotSigner)

    private fun setDefaultAddress(accountAddress: String) {
        preferences.edit().putString(DEFAULT_ACCOUNT_KEY, accountAddress).apply()
    }

    private fun requireValidAddress(accountAddress: String) {
        require(
            accountAddress.isNotBlank() &&
                !accountAddress.contains('/') &&
                !accountAddress.contains('\\')
        ) {
            "Account address must be non-blank and contain no path separators"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "altude_local_keys"
        const val DEFAULT_ACCOUNT_KEY = "default_account"
        const val PRIVATE_KEY_SEED_LENGTH = 32
        val keyMutex = Mutex()
    }
}
