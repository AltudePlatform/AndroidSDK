package com.altude.android

import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.altude.core.config.InitOptions
import com.altude.core.config.SdkConfig
import com.altude.core.model.TransactionSigner
import com.altude.gasstation.Altude
import com.altude.gasstation.data.Commitment
import com.altude.gasstation.data.SendOptions
import com.altude.gasstation.data.Token
import com.altude.vault.model.BiometricAuthenticationFailedException
import com.altude.vault.model.VaultException
import foundation.metaplex.solanaeddsa.Keypair
import foundation.metaplex.solanaeddsa.SolanaEddsa
import foundation.metaplex.solanapublickeys.PublicKey
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom

/**
 * Signer Examples Activity
 *
 * Side-by-side demonstration of the two signer approaches supported by the Altude SDK:
 *
 * ── Approach A: Default Vault (recommended) ─────────────────────────────────────
 *   • Keys stored in Android Keystore, encrypted with a vault seed
 *   • Biometric / device credential gates every signing operation
 *   • No private key ever exposed in memory
 *   • One-liner init: AltudeGasStation.init(context, apiKey)
 *   • Transactions: Altude.send(options)  ← biometric prompt is built-in
 *
 * ── Approach B: Custom Key Storage ──────────────────────────────────────────────
 *   • You manage your own TransactionSigner implementation
 *   • Completely auth-agnostic – biometric, PIN, none, HSM, etc.
 *   • Pass the signer either:
 *       - globally at init:  AltudeGasStation.init(..., InitOptions.custom(mySigner))
 *       - per call:          Altude.send(options, signer = mySigner)
 *   • Example below uses an in-memory Ed25519 key (no passkey)
 */
class SignerExamplesActivity : AppCompatActivity() {

    private val apiKey = "my_apikey" // Replace with your real API key

    // ── UI refs ──────────────────────────────────────────────────────────────────
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    // Vault (Approach A)
    private lateinit var vaultInitButton: Button
    private lateinit var vaultSendButton: Button

    // Custom signer (Approach B)
    private lateinit var customInitButton: Button
    private lateinit var customSendButton: Button
    private lateinit var customSendPerCallButton: Button

