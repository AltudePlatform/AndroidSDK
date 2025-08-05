package com.altude.core.service

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.util.Log
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.altude.core.helper.Mnemonic
import com.altude.core.model.KeyPair
import kotlinx.serialization.json.Json
import java.security.KeyPairGenerator
import java.security.KeyStore
import javax.crypto.Cipher
import java.io.File
import kotlinx.serialization.Serializable
import java.security.PrivateKey
import kotlin.io.encoding.Base64

@Serializable
data class SeedData(
    val accountAddress: String,
    val mnemonic: String,
    val passphrase: String,
    val privateKeyBase64: String?,
    val type: String
)

object StorageService {
    private lateinit var appContext : Context
    fun init(context : Context) {
        appContext  = context.applicationContext // prevents memory leaks
    }
    private val ANDROID_KEYSTORE = "AndroidKeyStore"

    private fun getKeyAlias(): String = "altude_store"
    private fun getSeedFileName(accountAddress: String): String = "encrypted_seed_$accountAddress.dat"

    suspend fun storePrivateKeyByteArray(privateKeyByteArray: ByteArray) {

        val keyPair = KeyPair.solanaKeyPairFromPrivateKey(privateKeyByteArray)
        val accountAddress = keyPair.publicKey.toBase58()
        val data = SeedData(
            accountAddress,
            "", "",
            privateKeyBase64 = Base64.encode( privateKeyByteArray),
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
            privateKeyBase64 =null,
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
    fun listEncryptedSeedFiles(): List<File> {
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
        val dataFile = dir.listFiles { _, name -> name.endsWith("$accountAddress.dat") }?.firstOrNull()
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

//    private fun writeMetadata(accountAddress: String, type: String) {
//        val metaFile = File(context.filesDir, "${getSeedFileName(accountAddress)}.meta.json")
//        metaFile.writeText("""{ "type": "$type" }""")
//    }
//    fun getStoredSeedType(accountAddress: String): String? {
//        val metaFile = File(context.filesDir, "${getSeedFileName(accountAddress)}.meta.json")
//        return if (metaFile.exists()) {
//            val text = metaFile.readText()
//            Regex(""""type"\s*:\s*"(\w+)"""").find(text)?.groupValues?.get(1)
//        } else null
//    }
}
