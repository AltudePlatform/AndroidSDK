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
import com.altude.core.config.SdkConfig
import com.altude.core.service.StorageService
import com.altude.gasstation.Altude
import com.altude.gasstation.AltudeGasStation
import com.altude.gasstation.data.Commitment
import com.altude.gasstation.data.SendOptions
import com.altude.gasstation.data.Token
import com.altude.vault.model.BiometricAuthenticationFailedException
import com.altude.vault.model.BiometricInvalidatedException
import com.altude.vault.model.BiometricNotAvailableException
import com.altude.vault.model.VaultException
import kotlinx.coroutines.launch

/**
 * Vault Example Activity - Default Integration
 *
 * Minimal SDK usage pattern:
 *   Altude.setApiKey(this, apiKey)   ← one-time setup
 *   Altude.send(options)             ← biometric handled internally
 *
 * SDK users only need .onSuccess / .onFailure — no try/catch required.
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

        statusText           = findViewById(R.id.statusText)
        walletAddressText    = findViewById(R.id.walletAddressText)
        progressBar          = findViewById(R.id.progressBar)
        initButton           = findViewById(R.id.initButton)
        singleTransferButton = findViewById(R.id.singleTransferButton)
        batchTransferButton  = findViewById(R.id.batchTransferButton)
        clearDataButton      = findViewById(R.id.clearDataButton)
        revealWalletButton   = findViewById(R.id.revealWalletButton)
        copyWalletButton     = findViewById(R.id.copyWalletButton)

        initButton.setOnClickListener           { initializeVault() }
        singleTransferButton.setOnClickListener { performSingleTransfer() }
        batchTransferButton.setOnClickListener  { performBatchTransfer() }
        clearDataButton.setOnClickListener      { clearAppDataAndRestart() }
        revealWalletButton.setOnClickListener   { revealWalletAddress() }
        copyWalletButton.setOnClickListener     { copyWalletToClipboard() }

        updateStatus("Welcome to Altude Vault!\n\nTap 'Initialize' to set up your secure vault with biometric auth.")
        updateWalletAddress()
    }

    private fun updateWalletAddress() {
        val address = runCatching { SdkConfig.currentSigner?.publicKey?.toBase58() }.getOrNull()
        walletAddressText.text = address ?: "Not initialized"
    }

    // ── Step 1: Init ─────────────────────────────────────────────────────────
    // One call does everything: sets API key, creates vault, registers biometric signer.
    // Vault errors (no biometric enrolled, invalidated keys, etc.) come back as
    // Result.failure and are routed to handleVaultError().

    private fun initializeVault() {
        lifecycleScope.launch {
            showProgress(true)
            updateStatus("Initializing vault…\n\nThis will set up biometric authentication.")

            Altude.setApiKey(this@VaultExampleActivity, apiKey)
                .onSuccess {
                    showProgress(false)
                    updateWalletAddress()
                    updateStatus(
                        "✅ Vault Initialized!\n\n" +
                        "Your wallet is secured with biometric authentication.\n\n" +
                        "Tap 'Send Transfer' to perform a transaction."
                    )
                    singleTransferButton.isEnabled = true
                    batchTransferButton.isEnabled  = true
                    revealWalletButton.isEnabled   = true
                    initButton.isEnabled           = false
                }
                .onFailure { error ->
                    showProgress(false)
                    handleVaultError(error, retryAction = { initializeVault() })
                }
        }
    }

    // ── Step 2: Single Transfer ───────────────────────────────────────────────
    // Altude.send() triggers the biometric prompt internally.
    // Cancelled / failed biometric → Result.failure(BiometricAuthenticationFailedException)

    private fun performSingleTransfer() {
        lifecycleScope.launch {
            showProgress(true)
            updateStatus("Please authenticate to send this transfer…")

            Altude.send(
                SendOptions(
                    toAddress  = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq",
                    amount     = 0.001,
                    token      = Token.SOL.mint(),
                    commitment = Commitment.finalized
                )
            )
                .onSuccess { response ->
                    showProgress(false)
                    updateWalletAddress()
                    updateStatus("✅ Transaction Sent!\n\nSignature: ${response.Signature.take(16)}…\n\nFinalized on blockchain.")
                }
                .onFailure { error ->
                    showProgress(false)
                    handleVaultError(error, retryAction = { performSingleTransfer() })
                }
        }
    }

    // ── Step 3: Batch Transfer ────────────────────────────────────────────────
    // Single biometric prompt for all transfers in the batch.

    private fun performBatchTransfer() {
        lifecycleScope.launch {
            showProgress(true)
            updateStatus("Preparing batch transfer…\n\nYou'll authenticate once for all 3 transfers.")

            if (SdkConfig.currentSigner == null) {
                showProgress(false)
                showErrorDialog("Not Initialized", "Tap 'Initialize' first.")
                return@launch
            }

            Altude.sendBatch(
                listOf(
                    SendOptions(toAddress = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq", amount = 0.001, token = Token.KIN.mint()),
                    SendOptions(toAddress = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq", amount = 0.002, token = Token.KIN.mint()),
                    SendOptions(toAddress = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq", amount = 0.003, token = Token.KIN.mint())
                )
            )
                .onSuccess { response ->
                    showProgress(false)
                    updateWalletAddress()
                    updateStatus("✅ All 3 transactions sent!\n\nSignature: ${response.Signature.take(16)}…\n\nBatch completed with a single biometric authentication.")
                }
                .onFailure { error ->
                    showProgress(false)
                    handleVaultError(error, retryAction = { performBatchTransfer() })
                }
        }
    }

    // ── Shared vault error handler ────────────────────────────────────────────
    // SDK users call this in every .onFailure.
    // Covers all vault error types — no need to import or know vault internals.

    private fun handleVaultError(error: Throwable, retryAction: (() -> Unit)? = null) {
        when (error) {
            is BiometricNotAvailableException -> {
                // Only offer "Open Settings" when the message explicitly says
                // no screen lock — not for every BiometricNotAvailableException
                val needsScreenLock = error.message?.contains("screen lock", ignoreCase = true) == true ||
                        error.message?.contains("none enrolled", ignoreCase = true) == true ||
                        error.remediation.contains("Screen Lock", ignoreCase = true)
                if (needsScreenLock) {
                    showErrorDialog(
                        title       = "Screen Lock Required",
                        message     = "A PIN, pattern, password, or fingerprint is required.\n\nGo to Settings > Security > Screen Lock.",
                        actionLabel = "Open Settings",
                        action      = { openBiometricSettings() }
                    )
                } else {
                    showErrorDialog(
                        title       = "Authentication Unavailable",
                        message     = error.message ?: "Authentication is not available.",
                        actionLabel = if (retryAction != null) "Retry" else "OK",
                        action      = retryAction
                    )
                }
            }
            is BiometricInvalidatedException ->
                showErrorDialog(
                    title       = "Vault Needs Reset",
                    message     = "Your biometric credentials changed.\n\n${error.remediation}",
                    actionLabel = "Clear Data",
                    action      = { clearAppDataAndRestart() }
                )
            is BiometricAuthenticationFailedException ->
                when (error.failureReason) {
                    BiometricAuthenticationFailedException.FailureReason.UserCancelled ->
                        updateStatus("Transaction cancelled. Tap the button to try again.")
                    BiometricAuthenticationFailedException.FailureReason.TooManyAttempts ->
                        showErrorDialog("Too Many Attempts", "Biometric locked for 30 seconds. Please try again later.")
                    else ->
                        showErrorDialog("Authentication Failed", "Fingerprint/face not recognized. Please try again.", "Retry", retryAction)
                }
            is VaultException ->
                showErrorDialog(
                    title       = "Vault Error [${error.errorCode}]",
                    message     = "${error.message}\n\n${error.remediation}",
                    actionLabel = if (retryAction != null) "Retry" else "OK",
                    action      = retryAction
                )
            else -> {
                val msg = error.message ?: error.javaClass.simpleName
                when {
                    msg.contains("token account", ignoreCase = true)  -> showErrorDialog("Invalid Recipient",    "Recipient has no token account for this token.")
                    msg.contains("insufficient",  ignoreCase = true)  -> showErrorDialog("Insufficient Balance", "Not enough tokens to complete this transfer.")
                    else                                               -> showErrorDialog("Error", msg, if (retryAction != null) "Retry" else "OK", retryAction)
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateStatus(message: String) = runOnUiThread { statusText.text = message }

    private fun showProgress(show: Boolean) = runOnUiThread {
        progressBar.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showErrorDialog(
        title: String,
        message: String,
        actionLabel: String? = null,
        action: (() -> Unit)? = null
    ) = runOnUiThread {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(actionLabel ?: "OK") { _, _ -> action?.invoke() }
            .setCancelable(true)
            .show()
    }

    private fun openBiometricSettings() {
        startActivity(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
    }

    private fun clearAppDataAndRestart() {
        lifecycleScope.launch {
            showProgress(true)
            updateStatus("Clearing vault data…")
            AltudeGasStation.clearVault(applicationContext)
            StorageService.clearAll()
            SdkConfig.clearSigner()
            showProgress(false)
            updateStatus("✅ Vault cleared. Tap 'Initialize' to set up a new vault.")
            singleTransferButton.isEnabled = false
            batchTransferButton.isEnabled  = false
            revealWalletButton.isEnabled   = false
            initButton.isEnabled           = true
        }
    }

    private fun revealWalletAddress() {
        lifecycleScope.launch {
            showProgress(true)
            updateStatus("Please authenticate to reveal your wallet address…")

            val signer = SdkConfig.currentSigner
            if (signer == null) {
                showProgress(false)
                showErrorDialog("Not Initialized", "Tap 'Initialize' first.")
                return@launch
            }

            runCatching { signer.signMessage("reveal-public-key".toByteArray()) }
                .onSuccess {
                    updateWalletAddress()
                    showProgress(false)
                    updateStatus("✅ Wallet unlocked. Address displayed above.")
                }
                .onFailure { error ->
                    showProgress(false)
                    handleVaultError(error, retryAction = { revealWalletAddress() })
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
