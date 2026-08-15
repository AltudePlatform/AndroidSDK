package com.altude.android

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.altude.core.model.TransactionSigner
import com.altude.gasstation.AltudeGasStation
import foundation.metaplex.solanaeddsa.Keypair
import foundation.metaplex.solanaeddsa.SolanaEddsa
import foundation.metaplex.solanapublickeys.PublicKey
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom

/**
 * Minimal app-owned-signer example.
 *
 * Altude Gas Station never generates, stores, or defaults to a signer. This app is
 * responsible for producing a [TransactionSigner] and handing it to
 * [AltudeGasStation.init] — here that's [ExampleAppSigner], a trivial in-memory
 * Ed25519 signer. A production app would replace it with its own key-management
 * strategy (hardware-backed keystore, remote signing service, [com.altude.core.model.HotSigner], etc.).
 */
class MainActivity : AppCompatActivity() {

    private val apiKey = "my_apikey"
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var privateKeyInput: EditText
    private lateinit var initButton: Button
    private lateinit var transferButton: Button

    private var signer: ExampleAppSigner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        privateKeyInput = findViewById(R.id.privateKeyInput)
        initButton = findViewById(R.id.initButton)
        transferButton = findViewById(R.id.transferButton)

        initButton.setOnClickListener { initializeGasStation() }
        transferButton.setOnClickListener { signTestMessage() }
        findViewById<Button>(R.id.generateKeyButton).setOnClickListener { generateTestKeyPair() }

        updateStatus("Enter a private key (or tap 'Generate') and initialize.")

        val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        else
            @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0)

        findViewById<TextView>(R.id.tvBuildInfo).text =
            "pkg: $packageName  •  v${pInfo.versionName}"
    }

    /**
     * Construct an app-owned [TransactionSigner] and pass it directly to
     * [AltudeGasStation.init]. There is no Vault/default signer fallback — if this
     * step is skipped, subsequent Gas Station calls fail with a clear "no signer
     * configured" error.
     */
    private fun initializeGasStation() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("Initializing Gas Station with an app-owned signer...")

                val privateKeyStr = privateKeyInput.text.toString().takeIf { it.isNotEmpty() }
                    ?: run {
                        showProgress(false)
                        showErrorDialog("Private Key Required", "Enter a private key or tap 'Generate' to create one.")
                        return@launch
                    }

                val appSigner = ExampleAppSigner(privateKeyStr)
                signer = appSigner

                AltudeGasStation.init(this@MainActivity, apiKey, appSigner)
                    .onSuccess {
                        showProgress(false)
                        updateStatus(
                            "✅ Gas Station initialized!\n\n" +
                                "Public Key: ${appSigner.publicKey.toBase58()}\n\n" +
                                "Tap 'Sign Test Message' to test signing."
                        )
                        transferButton.isEnabled = true
                        initButton.isEnabled = false
                    }
                    .onFailure { e ->
                        showProgress(false)
                        showErrorDialog("Initialization Error", "Error: ${e.message}")
                    }
            } catch (e: Exception) {
                showProgress(false)
                showErrorDialog("Initialization Error", "Error: ${e.message}")
            }
        }
    }

    private fun signTestMessage() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("Signing a test message with the app-owned signer...")

                val activeSigner = signer ?: run {
                    throw IllegalStateException("Signer not initialized")
                }

                val testMessage = "Hello from Altude SDK".toByteArray()
                val signature = activeSigner.signMessage(testMessage)

                showProgress(false)
                updateStatus(
                    "✅ Message signed!\n\n" +
                        "Signature: ${signature.take(16).joinToString("") { "%02x".format(it) }}...\n\n" +
                        "Signed entirely by the app-owned signer — Gas Station never touched key material."
                )
            } catch (e: Exception) {
                showProgress(false)
                showErrorDialog("Signing Error", "Error: ${e.message}")
            }
        }
    }

    private fun generateTestKeyPair() {
        try {
            val seed = ByteArray(32)
            SecureRandom().nextBytes(seed)
            val keyPair = runBlocking { SolanaEddsa.createKeypairFromSecretKey(seed) }
            val privateKeyHex = seed.joinToString("") { "%02x".format(it) }

            privateKeyInput.setText(privateKeyHex)
            updateStatus(
                "✅ Test key pair generated!\n\n" +
                    "Public Key: ${keyPair.publicKey.toBase58()}\n\n" +
                    "Tap 'Initialize' to use this key."
            )
        } catch (e: Exception) {
            showErrorDialog("Error", "Could not generate key pair: ${e.message}")
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread { statusText.text = message }
    }

    private fun showProgress(show: Boolean) {
        runOnUiThread { progressBar.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE }
    }

    private fun showErrorDialog(title: String, message: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }
}

/**
 * Example of an app-owned [TransactionSigner] implementation.
 *
 * This is intentionally minimal (in-memory Ed25519 keypair) to demonstrate the shape
 * of the interface. Real apps should back this with proper key management — an Android
 * Keystore-backed signer, a remote/HSM signing service, or [com.altude.core.model.HotSigner]
 * if in-memory storage is acceptable for the use case.
 */
class ExampleAppSigner(private val privateKeyHex: String) : TransactionSigner {

    private val keyPair: Keypair = try {
        val seed = ByteArray(32)
        for (i in 0 until minOf(64, privateKeyHex.length) step 2) {
            if (i + 1 < privateKeyHex.length) {
                seed[i / 2] = privateKeyHex.substring(i, i + 2).toInt(16).toByte()
            }
        }
        runBlocking { SolanaEddsa.createKeypairFromSecretKey(seed) }
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid private key format: ${e.message}")
    }

    override val publicKey: PublicKey
        get() = keyPair.publicKey

    override suspend fun signMessage(message: ByteArray): ByteArray {
        return try {
            SolanaEddsa.sign(message, keyPair)
        } catch (e: Exception) {
            throw RuntimeException("Failed to sign message: ${e.message}")
        }
    }
}
