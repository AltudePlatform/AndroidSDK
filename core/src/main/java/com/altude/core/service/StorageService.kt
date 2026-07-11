package com.altude.core.service

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.altude.core.helper.Mnemonic
import foundation.metaplex.solanaeddsa.SolanaEddsa
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import foundation.metaplex.solanaeddsa.Keypair

@Serializable
data class SeedData(
    val accountAddress: String = "",
    val mnemonic: String = "",
    val passphrase: String,
    val privateKey: ByteArray?,
    val type: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SeedData
        if (accountAddress != other.accountAddress) return false
        if (mnemonic != other.mnemonic) return false
        if (passphrase != other.passphrase) return false
        if (privateKey != null && other.privateKey != null) {
            if (!privateKey.contentEquals(other.privateKey)) return false
        } else if (privateKey != other.privateKey) return false
        if (type != other.type) return false
        return true
    }

    override fun hashCode(): Int {
        var result = accountAddress.hashCode()
        result = 31 * result + mnemonic.hashCode()
        result = 31 * result + passphrase.hashCode()
        result = 31 * result + (privateKey?.contentHashCode() ?: 0)
        result = 31 * result + type.hashCode()
        return result
    }
}

object StorageService {
    private const val TAG                      = "SecureStorage"
    private const val ANDROID_KEYSTORE         = "AndroidKeyStore"
    private const val MASTER_KEY_ALIAS         = "altude_secure_master"
    private const val SEED_FILE_PREFIX         = "encrypted_seed_"
    private const val SEED_FILE_SUFFIX         = ".dat"
    private const val ENCRYPTED_FILE_KEYSET_PREF  = "vault_encrypted_file_keyset"
    private const val ENCRYPTED_FILE_KEYSET_ALIAS = "vault_encrypted_file_keyset_alias"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ── MasterKey ─────────────────────────────────────────────────────────────

