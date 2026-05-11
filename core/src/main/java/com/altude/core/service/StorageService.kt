package com.altude.core.service

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.altude.core.helper.Mnemonic
import foundation.metaplex.solanaeddsa.SolanaEddsa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import kotlinx.serialization.Serializable

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
        if (!privateKey.contentEquals(other.privateKey)) return false
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
    private lateinit var appContext : Context
    fun init(context : Context) {
        appContext  = context.applicationContext // prevents memory leaks
    }

    /**
     * Exposes the application [Context] to other SDK modules (e.g. provenance).
     * Safe to call after [init] has been invoked.
     */
    fun getContext(): Context = appContext

    private val ANDROID_KEYSTORE = "AndroidKeyStore"

    private fun getKeyAlias(): String = "altude_store"
    private fun getSeedFileName(accountAddress: String): String = "encrypted_seed_$accountAddress.dat"

    suspend fun storePrivateKeyByteArray(privateKeyByteArray: ByteArray) {

        val keyPair = SolanaEddsa.createKeypairFromSecretKey(privateKeyByteArray.copyOfRange(0,32))
        val accountAddress = keyPair.publicKey.toBase58()
        val data = SeedData(
            accountAddress,
            "", "",
            privateKey =  privateKeyByteArray,
            type ="privatekey"
        )
        storeWalletSeed(accountAddress,data)
    }
    suspend fun storeMnemonic(seedPhrase: String, passPhrase: String = ""){
        val mnemonic = Mnemonic(seedPhrase, passPhrase)
        val keyPair = mnemonic.getKeyPair()
        val accountAddress = keyPair.publicKey.toBase58()
        val data = SeedData(
            accountAddress,
            seedPhrase, passPhrase,
            privateKey =null,
            type ="mnemonic"
        )
        storeWalletSeed(accountAddress,data)
    }


    private fun getMasterKey(): MasterKey =
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    private fun deleteEncryptedSeedFile(accountAddress: String) {
        val deleted = File(appContext.filesDir, getSeedFileName(accountAddress)).delete()
        Log.w("SecureStorage", "Deleted stale encrypted file for $accountAddress: $deleted")
    }

    /**
     * Deletes the MasterKey entry from the Android Keystore so [getMasterKey] regenerates it,
     * and also purges Tink's encrypted keyset SharedPreferences so they are regenerated with
     * the new master key on the next [EncryptedFile.Builder.build] call.
     *
     * Required when the key is permanently invalidated (e.g. new biometric enrolled, lock screen
     * changed, or Keystore ErrorCode -30).
     */
    private fun deleteKeyStoreEntry() {
        // 1. Delete the AndroidKeyStore master key entry.
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
            val alias = MasterKey.DEFAULT_MASTER_KEY_ALIAS
            if (ks.containsAlias(alias)) {
                ks.deleteEntry(alias)
                Log.w("SecureStorage", "Deleted invalidated KeyStore entry: $alias")
            }
        } catch (ex: Exception) {
            Log.e("SecureStorage", "Failed to delete KeyStore entry", ex)
        }

        // 2. Delete Tink's encrypted keyset SharedPreferences.
        // EncryptedFile stores a per-file keyset in a shared-prefs file named
        // "__androidx_security_crypto_encrypted_file_keyset__".
        // If the master key is gone those keysets are permanently unreadable, so
        // we wipe the entire prefs file so Tink regenerates fresh keysets next time.
        try {
            val tinkPrefsName = "__androidx_security_crypto_encrypted_file_keyset__"
            appContext.getSharedPreferences(tinkPrefsName, Context.MODE_PRIVATE)
                .edit().clear().commit()
            // Also delete the backing XML so there is no stale state.
            val tinkPrefsFile = appContext.filesDir.parentFile
                ?.resolve("shared_prefs/$tinkPrefsName.xml")
            if (tinkPrefsFile?.exists() == true) {
                tinkPrefsFile.delete()
                Log.w("SecureStorage", "Deleted Tink keyset prefs file")
            }
        } catch (ex: Exception) {
            Log.e("SecureStorage", "Failed to delete Tink keyset prefs", ex)
        }
    }

    private suspend fun storeWalletSeedInternal(accountAddress: String, seedData: SeedData) =
        withContext(Dispatchers.IO) {
            val file = File(appContext.filesDir, getSeedFileName(accountAddress))
            val backupFile = File(appContext.filesDir, getSeedFileName(accountAddress) + ".bak")

            // EncryptedFile cannot overwrite an existing file. Instead of deleting it
            // outright (which creates a data-loss window on crash), atomically move it
            // to a backup so we retain a copy during the write. renameTo() is atomic
            // on the same filesystem partition.
            if (file.exists()) {
                // Remove a stale backup left by a previous interrupted write.
                if (backupFile.exists() && !backupFile.delete()) {
                    throw StorageException(
                        "Cannot prepare write for $accountAddress: stale backup file could not be removed"
                    )
                }
                if (!file.renameTo(backupFile)) {
                    throw StorageException(
                        "Cannot prepare write for $accountAddress: existing seed file could not be moved aside"
                    )
                }
                Log.d("SecureStorage", "Moved existing seed file to backup for $accountAddress")
            }

            val json = Json.encodeToString(seedData)

            try {
                val encryptedFile = EncryptedFile.Builder(
                    appContext,
                    file,
                    getMasterKey(),
                    EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                ).build()

                encryptedFile.openFileOutput().use { output ->
                    output.write(json.toByteArray(Charsets.UTF_8))
                }

                // Write succeeded; the new file is in place — remove the backup.
                if (backupFile.exists() && !backupFile.delete()) {
                    Log.w("SecureStorage", "Backup file could not be removed after successful write for $accountAddress")
                }
                Log.i("SecureStorage", "Seed stored securely for $accountAddress")
            } catch (e: Exception) {
                // Write failed; restore the backup to prevent data loss.
                if (backupFile.exists()) {
                    if (file.exists() && !file.delete()) {
                        Log.e("SecureStorage", "Failed to remove partial write for $accountAddress during restore")
                    }
                    if (backupFile.renameTo(file)) {
                        Log.w("SecureStorage", "Restored backup seed file for $accountAddress after write failure")
                    } else {
                        Log.e("SecureStorage", "Failed to restore backup seed file for $accountAddress")
                    }
                }
                throw e
            }
        }

    /**
     * Walks the full exception cause chain to check if the exception or any of its causes
     * indicates a key invalidation scenario.
     */
    private fun isKeyInvalidatedException(e: Throwable): Boolean {
        var current: Throwable? = e
        while (current != null) {
            // Check for specific invalidation exception types
            if (current is AEADBadTagException) {
                return true
            }
            
            // Check for KeyPermanentlyInvalidatedException by class name
            // (android.security.keystore.KeyPermanentlyInvalidatedException)
            if (current.javaClass.simpleName == "KeyPermanentlyInvalidatedException") {
                return true
            }
            
            // Check for specific error messages that indicate key invalidation
            val msg = current.message ?: ""
            if (msg.contains("VERIFICATION_FAILED", ignoreCase = true)
                || msg.contains("verification failed", ignoreCase = true)
                || msg.contains("ErrorCode(-30)", ignoreCase = true)
                || msg.contains("Key permanently invalidated", ignoreCase = true)
            ) {
                return true
            }
            
            current = current.cause
        }
        return false
    }

    suspend fun storeWalletSeed(accountAddress: String, seedData: SeedData, overwrite: Boolean = false) {
        // By default, do not overwrite an existing seed file (wallet seeds are sensitive).
        // Callers must explicitly pass overwrite=true to replace an existing seed.
        if (!overwrite) {
            val file = File(appContext.filesDir, getSeedFileName(accountAddress))
            if (file.exists()) {
                Log.i("SecureStorage", "Seed already exists. Skipping.")
                return
            }
        }

        try {
            storeWalletSeedInternal(accountAddress, seedData)
        } catch (e: Exception) {
            if (isKeyInvalidatedException(e)) {
                Log.w("SecureStorage", "Key invalidated — purging stale key+file and retrying.")
                deleteEncryptedSeedFile(accountAddress)
                deleteKeyStoreEntry()
                try {
                    storeWalletSeedInternal(accountAddress, seedData)
                } catch (retryEx: Exception) {
                    Log.e("SecureStorage", "Retry failed", retryEx)
                    throw StorageException("Failed to store seed after key recovery for $accountAddress", retryEx)
                }
            } else {
                Log.e("SecureStorage", "Error storing seed", e)
                throw StorageException("Failed to store seed securely for $accountAddress", e)
            }
        }
    }


    //    fun retrieveDecryptedSeed(accountAddress: String): SeedData? {
