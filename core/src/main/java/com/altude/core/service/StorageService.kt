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
        } else if (privateKey != other.privateKey) {
            return false
        }
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
    private const val TAG = "SecureStorage"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val MASTER_KEY_ALIAS = "altude_secure_master"
    private const val SEED_FILE_PREFIX = "encrypted_seed_"
    private const val SEED_FILE_SUFFIX = ".dat"
    private const val ENCRYPTED_FILE_KEYSET_PREF = "vault_encrypted_file_keyset"
    private const val ENCRYPTED_FILE_KEYSET_ALIAS = "vault_encrypted_file_keyset_alias"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext // prevents memory leaks
    }

    fun clearAll(): Boolean {
        if (!::appContext.isInitialized) {
            Log.w(TAG, "StorageService.clearAll() called before init; nothing to clear")
            return false
        }
        var deletedAny = false
        listEncryptedSeedFiles().forEach { file ->
            if (file.delete()) {
                deletedAny = true
            }
        }
        clearMasterKeyState()
        return deletedAny
    }

    private fun getSeedFileName(accountAddress: String): String = "$SEED_FILE_PREFIX$accountAddress$SEED_FILE_SUFFIX"
    private fun seedFile(accountAddress: String): File = File(appContext.filesDir, getSeedFileName(accountAddress))

    private fun buildMasterKey(): MasterKey =
        MasterKey.Builder(appContext, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    private fun encryptedFile(target: File, masterKey: MasterKey): EncryptedFile =
        EncryptedFile.Builder(
            appContext,
            target,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        )
            .setKeysetPrefName(ENCRYPTED_FILE_KEYSET_PREF)
            .setKeysetAlias(ENCRYPTED_FILE_KEYSET_ALIAS)
            .build()

    private fun writeEncryptedSeed(target: File, serializedSeed: String) {
        val masterKey = buildMasterKey()
        encryptedFile(target, masterKey).openFileOutput().use { output ->
            output.write(serializedSeed.toByteArray(Charsets.UTF_8))
        }
    }

    private fun resetKeyMaterial(seedFile: File) {
        if (seedFile.exists()) {
            seedFile.delete()
        }
        clearMasterKeyState()
    }

    private fun clearMasterKeyState() {
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MASTER_KEY_ALIAS)
            }
        }.onFailure { Log.w(TAG, "Unable to clear keystore alias", it) }

        runCatching {
            appContext.deleteSharedPreferences(ENCRYPTED_FILE_KEYSET_PREF)
        }.onFailure { Log.w(TAG, "Unable to clear encrypted file keyset", it) }
    }

    private fun Throwable.isRecoverableKeystoreError(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is AEADBadTagException) return true
            if (current::class.java.name.contains("KeyStore", ignoreCase = true)) return true
            current = current.cause
        }
        return false
    }

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


    suspend fun storeWalletSeed(accountAddress: String, seedData: SeedData) {
        val targetFile = seedFile(accountAddress)
        if (targetFile.exists()) {
            Log.i(TAG, "Seed for $accountAddress already exists. Skipping.")
            return
        }

        val serializedSeed = Json.encodeToString(seedData)

        try {
            writeEncryptedSeed(targetFile, serializedSeed)
            Log.i(TAG, "Seed stored securely for $accountAddress")
        } catch (first: Exception) {
            if (!first.isRecoverableKeystoreError()) {
                Log.e(TAG, "Error storing seed for $accountAddress", first)
                throw Error("Failed to store seed securely: $first")
            }

            Log.w(TAG, "Keystore mismatch detected for $accountAddress. Resetting key material.", first)
            resetKeyMaterial(targetFile)

            try {
                writeEncryptedSeed(targetFile, serializedSeed)
                Log.i(TAG, "Seed stored securely after keystore reset for $accountAddress")
            } catch (retry: Exception) {
                Log.e(TAG, "Retry failed while storing seed for $accountAddress", retry)
                throw Error("Failed to store seed securely after key reset: $retry")
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
            ?.filter { it.name.startsWith(SEED_FILE_PREFIX) && it.name.endsWith(SEED_FILE_SUFFIX) }
            ?.map { it.name.removePrefix(SEED_FILE_PREFIX).removeSuffix(SEED_FILE_SUFFIX) }
            ?: emptyList()
    }

    private fun listEncryptedSeedFiles(): List<File> {
        return appContext.filesDir
            .listFiles { _, name -> name.startsWith(SEED_FILE_PREFIX) && name.endsWith(SEED_FILE_SUFFIX) }
            ?.toList()
            ?: emptyList()
    }

    fun deleteWallet(accountAddress: String): Boolean = seedFile(accountAddress).delete()

    fun getDecryptedSeeds(): List<SeedData?> {
        val encryptedFiles = listEncryptedSeedFiles()
        val masterKey = buildMasterKey()

        return encryptedFiles.map { file ->
            try {
                val decryptedBytes = encryptedFile(file, masterKey).openFileInput().use { it.readBytes() }
                Json.decodeFromString<SeedData>(decryptedBytes.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                Log.e(TAG, "Error decrypting ${file.name}", e)
                null
            }
        }
    }

    fun getDecryptedSeed(accountAddress: String = ""): SeedData? {
        val targetFile = if (accountAddress.isBlank()) {
            listEncryptedSeedFiles().firstOrNull()
        } else {
            seedFile(accountAddress).takeIf { it.exists() }
        } ?: return null

        return try {
            val masterKey = buildMasterKey()
            val decryptedBytes = encryptedFile(targetFile, masterKey).openFileInput().use { it.readBytes() }
            Json.decodeFromString<SeedData>(decryptedBytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed for $accountAddress", e)
            throw Error("Decryption failed for $accountAddress")
        }
    }
}