    private fun buildMasterKey(): MasterKey {
        // Guard: if a stale auth-required key exists, getKey() returns null → delete it
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
            if (ks.containsAlias(MASTER_KEY_ALIAS) && ks.getKey(MASTER_KEY_ALIAS, null) == null) {
                Log.w(TAG, "Stale auth-required key '$MASTER_KEY_ALIAS' — deleting.")
                ks.deleteEntry(MASTER_KEY_ALIAS)
                runCatching { appContext.deleteSharedPreferences(ENCRYPTED_FILE_KEYSET_PREF) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not inspect keystore alias: ${e.message}")
        }
        return MasterKey.Builder(appContext, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private fun encryptedFile(target: File, masterKey: MasterKey): EncryptedFile =
        EncryptedFile.Builder(
            appContext, target, masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        )
            .setKeysetPrefName(ENCRYPTED_FILE_KEYSET_PREF)
            .setKeysetAlias(ENCRYPTED_FILE_KEYSET_ALIAS)
            .build()

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun getSeedFileName(addr: String) = "$SEED_FILE_PREFIX$addr$SEED_FILE_SUFFIX"
    private fun seedFile(addr: String) = File(appContext.filesDir, getSeedFileName(addr))

    private fun listEncryptedSeedFiles(): List<File> =
        appContext.filesDir
            .listFiles { _, n -> n.startsWith(SEED_FILE_PREFIX) && n.endsWith(SEED_FILE_SUFFIX) }
            ?.toList() ?: emptyList()

    private fun Throwable.isRecoverableKeystoreError(): Boolean {
        var cur: Throwable? = this
        while (cur != null) {
            if (cur is AEADBadTagException) return true
            if (cur::class.java.name.contains("KeyStore", ignoreCase = true)) return true
            cur = cur.cause
        }
        return false
    }

    private fun clearMasterKeyState() {
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
            if (ks.containsAlias(MASTER_KEY_ALIAS)) ks.deleteEntry(MASTER_KEY_ALIAS)
        }
        runCatching { appContext.deleteSharedPreferences(ENCRYPTED_FILE_KEYSET_PREF) }
    }

    private fun writeEncryptedSeed(target: File, json: String) {
        encryptedFile(target, buildMasterKey()).openFileOutput().use {
            it.write(json.toByteArray(Charsets.UTF_8))
        }
    }

    // ── public API ────────────────────────────────────────────────────────────

    fun clearAll(): Boolean {
        if (!::appContext.isInitialized) return false
        listEncryptedSeedFiles().forEach { it.delete() }
        clearMasterKeyState()
        return true
    }

    fun listStoredWalletAddresses(): List<String> =
        appContext.filesDir.listFiles()
            ?.filter { it.name.startsWith(SEED_FILE_PREFIX) && it.name.endsWith(SEED_FILE_SUFFIX) }
            ?.map { it.name.removePrefix(SEED_FILE_PREFIX).removeSuffix(SEED_FILE_SUFFIX) }
            ?: emptyList()

    fun deleteWallet(accountAddress: String): Boolean = seedFile(accountAddress).delete()

    suspend fun storePrivateKeyByteArray(privateKeyByteArray: ByteArray) {
        val keyPair = SolanaEddsa.createKeypairFromSecretKey(privateKeyByteArray.copyOfRange(0, 32))
        storeWalletSeed(keyPair.publicKey.toBase58(), SeedData(
            keyPair.publicKey.toBase58(), "", "", privateKey = privateKeyByteArray, type = "privatekey"
        ))
    }

    suspend fun storeMnemonic(seedPhrase: String, passPhrase: String = "") {
        val mnemonic = Mnemonic(seedPhrase, passPhrase)
        val address = mnemonic.getKeyPair().publicKey.toBase58()
        storeWalletSeed(address, SeedData(address, seedPhrase, passPhrase, null, "mnemonic"))
    }

    suspend fun storeWalletSeed(accountAddress: String, seedData: SeedData) {
        val file = seedFile(accountAddress)
        if (file.exists()) { Log.i(TAG, "Seed for $accountAddress already exists. Skipping."); return }

        val json = Json.encodeToString(seedData)
        try {
            writeEncryptedSeed(file, json)
            Log.i(TAG, "Seed stored for $accountAddress")
        } catch (first: Exception) {
            if (!first.isRecoverableKeystoreError()) {
                Log.e(TAG, "Error storing seed for $accountAddress", first)
                throw RuntimeException("Failed to store seed: $first", first)
            }
            Log.w(TAG, "Keystore mismatch — resetting key material for $accountAddress", first)
            file.delete()
            clearMasterKeyState()
            try {
                writeEncryptedSeed(file, json)
                Log.i(TAG, "Seed stored after key reset for $accountAddress")
            } catch (retry: Exception) {
                Log.e(TAG, "Retry failed for $accountAddress", retry)
                throw RuntimeException("Failed to store seed after reset: $retry", retry)
            }
        }
    }

    fun getDecryptedSeed(accountAddress: String = ""): SeedData? {
        val file = if (accountAddress.isBlank()) listEncryptedSeedFiles().firstOrNull()
                   else seedFile(accountAddress).takeIf { it.exists() }
        file ?: return null
        return try {
            val bytes = encryptedFile(file, buildMasterKey()).openFileInput().use { it.readBytes() }
            Json.decodeFromString<SeedData>(bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed for $accountAddress", e)
            null
        }
    }

    fun getDecryptedSeeds(): List<SeedData?> =
        listEncryptedSeedFiles().map { file ->
            try {
                val bytes = encryptedFile(file, buildMasterKey()).openFileInput().use { it.readBytes() }
                Json.decodeFromString<SeedData>(bytes.toString(Charsets.UTF_8))
            } catch (e: Exception) { Log.e(TAG, "Error decrypting ${file.name}", e); null }
        }

    suspend fun getDecryptedSeedKeyPair(accountAddress: String): Keypair? {
        require(accountAddress.isNotBlank() && !accountAddress.contains('/') && !accountAddress.contains('\\')) {
            "Invalid accountAddress: must be non-blank and contain no path separators"
        }
        val seedData = getDecryptedSeed(accountAddress) ?: return null
        return when (seedData.type) {
            "mnemonic" -> {
                val mnemonic = Mnemonic(seedData.mnemonic, seedData.passphrase)
                mnemonic.getKeyPair()
            }
            "privatekey" -> {
                seedData.privateKey?.let { SolanaEddsa.createKeypairFromSecretKey(it.copyOfRange(0, 32)) }
            }
            else -> null
        }
    }
}
