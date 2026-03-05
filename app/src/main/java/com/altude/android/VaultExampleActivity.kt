package com.altude.android

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.altude.gasstation.Altude
import com.altude.gasstation.AltudeGasStation
import com.altude.gasstation.data.SendOptions
import com.altude.gasstation.data.Token
import com.altude.gasstation.data.Commitment
import com.altude.core.config.SdkConfig
import com.altude.core.service.StorageService
import com.altude.vault.model.VaultException
import com.altude.vault.model.BiometricNotAvailableException
import com.altude.vault.model.BiometricInvalidatedException
import com.altude.vault.model.BiometricAuthenticationFailedException
import kotlinx.coroutines.launch

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
    
    private val apiKey = "my_apikey"  // Replace with real key
    private lateinit var statusText: TextView
    private lateinit var walletAddressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var initButton: Button
    private lateinit var singleTransferButton: Button
    private lateinit var batchTransferButton: Button
    private lateinit var clearDataButton: Button
    private lateinit var revealWalletButton: Button
    private lateinit var copyWalletButton: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vault_example)
        
        // Initialize UI
        statusText = findViewById(R.id.statusText)
        walletAddressText = findViewById(R.id.walletAddressText)
        progressBar = findViewById(R.id.progressBar)
        initButton = findViewById(R.id.initButton)
        singleTransferButton = findViewById(R.id.singleTransferButton)
        batchTransferButton = findViewById(R.id.batchTransferButton)
        clearDataButton = findViewById(R.id.clearDataButton)
        revealWalletButton = findViewById(R.id.revealWalletButton)
        copyWalletButton = findViewById(R.id.copyWalletButton)

        // Set up click listeners
        initButton.setOnClickListener { initializeVault() }
        singleTransferButton.setOnClickListener { performSingleTransfer() }
        batchTransferButton.setOnClickListener { performBatchTransfer() }
        clearDataButton.setOnClickListener { clearAppDataAndRestart() }
        revealWalletButton.setOnClickListener { revealWalletAddress() }
        copyWalletButton.setOnClickListener { copyWalletToClipboard() }

        // Display welcome message
        updateStatus("Welcome to Altude Vault!\n\n" +
                "Tap 'Initialize' to set up your secure vault with biometric auth.")
        updateWalletAddress()
    }

    private fun updateWalletAddress() {
        val address = runCatching { SdkConfig.currentSigner?.publicKey?.toBase58() }
            .getOrNull()
        walletAddressText.text = address ?: "Not initialized"
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
                        updateWalletAddress()  // Address is now available immediately after init
                        updateStatus("✅ Vault Initialized Successfully!\n\n" +
                                "Your wallet is now secured with biometric authentication.\n\n" +
                                "Next: Tap 'Send Transfer' to perform a transaction.")

                        // Enable transaction buttons
                        singleTransferButton.isEnabled = true
                        batchTransferButton.isEnabled = true
                        revealWalletButton.isEnabled = true
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
     * Step 2: Perform Single Transaction using Altude.send()
     *
     * Demonstrates:
     * - Using Altude.send(options) for a simple gas-free transfer
     * - Per-operation biometric prompt (default mode)
     * - User is prompted on EVERY transaction
     * - Best for security-sensitive operations
     *
     * This is the recommended way to send transactions with vault/biometric auth
     */
    private fun performSingleTransfer() {
        lifecycleScope.launch {
            // Create SendOptions - account can be blank, SDK resolves it after biometric unlock
            val sendOptions = SendOptions(
                account = "",  // SDK auto-resolves from vault signer after biometric
                toAddress = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq",
                amount = 0.001,
                token = Token.SOL.mint(),
                commitment = Commitment.finalized
            )

            try {
                showProgress(true)
                updateStatus("Please authenticate to send this transfer...")
                val result = Altude.send(sendOptions)

                result
                    .onSuccess { response ->
                        showProgress(false)
                        updateWalletAddress()  // Public key is now available after biometric unlock
                        updateStatus("✅ Transaction Sent Successfully!\n\n" +
                                "Signature: ${response.Signature.take(16)}...\n\n" +
                                "Status: Finalized on blockchain\n" +
                                "Your transfer has been completed securely.")
                    }
                    .onFailure { error ->
                        throw error
                    }

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
                
            } catch (e: Exception) {
                showProgress(false)
                // Handle specific error cases
                val errorMessage = e.message ?: "Unknown error"
                when {
                    errorMessage.contains("token account", ignoreCase = true) ||
                    errorMessage.contains("Owner associated token account", ignoreCase = true) -> {
                        val tokenName = Token.entries.find { it.mint() == sendOptions.token }?.name ?: sendOptions.token
                        showErrorDialog(
                            title = "Invalid Recipient",
                            message = "The recipient wallet does not have a $tokenName token account.\n\n" +
                                    "The recipient must first create a $tokenName token account to receive transfers.\n\n" +
                                    "Error: $errorMessage"
                        )
                    }
                    errorMessage.contains("insufficient", ignoreCase = true) -> {
                        val tokenName = Token.entries.find { it.mint() == sendOptions.token }?.name ?: sendOptions.token
                        showErrorDialog(
                            title = "Insufficient Balance",
                            message = "Your wallet does not have enough $tokenName to complete this transfer.\n\n" +
                                    "Please ensure your wallet has sufficient balance."
                        )
                    }
                    errorMessage.contains("invalid", ignoreCase = true) ||
                    errorMessage.contains("invalid account", ignoreCase = true) -> {
                        showErrorDialog(
                            title = "Invalid Recipient Address",
                            message = "The recipient address is not valid.\n\n" +
                                    "Please check the recipient address and try again."
                        )
                    }
                    else -> {
                        showErrorDialog(
                            title = "Transfer Failed",
                            message = "${e.javaClass.simpleName}: $errorMessage"
                        )
                    }
                }
            }
        }
    }
    
    /**
     * Step 3: Batch Transactions using Altude.sendBatch()
     *
     * Demonstrates:
     * - Using Altude.sendBatch() for multiple transfers with single biometric auth
     * - User is prompted ONCE for the entire batch
     * - Better UX for bulk operations
     * - Uses VaultSigner session internally for efficient signing
     */
    private fun performBatchTransfer() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus(
                    "Preparing batch transfer...\n\n" +
                        "You'll authenticate once for all 3 transfers."
                )

                // Ensure the vault has been initialized
                if (SdkConfig.currentSigner == null) throw Exception("Vault not initialized")

                // Create multiple SendOptions - account can be blank, SDK resolves from vault signer
                val batchTransfers = listOf(
                    SendOptions(
                        account = "",  // SDK auto-resolves from vault signer after biometric
                        toAddress = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq",
                        amount = 0.001,
                        token = Token.KIN.mint(),
                        commitment = Commitment.finalized
                    ),
                    SendOptions(
                        account = "",
                        toAddress = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq",
                        amount = 0.002,
                        token = Token.KIN.mint(),
                        commitment = Commitment.finalized
                    ),
                    SendOptions(
                        account = "",
                        toAddress = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq",
                        amount = 0.003,
                        token = Token.KIN.mint(),
                        commitment = Commitment.finalized
                    )
                )

                // Sign and send batch - biometric prompt happens once inside Altude.sendBatch()
                updateStatus("Please authenticate once to send all 3 transfers...")
                val result = Altude.sendBatch(batchTransfers)

                result
                    .onSuccess { response ->
                        showProgress(false)
                        updateWalletAddress()
                        updateStatus(
                            "✅ All 3 transactions sent successfully!\n\n" +
                                "Signature: ${response.Signature.take(16)}...\n\n" +
                                "Batch processing completed with a single biometric authentication.\n" +
                                "All transfers are now finalized on the blockchain."
                        )
                    }
                    .onFailure { error ->
                        throw error
                    }

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
                    message = "[${e.errorCode}] ${e.message}\n\n${e.remediation}"
                )
            } catch (e: Exception) {
                showProgress(false)
                showErrorDialog(
                    title = "Batch Transfer Failed",
                    message = "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
    }


    // ============ Helper Methods ============
    
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
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("Clearing vault data...\n\nThis resets biometric storage and keystore material.")

                AltudeGasStation.clearVault(applicationContext)
                StorageService.clearAll()
                SdkConfig.clearSigner()

                showProgress(false)
                updateStatus("✅ Vault data cleared. Tap 'Initialize' to set up a new vault.")

                singleTransferButton.isEnabled = false
                batchTransferButton.isEnabled = false
                revealWalletButton.isEnabled = false
                initButton.isEnabled = true
            } catch (e: Exception) {
                showProgress(false)
                showErrorDialog(
                    title = "Unable to Clear",
                    message = "${e.javaClass.simpleName}: ${e.message}\n\n" +
                            "Please clear app data from system settings if the problem persists."
                )
            }
        }
    }
    
    private fun revealWalletAddress() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("Please authenticate to reveal your wallet address...")

                val signer = SdkConfig.currentSigner
                    ?: throw Exception("Vault not initialized")

                // Sign a harmless message to unlock the vault and cache the public key
                signer.signMessage("reveal-public-key".toByteArray())
                updateWalletAddress()

                showProgress(false)
                updateStatus("✅ Wallet unlocked. Address displayed above.")

            } catch (e: BiometricAuthenticationFailedException) {
                showProgress(false)
                when (e.failureReason) {
                    BiometricAuthenticationFailedException.FailureReason.UserCancelled ->
                        updateStatus("Unlock cancelled. Tap 'Reveal Wallet' to try again.")
                    BiometricAuthenticationFailedException.FailureReason.TooManyAttempts ->
                        showErrorDialog(
                            title = "Too Many Attempts",
                            message = "Biometric locked for 30 seconds. Please try again later."
                        )
                    else -> showErrorDialog(
                        title = "Authentication Failed",
                        message = "Fingerprint/face not recognized. Please try again."
                    )
                }
            } catch (e: VaultException) {
                showProgress(false)
                showErrorDialog(
                    title = "Vault Error",
                    message = "[${e.errorCode}] ${e.message}\n\n${e.remediation}"
                )
            } catch (e: Exception) {
                showProgress(false)
                showErrorDialog(
                    title = "Unable to Reveal",
                    message = e.message ?: "Unknown error"
                )
            }
        }
    }

    private fun copyWalletToClipboard() {
        val address = walletAddressText.text.toString()
        if (address.isEmpty() || address == "Not initialized" || address.contains("Locked")) {
            Toast.makeText(this, "Wallet address not available. Unlock first.", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = android.content.ClipData.newPlainText("Wallet Address", address)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Wallet address copied to clipboard!", Toast.LENGTH_SHORT).show()
    }
}
