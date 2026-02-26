package com.altude.android

import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.altude.gasstation.AltudeGasStation
import com.altude.core.config.SdkConfig
import com.altude.core.config.InitOptions
import com.altude.core.config.SignerStrategy
import com.altude.vault.model.*

/**
 * Error Handling Example Activity
 *
 * Demonstrates common error scenarios and recovery patterns:
 * - Biometric unavailable (guide user to set up)
 * - Biometric invalidated (old keys unusable)
 * - Authentication failed (retry with guidance)
 * - Storage issues (check permissions)
 * - Session expired (automatic re-prompt)
 *
 * Key learning: Every error includes remediation messages
 */
class ErrorHandlingExampleActivity : AppCompatActivity() {
    
    private val apiKey = "ak_xECEd2kxw8siDNxUXAhfGIJf_YJ7nUrZx-fAHXg9NJk"
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var testButton: Button
    private lateinit var errorCodeText: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_error_handling_example)
        
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        testButton = findViewById(R.id.testButton)
        errorCodeText = findViewById(R.id.errorCodeText)
        
        updateStatus("Error Handling Examples\n\n" +
                "Tap 'Test Error' to simulate different error scenarios.")
        
        testButton.setOnClickListener { showErrorScenarioDialog() }
    }
    
    private fun showErrorScenarioDialog() {
        val scenarios = arrayOf(
            "1. Biometric Not Available",
            "2. Biometric Invalidated",
            "3. Authentication Failed",
            "4. Vault Locked",
            "5. Session Expired",
            "6. Storage Permission Error"
        )
        
        AlertDialog.Builder(this)
            .setTitle("Select Error Scenario")
            .setItems(scenarios) { _, which ->
                when (which) {
                    0 -> testBiometricNotAvailable()
                    1 -> testBiometricInvalidated()
                    2 -> testAuthenticationFailed()
                    3 -> testVaultLocked()
                    4 -> testSessionExpired()
                    5 -> testStorageError()
                }
            }
            .show()
    }
    
    /**
     * Error Scenario 1: Biometric Not Available
     *
     * When: Device has no fingerprint/face/PIN enrollment
     * Error Code: VAULT-0201
     * Recovery: Guide user to device settings to enroll biometric
     */
    private fun testBiometricNotAvailable() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("Simulating: Biometric Not Available")
                
                // In real scenario, would be thrown during AltudeGasStation.init()
                // For demo, we throw it manually
                throw BiometricNotAvailableException(
                    message = "No biometric enrollment detected on device",
                    remediation = "1. Open Settings\n" +
                            "2. Go to Security > Fingerprint (or Biometric)\n" +
                            "3. Enroll your fingerprint, face, or set PIN\n" +
                            "4. Return to app and retry"
                )
                
            } catch (e: BiometricNotAvailableException) {
                showProgress(false)
                displayError(e)
                
                // Offer action
                showActionDialog(
                    title = "Set Up Biometric",
                    message = e.remediation,
                    actionLabel = "Open Settings",
                    action = { openBiometricSettings() }
                )
                
            } catch (e: VaultException) {
                showProgress(false)
                displayError(e)
            }
        }
    }
    
    /**
     * Error Scenario 2: Biometric Invalidated
     *
     * When: User changed fingerprints, face enrollment, or PIN
     * Error Code: VAULT-0202
     * Impact: CRITICAL - existing vault keys cannot be recovered
     * Recovery: Clear app data and reinitialize (data loss)
     */
    private fun testBiometricInvalidated() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("Simulating: Biometric Invalidated")
                
                throw BiometricInvalidatedException(
                    message = "Biometric credentials have been invalidated",
                    remediation = "Your vault encryption keys are no longer accessible for security.\n\n" +
                            "Options:\n" +
                            "1. Clear App Data (lose current vault)\n" +
                            "2. Uninstall & Reinstall\n" +
                            "3. (Advanced) Restore from backup\n\n" +
                            "This is a security feature to prevent unauthorized access."
                )
                
            } catch (e: BiometricInvalidatedException) {
                showProgress(false)
                displayError(e)
                
                // Critical error - offer clear options
                AlertDialog.Builder(this@ErrorHandlingExampleActivity)
                    .setTitle("Security Update Required")
                    .setMessage(e.remediation)
                    .setPositiveButton("Clear App Data") { _, _ ->
                        clearAppDataGracefully()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                
            } catch (e: VaultException) {
                showProgress(false)
                displayError(e)
            }
        }
    }
    
    /**
     * Error Scenario 3: Authentication Failed
     *
     * When: User provides wrong fingerprint/face or cancels prompt
     * Error Code: VAULT-0203 (auth failed) or VAULT-0205 (cancelled)
     * Recovery: Allow retry (transient error)
     */
    private fun testAuthenticationFailed() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("Simulating: Authentication Failed")
                
                throw BiometricAuthenticationFailedException(
                    failureReason = BiometricAuthenticationFailedException.FailureReason.AuthenticationFailed,
                    message = "Biometric authentication failed",
                    remediation = "Your fingerprint/face didn't match.\n\n" +
                            "Please try again. Make sure your fingers are clean and dry,\n" +
                            "and your face is well-lit and fully visible."
                )
                
            } catch (e: BiometricAuthenticationFailedException) {
                showProgress(false)
                displayError(e)
                
                // Transient error - allow retry
                when (e.failureReason) {
                    BiometricAuthenticationFailedException.FailureReason.UserCancelled -> {
                        updateStatus("❌ Cancelled\n\n" +
                                "Transaction was cancelled.\n" +
                                "Tap 'Test Error' to retry.")
                    }
                    BiometricAuthenticationFailedException.FailureReason.TooManyAttempts -> {
                        updateStatus("❌ Locked Out\n\n" +
                                "Too many failed attempts.\n" +
                                "Try again in 30 seconds.")
                    }
                    else -> {
                        updateStatus("❌ Authentication Failed\n\n" +
                                "Please try again.\n\n" +
                                "Tips:\n" +
                                "- Keep fingers clean and dry\n" +
                                "- Ensure good lighting\n" +
                                "- Remove screen protector if problematic")
                    }
                }
                
            } catch (e: VaultException) {
                showProgress(false)
                displayError(e)
            }
        }
    }
    
    /**
     * Error Scenario 4: Vault Locked
     *
     * When: Vault not initialized or not unlocked
     * Error Code: VAULT-0401
     * Recovery: Initialize vault with AltudeGasStation.init()
     */
    private fun testVaultLocked() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("Simulating: Vault Locked")
                
                // Try to use signer without initialization
                val signer = SdkConfig.currentSigner
                    ?: throw VaultLockedException(
                        message = "Vault is not initialized",
                        remediation = "Call AltudeGasStation.init(context, apiKey) first"
                    )
                
            } catch (e: VaultLockedException) {
                showProgress(false)
                displayError(e)
                
                updateStatus("❌ Vault Locked\n\n" +
                        "The vault must be initialized before use.\n\n" +
                        "Solution: Call AltudeGasStation.init() during app startup.")
                
            } catch (e: VaultException) {
                showProgress(false)
                displayError(e)
            }
        }
    }
    
    /**
     * Error Scenario 5: Session Expired
     *
     * When: Session-based TTL expires (advanced mode)
     * Error Code: VAULT-0402
     * Recovery: Automatic - signer re-prompts for biometric
     */
    private fun testSessionExpired() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("Simulating: Session Expired")
                
                // This would happen in session-based mode after TTL passes
                throw VaultException(
                    errorCode = VaultErrorCodes.SESSION_EXPIRED,
                    message = "Authentication session has expired",
                    remediation = "Session timeout reached (45 seconds).\n\n" +
                            "Biometric will be requested again for next operation.\n" +
                            "This is the expected behavior in session-based mode."
                )
                
            } catch (e: VaultException) {
                showProgress(false)
                displayError(e)
                
                updateStatus("⏱️ Session Expired\n\n" +
                        "Your session timed out after 45 seconds.\n\n" +
                        "Next transaction will require biometric re-authentication.\n" +
                        "This provides a balance between security and convenience.")
                
            }
        }
    }
    
    /**
     * Error Scenario 6: Storage Permission Error
     *
     * When: App lacks write permission or storage is full
     * Error Code: VAULT-0102 (permission) or VAULT-0103 (space)
     * Recovery: Request permission or free space
     */
    private fun testStorageError() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("Simulating: Storage Error")
                
                throw VaultInitFailedException(
                    message = "Insufficient storage space",
                    remediation = "1. Check available device storage (Settings > Storage)\n" +
                            "2. Free up at least 50MB of space\n" +
                            "3. Clear cache (Settings > Apps > [App Name] > Storage)\n" +
                            "4. Retry initialization"
                )
                
            } catch (e: VaultInitFailedException) {
                showProgress(false)
                displayError(e)
                
                showActionDialog(
                    title = "Storage Issue",
                    message = e.remediation,
                    actionLabel = "Open Settings",
                    action = { openStorageSettings() }
                )
                
            } catch (e: VaultException) {
                showProgress(false)
                displayError(e)
            }
        }
    }
    
    // ============ Helper Methods ============
    
    private fun displayError(e: VaultException) {
        val errorInfo = "[${e.errorCode}] ${e.message ?: "Unknown error"}"
        runOnUiThread {
            errorCodeText.text = errorInfo
            statusText.text = "${e.message}\n\n${e.remediation}"
        }
    }
    
    private fun updateStatus(message: String) {
        runOnUiThread {
            statusText.text = message
        }
    }
    
    private fun showProgress(show: Boolean) {
        runOnUiThread {
            progressBar.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        }
    }
    
    private fun showActionDialog(
        title: String,
        message: String,
        actionLabel: String,
        action: () -> Unit
    ) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(actionLabel) { _, _ -> action() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    
    private fun openBiometricSettings() {
        startActivity(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
    }
    
    private fun openStorageSettings() {
        // Open Settings > Apps > This App > Storage
        val intent = android.content.Intent().apply {
            action = android.provider.Settings.ACTION_APPLICATION_SETTINGS
            data = android.net.Uri.parse("package:${packageName}")
        }
        startActivity(intent)
    }
    
    private fun clearAppDataGracefully() {
        // Note: This is a simplified version
        // Real implementation would need device admin or user action
        updateStatus("Please clear app data manually:\n\n" +
                "Settings > Apps > [App Name] > Storage > Clear Data\n\n" +
                "Then restart the app.")
    }
}
