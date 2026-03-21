package com.altude.vault.storage

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.altude.vault.model.BiometricInvalidatedException
import com.altude.vault.model.VaultDecryptionFailedException
import com.altude.vault.model.VaultInitFailedException
import com.altude.vault.model.VaultStorageCorruptedException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.AEADBadTagException

object VaultStorage {

    private const val TAG               = "VaultStorage"
    private const val VAULT_FILE_PREFIX = "vault_seed_"
    private const val VAULT_FILE_SUFFIX = ".encrypted"
    private const val MASTER_KEY_ALIAS  = "vault_master_key"
    private const val ANDROID_KEYSTORE  = "AndroidKeyStore"

    @Serializable
    data class VaultData(
        @SerialName("seed_blob")     val seedBlob:    String,
        @SerialName("app_id")        val appId:       String,
        @SerialName("created_at_ms") val createdAtMs: Long,
        @SerialName("version")       val version:     Int = 1
    )

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun isKeyPermanentlyInvalidated(e: Exception): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                e.javaClass.name == "android.security.keystore.KeyPermanentlyInvalidatedException"

    private fun isStaleKeyset(e: Exception): Boolean {
        if (e is AEADBadTagException) return true
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is AEADBadTagException) return true
            cause = cause.cause
        }
        return false
    }

    private fun buildMasterKey(context: Context): MasterKey {
        // If a previous version left a stale auth-required key, getKey() returns null.
        // Delete it so Builder creates a fresh non-auth-required key.
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
            if (ks.containsAlias(MASTER_KEY_ALIAS) && ks.getKey(MASTER_KEY_ALIAS, null) == null) {
                Log.w(TAG, "Stale auth-required key detected — deleting.")
                ks.deleteEntry(MASTER_KEY_ALIAS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Keystore inspect failed — continuing: ${e.message}")
        }
        return MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private fun vaultFile(context: Context, appId: String) =
        File(context.filesDir, "$VAULT_FILE_PREFIX${appId}$VAULT_FILE_SUFFIX")

    private fun purgeVault(context: Context, appId: String) {
        vaultFile(context, appId).delete()
        runCatching {
            context.getSharedPreferences(
                "__androidx_security_crypto_encrypted_file_pref__", Context.MODE_PRIVATE
            ).edit().remove(vaultFile(context, appId).absolutePath).apply()
        }
    }

    // ── public API ────────────────────────────────────────────────────────────

    @Suppress("UNUSED_PARAMETER")
    fun initializeKeystore(context: Context, appId: String, requireBiometric: Boolean = true) {
        try {
            buildMasterKey(context)
        } catch (e: Exception) {
            if (isKeyPermanentlyInvalidated(e)) throw BiometricInvalidatedException(cause = e)
            throw VaultInitFailedException("Failed to initialize keystore: ${e.message}", cause = e)
        }
    }

    fun storeSeed(context: Context, appId: String, seedBytes: ByteArray) {
        try {
            val masterKey = buildMasterKey(context)
            val file = vaultFile(context, appId)
            if (file.exists()) file.delete()

            val encryptedFile = EncryptedFile.Builder(
                context, file, masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()

            val vaultData = VaultData(
                seedBlob    = android.util.Base64.encodeToString(seedBytes, android.util.Base64.NO_WRAP),
                appId       = appId,
                createdAtMs = System.currentTimeMillis()
            )
            encryptedFile.openFileOutput().use {
                it.write(Json.encodeToString(VaultData.serializer(), vaultData).toByteArray())
            }
        } catch (e: Exception) {
            if (isKeyPermanentlyInvalidated(e)) throw BiometricInvalidatedException(cause = e)
            if (isStaleKeyset(e)) { purgeVault(context, appId); throw VaultStorageCorruptedException(cause = e) }
            throw VaultInitFailedException("Failed to store seed: ${e.message}", cause = e)
        }
    }

    fun retrieveSeed(context: Context, appId: String): ByteArray {
        try {
            val masterKey = buildMasterKey(context)
            val file = vaultFile(context, appId)
            if (!file.exists()) throw VaultDecryptionFailedException("Vault file not found for appId: $appId")

            val encryptedFile = EncryptedFile.Builder(
                context, file, masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()

            val json = encryptedFile.openFileInput().bufferedReader().use { it.readText() }
            val data = Json.decodeFromString(VaultData.serializer(), json)
            return android.util.Base64.decode(data.seedBlob, android.util.Base64.NO_WRAP)
        } catch (e: VaultDecryptionFailedException) { throw e }
        catch (e: Exception) {
            if (isKeyPermanentlyInvalidated(e)) throw BiometricInvalidatedException(cause = e)
            if (isStaleKeyset(e)) { purgeVault(context, appId); throw VaultStorageCorruptedException(cause = e) }
            throw VaultDecryptionFailedException("Failed to decrypt seed: ${e.message}", cause = e)
        }
    }

    fun vaultExists(context: Context, appId: String): Boolean =
        vaultFile(context, appId).exists()

    fun clearVault(context: Context, appId: String): Boolean {
        val existed = vaultExists(context, appId)
        purgeVault(context, appId)
        return existed
    }
}