    // Held for per-call demo
    private var customSigner: NoPassphraseMemorySigner? = null

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signer_examples)

        statusText               = findViewById(R.id.statusText)
        progressBar              = findViewById(R.id.progressBar)
        vaultInitButton          = findViewById(R.id.vaultInitButton)
        vaultSendButton          = findViewById(R.id.vaultSendButton)
        customInitButton         = findViewById(R.id.customInitButton)
        customSendButton         = findViewById(R.id.customSendButton)
        customSendPerCallButton  = findViewById(R.id.customSendPerCallButton)

        vaultSendButton.isEnabled         = false
        customSendButton.isEnabled        = false
        customSendPerCallButton.isEnabled = false

        vaultInitButton.setOnClickListener         { initVault() }
        vaultSendButton.setOnClickListener         { sendWithVault() }
        customInitButton.setOnClickListener        { initCustomSigner() }
        customSendButton.setOnClickListener        { sendWithCustomSignerGlobal() }
        customSendPerCallButton.setOnClickListener { sendWithCustomSignerPerCall() }

        updateStatus(
            "Two approaches to signing with Altude.send():\n\n" +
            "• Approach A – Default Vault (biometric)\n" +
            "• Approach B – Custom key storage (no passkey)\n\n" +
            "Tap an 'Init' button to get started."
        )
    }

    // ════════════════════════════════════════════════════════════════════════════
    // APPROACH A — Default Vault (recommended)
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Approach A – Init
     *
     * AltudeGasStation.init() with no extra options creates a VaultSigner backed
     * by the Android Keystore, protected by biometric / device credential.
     * You don't need to handle keys yourself at all.
     */
    private fun initVault() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("[Approach A] Initializing Vault…\n\nBiometric prompt may appear.")

                // ─── Altude.setApiKey() is the only init call needed ───────
                val result = Altude.setApiKey(
                    activity = this@SignerExamplesActivity,
                    apiKey   = apiKey
                    // No options → defaults to Vault with biometric
                )
                // ───────────────────────────────────────────────────────────────

                result
                    .onSuccess {
                        showProgress(false)
                        val address = SdkConfig.currentSigner?.publicKey?.toBase58() ?: "unavailable"
                        updateStatus(
                            "✅ [Approach A] Vault ready!\n\n" +
                            "Wallet: ${address.take(20)}…\n\n" +
                            "Tap 'Send (Vault)' to send a transaction.\n" +
                            "Biometric prompt will appear automatically."
                        )
                        vaultSendButton.isEnabled = true
                        vaultInitButton.isEnabled = false
                    }
                    .onFailure { throw it }

            } catch (e: Exception) {
                showProgress(false)
                showError("Vault Init Failed", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Approach A – Send
     *
     * Altude.send(options) uses the vault signer set during init.
     * The biometric prompt is triggered automatically inside the SDK —
     * you don't need to write any authentication code.
     */
    private fun sendWithVault() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("[Approach A] Sending…\n\nBiometric prompt will appear now.")

                val options = SendOptions(
                    // account is blank → SDK resolves from the vault signer automatically
                    toAddress  = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq",
                    amount     = 0.001,
                    token      = Token.KIN.mint(),
                    commitment = Commitment.finalized
                )

                // ─── Altude.send() – no signer arg needed, vault handles auth ──
                val result = Altude.send(options)
                // ───────────────────────────────────────────────────────────────

                result
                    .onSuccess { response ->
                        showProgress(false)
                        updateStatus(
                            "✅ [Approach A] Transfer sent!\n\n" +
                            "Signature: ${response.Signature.take(20)}…\n\n" +
                            "Signed automatically by the vault after biometric auth."
                        )
                    }
                    .onFailure { throw it }

            } catch (_: BiometricAuthenticationFailedException) {
                showProgress(false)
                updateStatus("[Approach A] Biometric cancelled or failed. Try again.")
            } catch (e: VaultException) {
                showProgress(false)
                showError("Vault Error [${e.errorCode}]", "${e.message}\n\n${e.remediation}")
            } catch (e: Exception) {
                showProgress(false)
                showError("Send Failed", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // APPROACH B — Custom Key Storage (no passkey / your own auth)
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Approach B – Init (global custom signer)
     *
     * Provide your own TransactionSigner via InitOptions.custom().
     * The signer below stores an Ed25519 key in memory with NO passphrase —
     * but you could swap it for anything: HSM, remote MPC, encrypted file, etc.
     *
     * After init, Altude.send(options) will use your signer automatically,
     * exactly like the vault approach but with your own signing logic.
     */
    private fun initCustomSigner() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("[Approach B] Generating in-memory key (no passkey)…")

                // ─── Create your custom signer ──────────────────────────────────
                val signer = NoPassphraseMemorySigner.generate()
                customSigner = signer
                // ───────────────────────────────────────────────────────────────

                // ─── Altude.setApiKey() with custom signer ─────────────────
                val result = Altude.setApiKey(
                    activity = this@SignerExamplesActivity,
                    apiKey   = apiKey,
                    options  = InitOptions.custom(signer)   // ← your signer goes here
                )
                // ───────────────────────────────────────────────────────────────

                result
                    .onSuccess {
                        showProgress(false)
                        updateStatus(
                            "✅ [Approach B] Custom signer ready!\n\n" +
                            "Wallet: ${signer.publicKey.toBase58().take(20)}…\n\n" +
                            "No biometric configured. Signing is instant.\n\n" +
                            "• 'Send (global)' uses the signer set during init.\n" +
                            "• 'Send (per-call)' passes the signer directly to Altude.send()."
                        )
                        customSendButton.isEnabled       = true
                        customSendPerCallButton.isEnabled = true
                        customInitButton.isEnabled       = false
                    }
                    .onFailure { throw it }

            } catch (e: Exception) {
                showProgress(false)
                showError("Custom Init Failed", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Approach B – Send (global signer, set during init)
     *
     * No signer argument passed → SDK uses the one registered via InitOptions.custom().
     * This is identical in call-site to Approach A; the difference is the signer
     * implementation (no biometric, no vault).
     */
    private fun sendWithCustomSignerGlobal() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("[Approach B – global] Sending instantly (no prompt)…")

                val options = SendOptions(
                    toAddress  = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq",
                    amount     = 0.001,
                    token      = Token.KIN.mint(),
                    commitment = Commitment.finalized
                )

                // ─── Same call as Approach A — signer is resolved from SdkConfig ─
                val result = Altude.send(options)
                // ───────────────────────────────────────────────────────────────

                result
                    .onSuccess { response ->
                        showProgress(false)
                        updateStatus(
                            "✅ [Approach B – global] Sent!\n\n" +
                            "Signature: ${response.Signature.take(20)}…\n\n" +
                            "Signed by the custom signer registered at init (no prompt)."
                        )
                    }
                    .onFailure { throw it }

            } catch (e: Exception) {
                showProgress(false)
                showError("Send Failed", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Approach B – Send (per-call signer override)
     *
     * Pass your signer directly to Altude.send(options, signer = …).
     * Useful when you need to use a different signer for a specific transaction
     * without changing the globally registered signer.
     */
    private fun sendWithCustomSignerPerCall() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("[Approach B – per-call] Sending with per-call signer override…")

                val signer = customSigner
                    ?: return@launch showError("Not Ready", "Initialize custom signer first.")

                val options = SendOptions(
                    toAddress  = "EykLriS4Z34YSgyPdTeF6DHHiq7rvTBaG2ipog4V2teq",
                    amount     = 0.001,
                    token      = Token.KIN.mint(),
                    commitment = Commitment.finalized
                )

                // ─── Pass signer directly — overrides whatever is in SdkConfig ──
                val result = Altude.send(options, signer = signer)
                // ───────────────────────────────────────────────────────────────

                result
                    .onSuccess { response ->
                        showProgress(false)
                        updateStatus(
                            "✅ [Approach B – per-call] Sent!\n\n" +
                            "Signature: ${response.Signature.take(20)}…\n\n" +
                            "Signer was passed directly to Altude.send() — not from SdkConfig."
                        )
                    }
                    .onFailure { throw it }

            } catch (e: Exception) {
                showProgress(false)
                showError("Send Failed", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════════

    private fun updateStatus(msg: String) = runOnUiThread { statusText.text = msg }

    private fun showProgress(show: Boolean) = runOnUiThread {
        progressBar.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showError(title: String, message: String) = runOnUiThread {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// Example: Custom TransactionSigner with NO passphrase
//
// Implements the TransactionSigner interface from the Altude SDK.
// Keys live in memory (app process) — no biometric, no PIN, no passphrase.
//
// Swap the body of signMessage() for your own logic:
//   • Read from an encrypted file you manage
//   • Call a remote HSM / MPC service
//   • Use your own Android Keystore key without biometric
//   • Derive from a seed phrase stored wherever you choose
// ════════════════════════════════════════════════════════════════════════════════
class NoPassphraseMemorySigner private constructor(private val keypair: Keypair) : TransactionSigner {

    override val publicKey: PublicKey
        get() = keypair.publicKey

    /**
     * Signs the message immediately — no auth prompt of any kind.
     * Replace with your own storage/signing logic as needed.
     */
    override suspend fun signMessage(message: ByteArray): ByteArray {
        return SolanaEddsa.sign(message, keypair)
    }

    companion object {
        /**
         * Generate a fresh random keypair (in-memory, no persistence).
         * In a real app you would load the key from your own secure storage.
         */
        fun generate(): NoPassphraseMemorySigner {
            val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val keypair = runBlocking { SolanaEddsa.createKeypairFromSecretKey(seed) }
            return NoPassphraseMemorySigner(keypair)
        }

        /**
         * Load from an existing raw 32-byte seed (your own storage).
         */
        @Suppress("unused")
        fun fromSeed(seed: ByteArray): NoPassphraseMemorySigner {
            require(seed.size == 32) { "Seed must be exactly 32 bytes" }
            val keypair = runBlocking { SolanaEddsa.createKeypairFromSecretKey(seed) }
            return NoPassphraseMemorySigner(keypair)
        }
    }
}

