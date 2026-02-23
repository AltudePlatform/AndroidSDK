package com.altude.vault.storage

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.altude.vault.model.BiometricInvalidatedException
import com.altude.vault.model.VaultDecryptionFailedException
import com.altude.vault.model.VaultException
import com.altude.vault.model.VaultInitFailedException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Vault storage handles encrypted persistence of the root seed using Android Keystore.
 * All data is encrypted at rest with AES-256-GCM via EncryptedFile.
 *
 * File location: context.filesDir/vault_seed_<appId>.encrypted
 * Encryption: Android Keystore master key with biometric/device credential gating (optional)
 */
object VaultStorage {

    private const val VAULT_FILE_PREFIX = "vault_seed_"
    private const val VAULT_FILE_SUFFIX = ".encrypted"
    private const val MASTER_KEY_ALIAS = "vault_master_key"

    @Serializable
    data class VaultData(
        @SerialName("seed_blob")
        val seedBlob: String, // Base64-encoded encrypted seed

        @SerialName("app_id")
        val appId: String,

        @SerialName("created_at_ms")
        val createdAtMs: Long,

        @SerialName("version")
        val version: Int = 1 // For future format changes
    )

    /**
     * Initialize vault storage for the given app.
     * Creates a master key in Android Keystore with optional biometric gating.
     *
     * @param context Application context
     * @param appId Unique app identifier for this vault (typically package name)
     * @param requireBiometric Whether to require biometric/device credential (default: true)
     * @throws VaultException if keystore initialization fails
     */
    fun initializeKeystore(
        context: Context,
        appId: String,
        requireBiometric: Boolean = true
    ) {
        try {
            val masterKeySpec = MasterKey.Builder(context, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .apply {
                    if (requireBiometric) {
                        setUserAuthenticationRequired(true, 3600) // 1 hour TTL
                    }
                }
                .build()
            // The MasterKey is automatically stored; this just initializes/validates it
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw BiometricInvalidatedException(cause = e)
        } catch (e: Exception) {
            throw VaultInitFailedException(
                "Failed to initialize keystore: ${e.message}",
                cause = e
            )
        }
    }

    /**
     * Store an encrypted seed blob in vault storage.
     * The seed is serialized to JSON and encrypted using EncryptedFile.
     *
     * @param context Application context
     * @param appId App identifier (used in filename)
     * @param seedBytes Raw seed bytes to encrypt and store
     * @throws VaultException if storage fails
     */
    fun storeSeed(
        context: Context,
        appId: String,
        seedBytes: ByteArray
    ) {
        try {
            val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val vaultFile = File(context.filesDir, "$VAULT_FILE_PREFIX${appId}$VAULT_FILE_SUFFIX")

            val encryptedFile = EncryptedFile.Builder(
                context,
                vaultFile,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()

            val vaultData = VaultData(
                seedBlob = android.util.Base64.encodeToString(seedBytes, android.util.Base64.NO_WRAP),
                appId = appId,
                createdAtMs = System.currentTimeMillis()
            )

            val jsonString = Json.encodeToString(VaultData.serializer(), vaultData)
            encryptedFile.openFileOutput().use { output ->
                output.write(jsonString.toByteArray())
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw BiometricInvalidatedException(cause = e)
        } catch (e: Exception) {
            throw VaultInitFailedException(
                "Failed to store seed: ${e.message}",
                cause = e
            )
        }
    }

    /**
     * Retrieve and decrypt seed bytes from vault storage.
     * This operation may prompt for biometric/device credential if configured.
     *
     * @param context Application context
     * @param appId App identifier (used to locate file)
     * @return Decrypted seed bytes
     * @throws BiometricInvalidatedException if biometric enrollment changed
     * @throws VaultDecryptionFailedException if decryption fails
     */
    fun retrieveSeed(context: Context, appId: String): ByteArray {
        try {
            val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val vaultFile = File(context.filesDir, "$VAULT_FILE_PREFIX${appId}$VAULT_FILE_SUFFIX")
            if (!vaultFile.exists()) {
                throw VaultDecryptionFailedException(
                    "Vault file not found for appId: $appId"
                )
            }

            val encryptedFile = EncryptedFile.Builder(
                context,
                vaultFile,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()

            val jsonString = encryptedFile.openFileInput().bufferedReader().use { it.readText() }
            val vaultData = Json.decodeFromString(VaultData.serializer(), jsonString)

            return android.util.Base64.decode(vaultData.seedBlob, android.util.Base64.NO_WRAP)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw BiometricInvalidatedException(cause = e)
        } catch (e: Exception) {
            throw VaultDecryptionFailedException(
                "Failed to decrypt seed: ${e.message}",
                cause = e
            )
        }
    }

    /**
     * Check if a vault exists for the given app.
     *
     * @param context Application context
     * @param appId App identifier
     * @return true if vault file exists
     */
    fun vaultExists(context: Context, appId: String): Boolean {
        val vaultFile = File(context.filesDir, "$VAULT_FILE_PREFIX${appId}$VAULT_FILE_SUFFIX")
        return vaultFile.exists()
    }

    /**
     * Clear/delete vault storage (destructive operation).
     * This is typically called when resetting the app or on uninstall.
     *
     * @param context Application context
     * @param appId App identifier
     * @return true if vault was deleted, false if didn't exist
     */
    fun clearVault(context: Context, appId: String): Boolean {
        val vaultFile = File(context.filesDir, "$VAULT_FILE_PREFIX${appId}$VAULT_FILE_SUFFIX")
        return vaultFile.delete()
    }
}
