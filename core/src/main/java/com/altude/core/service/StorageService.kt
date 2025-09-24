package com.altude.core.service

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.altude.core.helper.Mnemonic
import foundation.metaplex.solanaeddsa.SolanaEddsa
import kotlinx.serialization.json.Json
import java.io.File
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


    suspend fun storeWalletSeed(accountAddress: String, seedData: SeedData) {
        try {
            val fileName = getSeedFileName(accountAddress)
            val file = File(appContext.filesDir, fileName)

            if (file.exists()) {
                Log.i("SecureStorage", "Seed for $accountAddress already exists. Skipping.")
                return
            }

            val json = Json.encodeToString(seedData)

            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val encryptedFile = EncryptedFile.Builder(
                appContext,
                file,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()

            encryptedFile.openFileOutput().use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
            }

            Log.i("SecureStorage", "Seed stored securely for $accountAddress")
        } catch (e: Exception) {
            Log.e("SecureStorage", "Error storing seed for $accountAddress", e)
            throw Error("Failed to store seed securely: $e")
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
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

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
        val dataFile = dir.listFiles { _, name -> name.endsWith("$accountAddress.dat") && name.startsWith("encrypted_seed_")}?.firstOrNull()
            ?: return null

        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val encryptedFile = EncryptedFile.Builder(
                appContext,
                dataFile, // this is important!
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()

            val decryptedBytes = encryptedFile.openFileInput().use { it.readBytes() }
            Json.decodeFromString<SeedData>(decryptedBytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e("SecureStorage", "Decryption failed for $accountAddress", e)
            throw Error("Decryption failed for $accountAddress")
        }
    }
}