//        try {
//            val file = File(context.filesDir, getSeedFileName(accountAddress))
//            if (!file.exists()) return null
//
//            val encryptedBytes = FileInputStream(file).use { it.readBytes() }
//
//            val keyAlias = getKeyAlias()
//            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
//            keyStore.load(null)
//
//            if (!keyStore.containsAlias(keyAlias)) return null
//
//            val privateKey = keyStore.getKey(keyAlias, null) as PrivateKey
//            val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
//            cipher.init(Cipher.DECRYPT_MODE, privateKey)
//            val decryptedJson = cipher.doFinal(encryptedBytes).toString(Charsets.UTF_8)
//            val data = Json.decodeFromString<SeedData>(decryptedJson)
//
//            return data
//
//        } catch (e: Exception) {
//            Log.e("SecureStorage", "Error retrieving seed for $accountAddress", e)
//            return null
//        }
//    }
    fun listStoredWalletAddresses(): List<String> {
        return appContext.filesDir.listFiles()
            ?.filter { it.name.startsWith("encrypted_seed_") && it.name.endsWith(".dat") }
            ?.map { it.name.removePrefix("encrypted_seed_").removeSuffix(".dat") }
            ?: emptyList()
    }
    private fun listEncryptedSeedFiles(): List<File> {
        val dir = appContext.filesDir
        return dir.listFiles { _, name -> name.endsWith(".dat") }?.toList() ?: emptyList()
    }
    fun deleteWallet(accountAddress: String): Boolean {
        val file = File(appContext.filesDir, getSeedFileName(accountAddress))
        return file.delete()
    }

    fun getDecryptedSeeds(): List<SeedData?> {
        val encryptedFiles = listEncryptedSeedFiles()
        val masterKey = getMasterKey()

        return encryptedFiles.map { file ->
            try {
                val encryptedFile = EncryptedFile.Builder(
                    appContext,
                    file,
                    masterKey,
                    EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                ).build()

                val decryptedBytes = encryptedFile.openFileInput().use { it.readBytes() }
                Json.decodeFromString<SeedData>(decryptedBytes.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                Log.e("SecureStorage", "Error decrypting ${file.name}", e)
                null
            }
        }
    }

    fun getDecryptedSeed(accountAddress: String = ""): SeedData? {
        val dir = appContext.filesDir
        val dataFile = dir.listFiles { _, name ->
            name.endsWith("$accountAddress.dat") && name.startsWith("encrypted_seed_")
        }?.firstOrNull() ?: return null

        return try {
            val encryptedFile = EncryptedFile.Builder(
                appContext,
                dataFile,
                getMasterKey(),
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()

            val decryptedBytes = encryptedFile.openFileInput().use { it.readBytes() }
            Json.decodeFromString<SeedData>(decryptedBytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e("SecureStorage", "Decryption failed for $accountAddress", e)
            throw StorageException("Decryption failed for $accountAddress", e)
        }
    }
}

class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause)
