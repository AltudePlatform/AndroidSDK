package com.altude.android

import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import com.altude.gasstation.AltudeGasStation
import com.altude.core.config.SdkConfig
import com.altude.core.config.InitOptions
import com.altude.core.config.SignerStrategy
import com.altude.vault.model.VaultException
import com.altude.vault.model.BiometricNotAvailableException
import com.altude.vault.model.BiometricInvalidatedException
import com.altude.vault.model.BiometricAuthenticationFailedException

/**
 * Vault Example Activity - Default Integration
 *
 * Demonstrates the simplest way to integrate Vault with biometric authentication:
 * - One-liner initialization: AltudeGasStation.init(context, apiKey)
 * - Automatic VaultSigner with per-operation biometric
 * - Gas-free transactions
 *
 * Features shown:
 * - Initialize vault with default settings
 * - Perform gas-free transfers
 * - Handle common errors gracefully
 * - Display user-friendly messages
 */
class VaultExampleActivity : AppCompatActivity() {
    
    private val apiKey = "ak_xECEd2kxw8siDNxUXAhfGIJf_YJ7nUrZx-fAHXg9NJk"  // Replace with real key
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var initButton: Button
    private lateinit var singleTransferButton: Button
    private lateinit var batchTransferButton: Button
    private lateinit var clearDataButton: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vault_example)
        
        // Initialize UI
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        initButton = findViewById(R.id.initButton)
        singleTransferButton = findViewById(R.id.singleTransferButton)
        batchTransferButton = findViewById(R.id.batchTransferButton)
        clearDataButton = findViewById(R.id.clearDataButton)
        
        // Set up click listeners
        initButton.setOnClickListener { initializeVault() }
        singleTransferButton.setOnClickListener { performSingleTransfer() }
        batchTransferButton.setOnClickListener { performBatchTransfer() }
        clearDataButton.setOnClickListener { clearAppDataAndRestart() }
        
        // Display welcome message
        updateStatus("Welcome to Altude Vault!\n\n" +
                "Tap 'Initialize' to set up your secure vault with biometric auth.")
    }
    
    /**
     * Step 1: Initialize Vault with One-Liner
     *
     * Best Practice: Call once during app startup
     * Default Behavior: VaultSigner with per-operation biometric auth
     * Result: User is prompted for fingerprint/face on every transaction
     */
    private fun initializeVault() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("Initializing vault...\n\nThis will set up biometric authentication.")
                
                // ONE-LINER: AltudeGasStation.init sets up VaultSigner by default
                // - Creates encrypted seed storage
                // - Initializes Android Keystore
                // - Enables biometric authentication
                // - No explicit signer configuration needed
                val initResult = AltudeGasStation.init(this@VaultExampleActivity, apiKey)

                initResult
                    .onSuccess {
                        showProgress(false)
                        updateStatus("✅ Vault Initialized Successfully!\n\n" +
                                "Your wallet is now secured with biometric authentication.\n\n" +
                                "Next: Tap 'Send Transfer' to perform a transaction.")

                        // Enable transaction buttons
                        singleTransferButton.isEnabled = true
                        batchTransferButton.isEnabled = true
                        initButton.isEnabled = false
                    }
                    .onFailure { error ->
                        throw error
                    }

            } catch (e: BiometricNotAvailableException) {
                // User hasn't set up biometric - guide them
                showProgress(false)
                showErrorDialog(
                    title = "Biometric Not Set Up",
                    message = e.remediation,
                    actionLabel = "Open Settings",
                    action = { openBiometricSettings() }
                )
                
            } catch (e: BiometricInvalidatedException) {
                // Biometric changed (new fingerprints added, etc.)
                // This is a security feature - old vault keys are invalidated
                showProgress(false)
                showErrorDialog(
                    title = "Vault Needs Reset",
                    message = "Your biometric credentials changed.\n\n${e.remediation}",
                    actionLabel = "Clear Data",
                    action = { clearAppDataAndRestart() }
                )
                
            } catch (e: VaultException) {
                // Other vault errors (storage, permission, etc.)
                showProgress(false)
                showErrorDialog(
                    title = "Initialization Failed",
                    message = "[${e.errorCode}] ${e.message}\n\n${e.remediation}",
                    actionLabel = "Retry",
                    action = { initializeVault() }
                )
            } catch (e: Exception) {
                // Catch any other unexpected exceptions
                showProgress(false)
                showErrorDialog(
                    title = "Unexpected Error",
                    message = "${e.javaClass.simpleName}: ${e.message}",
                    actionLabel = "Retry",
                    action = { initializeVault() }
                )
            }
        }
    }
    
    /**
     * Step 2: Perform Single Transaction
     *
     * Demonstrates:
     * - Per-operation biometric prompt (default mode)
     * - User is prompted on EVERY transaction
     * - Best for security-sensitive operations
     */
    private fun performSingleTransfer() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("Preparing transfer...\n\nYou'll be prompted to authenticate.")
                
                // Get current signer (VaultSigner set by AltudeGasStation.init)
                val signer = SdkConfig.currentSigner
                    ?: throw Exception("Vault not initialized")
                
                // Create gas-free transfer
                // (In real app, would use real recipient and amount)
                val transferTx = createTestTransfer(
                    recipient = "11111111111111111111111111111111",
                    tokenMint = "EPjFWaLb3HuSfopjmrX4S37FvCkxjVcaS1nrSE9w5MEh",  // USDC
                    amount = 100_000_000  // 100 USDC
                )
                
                // Sign transaction (user sees biometric prompt here)
                updateStatus("Please authenticate to sign this transaction...")
                val signature = signer.signMessage(transferTx.message)
                
                showProgress(false)
                updateStatus("✅ Transaction Signed Successfully!\n\n" +
                        "Signature: ${signature.take(16).joinToString("") { b -> "%02x".format(b) }}...\n\n" +
                        "In production, this would be submitted to blockchain.")
                
            } catch (e: BiometricAuthenticationFailedException) {
                showProgress(false)
                when (e.failureReason) {
                    BiometricAuthenticationFailedException.FailureReason.UserCancelled -> {
                        updateStatus("Transaction cancelled.\n\nTap 'Send Transfer' to try again.")
                    }
                    BiometricAuthenticationFailedException.FailureReason.TooManyAttempts -> {
                        showErrorDialog(
                            title = "Too Many Attempts",
                            message = "Biometric locked for 30 seconds.\n\nPlease try again later."
                        )
                    }
                    else -> {
                        showErrorDialog(
                            title = "Authentication Failed",
                            message = "Fingerprint/face not recognized.\n\nPlease try again."
                        )
                    }
                }
                
            } catch (e: VaultException) {
                showProgress(false)
                showErrorDialog(
                    title = "Transaction Error",
                    message = "[${e.errorCode}] ${e.message}\n\n${e.remediation}"
                )
            }
        }
    }
    
    /**
     * Step 3: Batch Transactions (Advanced)
     *
     * Demonstrates:
     * - Would use VaultSigner.createWithSession() for batch operations
     * - User prompted ONCE for multiple transactions in 45-second window
     * - Better UX for bulk operations
     *
     * Note: This shows the pattern; actual batch signing left for VaultSignerExampleActivity
     */
    private fun performBatchTransfer() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("Preparing batch transfer...\n\n" +
                        "You'll authenticate once for the entire batch.\n" +
                        "This is more efficient than per-transaction prompts.")
                
                val signer = SdkConfig.currentSigner
                    ?: throw Exception("Vault not initialized")
                
                // In per-operation mode (default), each sign still prompts
                // For true batch with one prompt, use VaultSigner.createWithSession()
                
                val signatures: List<ByteArray> = listOf(
                    "Transfer 1: 100 USDC",
                    "Transfer 2: 50 SOL",
                    "Transfer 3: 200 USDC"
                ).mapIndexed { index, transfer ->
                    async {
                        updateStatus("Signing transaction ${index + 1}/3...\n\nPlease authenticate.")
                        signer.signMessage(transfer.toByteArray())
                    }
                }.map { it.await() }
                
                showProgress(false)
                updateStatus("✅ All 3 transactions signed!\n\n" +
                        "In per-operation mode (default), user was prompted 3 times.\n\n" +
                        "Tip: Use VaultSigner.createWithSession() for fewer prompts in batch operations.")
                
            } catch (e: BiometricAuthenticationFailedException) {
                showProgress(false)
                if (e.failureReason == BiometricAuthenticationFailedException.FailureReason.UserCancelled) {
                    updateStatus("Batch cancelled.\n\nTap 'Send Batch' to try again.")
                } else {
                    showErrorDialog(
                        title = "Authentication Failed",
                        message = "Could not authenticate for batch operation.\n\nPlease try again."
                    )
                }
                
            } catch (e: VaultException) {
                showProgress(false)
                showErrorDialog(
                    title = "Batch Error",
                    message = "[${e.errorCode}] ${e.message}"
                )
            }
        }
    }
    
    // ============ Helper Methods ============
    
    private fun createTestTransfer(
        recipient: String,
        tokenMint: String,
        amount: Long
    ): TestTransaction {
        // Simplified test transaction (not real Solana format)
        val messageJson = """{
            "recipient": "$recipient",
            "tokenMint": "$tokenMint",
            "amount": $amount,
            "timestamp": ${System.currentTimeMillis()}
        }"""
        
        return TestTransaction(messageJson.toByteArray())
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
    
    private fun showErrorDialog(
        title: String,
        message: String,
        actionLabel: String? = null,
        action: (() -> Unit)? = null
    ) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(actionLabel ?: "OK") { _, _ -> action?.invoke() }
                .setCancelable(true)
                .show()
        }
    }
    
    private fun openBiometricSettings() {
        startActivity(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
    }
    
    private fun clearAppDataAndRestart() {
        val packageName = packageName
        val runtime = Runtime.getRuntime()
        try {
            runtime.exec("pm clear $packageName")
        } catch (e: Exception) {
            updateStatus("Could not clear data. Please manually clear app data in settings.")
        }
    }
    
    // Test data class
    data class TestTransaction(val message: ByteArray) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = message.contentHashCode()
    }
}
