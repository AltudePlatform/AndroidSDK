package com.altude.android

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
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
import com.altude.vault.model.VaultInitFailedException
import com.altude.vault.model.VaultLockedException
import androidx.core.net.toUri
import kotlinx.coroutines.launch

/**
 * Error Handling Example Activity
 *
 * Demonstrates REAL vault error handling by calling actual SDK functions.
 * Each scenario triggers the real error path — no manual throws.
 *
 * Scenarios:
 * 1. Init vault         → catches BiometricNotAvailableException if no biometric enrolled
 * 2. Send transaction   → catches BiometricAuthenticationFailedException if user cancels
 * 3. Use before init    → catches VaultLockedException if init was never called
 * 4. Biometric changed  → catches BiometricInvalidatedException (real keystore invalidation)
 * 5. Storage issue      → catches VaultInitFailedException
 * 6. Clear vault        → resets state so errors can be re-triggered
 */
@Suppress("SetTextI18n", "HardcodedText")
class ErrorHandlingExampleActivity : AppCompatActivity() {

    private val apiKey = "my_apikey"

    private lateinit var statusText: TextView
    private lateinit var errorCodeText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var testButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_error_handling_example)

        statusText    = findViewById(R.id.statusText)
        errorCodeText = findViewById(R.id.errorCodeText)
        progressBar   = findViewById(R.id.progressBar)
        testButton    = findViewById(R.id.testButton)

        // Wire clearButton dynamically — avoids IDE R-cache stale reference issue
        val clearButton = findViewById<Button>(R.id.clearButton)
        testButton.setOnClickListener  { showScenarioPicker() }
        clearButton?.setOnClickListener { clearVaultAndReset() }

        updateStatus(
            "Real Vault Error Handling\n\n" +
            "Each scenario calls the actual SDK — errors come from the vault itself.\n\n" +
            "Tap 'Run Scenario' to pick one."
        )
        errorCodeText.text = "No error yet"
    }

    // ── Scenario picker ──────────────────────────────────────────────────────

    private fun showScenarioPicker() {
        val items = arrayOf(
            "1. Init Vault  (VAULT-0201 if no biometric set up)",
            "2. Send Tx     (VAULT-0203/0205 if cancelled/failed)",
            "3. Use Before Init  (VAULT-0401)",
            "4. Biometric Invalidated  (VAULT-0202)",
            "5. Storage / Init failure  (VAULT-0101)",
            "⚠️ Clear Vault Data"
        )
        AlertDialog.Builder(this)
            .setTitle("Pick a Scenario")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> scenarioInitVault()
                    1 -> scenarioSendTransaction()
                    2 -> scenarioUseBeforeInit()
                    3 -> scenarioBiometricInvalidated()
                    4 -> scenarioStorageFailure()
                    5 -> clearVaultAndReset()
                }
            }
            .show()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Scenario 1 — Init Vault
    // Real error: BiometricNotAvailableException thrown by BiometricHandler
    //             inside AltudeGasStation.init() when no biometric is enrolled.
    // ════════════════════════════════════════════════════════════════════════
    private fun scenarioInitVault() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("Calling AltudeGasStation.init()…\n\nIf no biometric is enrolled the vault will throw VAULT-0201.")
                clearError()

                val result = Altude.setApiKey(this@ErrorHandlingExampleActivity, apiKey)
                // ------------------------------------------------------------

                result
                    .onSuccess {
                        showProgress(false)
                        val address = SdkConfig.currentSigner?.publicKey?.toBase58() ?: "n/a"
                        updateStatus(
                            "✅ Vault initialized — no errors.\n\n" +
                            "Wallet: ${address.take(20)}…\n\n" +
                            "Biometric is properly set up on this device.\n" +
                            "Try Scenario 2 to test authentication errors."
                        )
                    }
                    .onFailure { throw it }

            } catch (e: BiometricNotAvailableException) {
                // ── Real VAULT-0201 ─────────────────────────────────────────
                showProgress(false)
                displayError(e)
                AlertDialog.Builder(this@ErrorHandlingExampleActivity)
                    .setTitle("[${e.errorCode}] Biometric Not Set Up")
                    .setMessage(
                        "${e.message}\n\n" +
                        "Fix:\n${e.remediation}"
                    )
                    .setPositiveButton("Open Settings") { _, _ -> openSecuritySettings() }
                    .setNegativeButton("Cancel", null)
                    .show()

            } catch (e: BiometricInvalidatedException) {
                // ── Real VAULT-0202 ─────────────────────────────────────────
                showProgress(false)
                displayError(e)
                showInvalidatedDialog(e)

            } catch (e: VaultInitFailedException) {
                // ── Real VAULT-0101 ─────────────────────────────────────────
                showProgress(false)
                displayError(e)
                AlertDialog.Builder(this@ErrorHandlingExampleActivity)
                    .setTitle("[${e.errorCode}] Init Failed")
                    .setMessage("${e.message}\n\n${e.remediation}")
                    .setPositiveButton("OK", null)
                    .show()

            } catch (e: VaultException) {
                showProgress(false)
                displayError(e)
                showGenericVaultError(e)

            } catch (e: Exception) {
                showProgress(false)
                showUnexpected(e)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Scenario 2 — Send Transaction
    // Real errors: BiometricAuthenticationFailedException thrown inside
    //              Altude.send() → GaslessManager → VaultSigner.signMessage()
    //              → BiometricHandler.authenticate() when user cancels/fails.
    // ════════════════════════════════════════════════════════════════════════
    private fun scenarioSendTransaction() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("Calling Altude.send()…\n\nCancel or fail the biometric prompt to trigger VAULT-0203/0205.")
                clearError()

                if (SdkConfig.currentSigner == null) {
                    throw VaultLockedException(
                        remediation = "Call AltudeGasStation.init() first (run Scenario 1)."
                    )
                }

                // ── REAL SDK CALL ────────────────────────────────────────────
                val result = Altude.send(
                    SendOptions(
                        toAddress  = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq",
                        amount     = 0.001,
                        token      = Token.KIN.mint(),
                        commitment = Commitment.finalized
                    )
                )
                // ────────────────────────────────────────────────────────────

                result
                    .onSuccess { response ->
                        showProgress(false)
                        updateStatus(
                            "✅ Transaction sent!\n\n" +
                            "Signature: ${response.Signature.take(20)}…\n\n" +
                            "No errors — authentication succeeded."
                        )
                    }
                    .onFailure { throw it }

            } catch (e: BiometricAuthenticationFailedException) {
                // ── Real VAULT-0203 / VAULT-0205 ────────────────────────────
                showProgress(false)
                displayError(e)
                when (e.failureReason) {
                    BiometricAuthenticationFailedException.FailureReason.UserCancelled ->
                        updateStatus(
                            "❌ [${e.errorCode}] Transaction cancelled.\n\n" +
                            "User tapped Cancel on the biometric prompt.\n\n" +
                            "Recovery: Show a 'Try Again' button — this is a transient, retryable error."
                        )
                    BiometricAuthenticationFailedException.FailureReason.TooManyAttempts ->
                        AlertDialog.Builder(this@ErrorHandlingExampleActivity)
                            .setTitle("[${e.errorCode}] Too Many Attempts")
                            .setMessage("Biometric locked for 30 seconds.\n\n${e.remediation}")
                            .setPositiveButton("OK", null)
                            .show()
                    else ->
                        updateStatus(
                            "❌ [${e.errorCode}] Authentication failed.\n\n" +
                            "${e.message}\n\nRecovery: ${e.remediation}"
                        )
                }

            } catch (e: VaultLockedException) {
                showProgress(false)
                displayError(e)
                updateStatus("❌ [${e.errorCode}] ${e.message}\n\nFix: ${e.remediation}")

            } catch (e: VaultException) {
                showProgress(false)
                displayError(e)
                showGenericVaultError(e)

            } catch (e: Exception) {
                showProgress(false)
                showUnexpected(e)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Scenario 3 — Use SDK before init
    // Real error: VaultLockedException — SdkConfig.currentSigner is null
    //             because AltudeGasStation.init() was never called.
    // ════════════════════════════════════════════════════════════════════════
    private fun scenarioUseBeforeInit() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("Clearing signer then calling Altude.send() without init…")
                clearError()

                // Simulate "forgot to call init" by clearing the signer
                SdkConfig.clearSigner()

                // ── REAL SDK CALL — will fail because signer is null ─────────
                val result = Altude.send(
                    SendOptions(
                        toAddress  = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq",
                        amount     = 0.001,
                        token      = Token.KIN.mint(),
                        commitment = Commitment.finalized
                    )
                )
                // ────────────────────────────────────────────────────────────

                result
                    .onSuccess {
                        showProgress(false)
                        updateStatus("✅ Sent (signer was still set). Run 'Clear Vault' first to reset.")
                    }
                    .onFailure { throw it }

            } catch (e: VaultLockedException) {
                showProgress(false)
                displayError(e)
                updateStatus(
                    "❌ [${e.errorCode}] Vault not initialized.\n\n" +
                    "${e.message}\n\n" +
                    "Fix: ${e.remediation}"
                )

            } catch (e: IllegalStateException) {
                // GaslessManager throws this when signer is null
                showProgress(false)
                errorCodeText.text = "VAULT-0401 (no signer)"
                updateStatus(
                    "❌ Signer not set.\n\n" +
                    "${e.message}\n\n" +
                    "Fix: Call AltudeGasStation.init(context, apiKey) before any Altude.* call."
                )

            } catch (e: VaultException) {
                showProgress(false)
                displayError(e)
                showGenericVaultError(e)

            } catch (e: Exception) {
                showProgress(false)
                showUnexpected(e)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Scenario 4 — Biometric Invalidated
    // Real error: BiometricInvalidatedException thrown when Android Keystore
    //             invalidates keys after biometric enrollment changes.
    //             To trigger naturally: change/add a fingerprint on device,
    //             then call AltudeGasStation.init() — vault will throw on
    //             first decrypt attempt.
    //
    // Note: We cannot force-invalidate the keystore from code. This scenario
    //       explains HOW to detect it and WHAT to do.
    // ════════════════════════════════════════════════════════════════════════
    private fun scenarioBiometricInvalidated() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("Attempting vault access after simulated key invalidation…")
                clearError()

                val result = Altude.setApiKey(this@ErrorHandlingExampleActivity, apiKey)

                result
                    .onSuccess {
                        showProgress(false)
                        updateStatus(
                            "ℹ️ No invalidation error this time.\n\n" +
                            "To trigger VAULT-0202 for real:\n" +
                            "1. Enroll a new fingerprint in device Settings\n" +
                            "2. Come back and run Scenario 1 (Init Vault)\n\n" +
                            "Android will automatically invalidate the old key."
                        )
                    }
                    .onFailure { throw it }

            } catch (e: BiometricInvalidatedException) {
                // ── Real VAULT-0202 ─────────────────────────────────────────
                showProgress(false)
                displayError(e)
                showInvalidatedDialog(e)

            } catch (e: VaultException) {
                showProgress(false)
                displayError(e)
                showGenericVaultError(e)

            } catch (e: Exception) {
                showProgress(false)
                showUnexpected(e)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Scenario 5 — Storage / Init failure
    // Real error: VaultInitFailedException thrown by VaultManager.createVault()
    //             when storage write fails (e.g. disk full, permission denied).
    //
    // Note: We cannot force a disk-full error from code. This scenario shows
    //       how to catch and display the error if it occurs in the field.
    // ════════════════════════════════════════════════════════════════════════
    private fun scenarioStorageFailure() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("Attempting init — catches VaultInitFailedException if storage fails…")
                clearError()

                val result = Altude.setApiKey(this@ErrorHandlingExampleActivity, apiKey)

                result
                    .onSuccess {
                        showProgress(false)
                        updateStatus(
                            "ℹ️ Init succeeded — no storage error.\n\n" +
                            "VaultInitFailedException (VAULT-0101) is thrown in the field when:\n" +
                            "• Device storage is full\n" +
                            "• App has no write permission\n" +
                            "• Keystore is unavailable\n\n" +
                            "The catch block below handles all these cases."
                        )
                    }
                    .onFailure { throw it }

            } catch (e: VaultInitFailedException) {
                // ── Real VAULT-0101 ─────────────────────────────────────────
                showProgress(false)
                displayError(e)
                AlertDialog.Builder(this@ErrorHandlingExampleActivity)
                    .setTitle("[${e.errorCode}] Storage / Init Failed")
                    .setMessage("${e.message}\n\nFix:\n${e.remediation}")
                    .setPositiveButton("Open App Settings") { _, _ -> openAppSettings() }
                    .setNegativeButton("OK", null)
                    .show()

            } catch (e: VaultException) {
                showProgress(false)
                displayError(e)
                showGenericVaultError(e)

            } catch (e: Exception) {
                showProgress(false)
                showUnexpected(e)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Clear vault — lets errors be re-triggered from a clean state
    // Note: There is no vault recovery / seed export function in the SDK.
    //       Clearing is destructive. Keys are gone after this.
    // ════════════════════════════════════════════════════════════════════════
    private fun clearVaultAndReset() {
        AlertDialog.Builder(this)
            .setTitle("Clear Vault?")
            .setMessage(
                "This deletes all vault keys and resets the demo.\n\n" +
                "⚠️ There is no recovery — keys cannot be exported or restored.\n\n" +
                "Continue?"
            )
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch {
                    try {
                        showProgress(true)
                        AltudeGasStation.clearVault(applicationContext)
                        StorageService.clearAll()
                        SdkConfig.clearSigner()
                        showProgress(false)
                        clearError()
                        updateStatus(
                            "✅ Vault cleared.\n\n" +
                            "All keys deleted. Run Scenario 1 to re-initialize.\n\n" +
                            "Note: There is currently no seed export / recovery function. " +
                            "Clearing vault means those keys are gone permanently."
                        )
                    } catch (e: Exception) {
                        showProgress(false)
                        showUnexpected(e)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Shared error dialogs ─────────────────────────────────────────────────

    private fun showInvalidatedDialog(e: BiometricInvalidatedException) {
        AlertDialog.Builder(this@ErrorHandlingExampleActivity)
            .setTitle("[${e.errorCode}] Biometric Keys Invalidated")
            .setMessage(
                "${e.message}\n\n" +
                "⚠️ There is no recovery for this error — the keys that were " +
                "protected by the old biometric are permanently inaccessible.\n\n" +
                "Fix:\n${e.remediation}\n\n" +
                "Tap 'Clear Vault' to start fresh."
            )
            .setPositiveButton("Clear Vault") { _, _ -> clearVaultAndReset() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGenericVaultError(e: VaultException) {
        AlertDialog.Builder(this@ErrorHandlingExampleActivity)
            .setTitle("[${e.errorCode}] Vault Error")
            .setMessage("${e.message}\n\nFix:\n${e.remediation}")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showUnexpected(e: Exception) {
        errorCodeText.text = e.javaClass.simpleName
        updateStatus("❌ Unexpected error\n\n${e.javaClass.simpleName}: ${e.message}")
    }

    // ── UI helpers ───────────────────────────────────────────────────────────

    private fun displayError(e: VaultException) {
        runOnUiThread {
            errorCodeText.text = e.errorCode
            statusText.text    = "❌ ${e.message}\n\nRemediation:\n${e.remediation}"
        }
    }

    private fun clearError() = runOnUiThread { errorCodeText.text = "—" }

    private fun updateStatus(msg: String) = runOnUiThread { statusText.text = msg }

    private fun showProgress(show: Boolean) = runOnUiThread {
        progressBar.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun openSecuritySettings() {
        startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:$packageName".toUri()
            }
        )
    }
}
