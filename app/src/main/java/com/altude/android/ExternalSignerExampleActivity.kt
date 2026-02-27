package com.altude.android

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.altude.gasstation.AltudeGasStation
import com.altude.core.config.InitOptions
import com.altude.core.config.SignerStrategy
import com.altude.core.model.TransactionSigner
import foundation.metaplex.solanaeddsa.Keypair
import foundation.metaplex.solanaeddsa.SolanaEddsa
import foundation.metaplex.solanapublickeys.PublicKey
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking

/**
 * External Signer Example Activity
 *
 * Demonstrates how to use a custom/external signer instead of Vault:
 * - HotSigner (legacy, keys in app memory)
 * - Custom hardware wallet integration
 * - Multi-signature wallet
 * - Web3 provider integration
 *
 * Features shown:
 * - Create custom TransactionSigner implementation
 * - Pass to AltudeGasStation via InitOptions
 * - Perform transactions with external signer
 * - Error handling for signer-specific errors
 */
class ExternalSignerExampleActivity : AppCompatActivity() {
    
    private val apiKey = "my_apikey"
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var privateKeyInput: EditText
    private lateinit var initButton: Button
    private lateinit var transferButton: Button
    
    private var customSigner: CustomTestSigner? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_external_signer_example)
        
        // Initialize UI
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        privateKeyInput = findViewById(R.id.privateKeyInput)
        initButton = findViewById(R.id.initButton)
        transferButton = findViewById(R.id.transferButton)
        
        initButton.setOnClickListener { initializeWithExternalSigner() }
        transferButton.setOnClickListener { performTransfer() }
        
        updateStatus("Welcome to External Signer Integration!\n\n" +
                "Enter a private key (or tap 'Generate') and initialize.")
        
        // Button to generate test key
        findViewById<Button>(R.id.generateKeyButton).setOnClickListener {
            generateTestKeyPair()
        }
    }
    
    /**
     * Initialize with Custom External Signer
     *
     * Pattern:
     * 1. Create custom TransactionSigner
     * 2. Wrap in SignerStrategy.External()
     * 3. Pass to AltudeGasStation via InitOptions
     * 4. No Vault initialization - uses external signer instead
     */
    private fun initializeWithExternalSigner() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("Initializing with external signer...\n\n" +
                        "Creating custom signer from private key.")
                
                val privateKeyStr = privateKeyInput.text.toString().takeIf { it.isNotEmpty() }
                    ?: run {
                        showProgress(false)
                        showErrorDialog(
                            "Private Key Required",
                            "Enter a private key or tap 'Generate' to create one."
                        )
                        return@launch
                    }
                
                // Step 1: Create custom signer
                // (In real app, could be HotSigner, hardware wallet, etc.)
                val externalSigner = CustomTestSigner(privateKeyStr)
                customSigner = externalSigner
                
                // Step 2: Create InitOptions with external signer
                val options = InitOptions.custom(externalSigner)
                
                // Step 3: Initialize with external signer (no Vault)
                // Note: This is different from VaultExampleActivity
                // - No biometric authentication
                // - Key held in memory (unsafe for production)
                // - Full control over signing logic
                AltudeGasStation.init(this@ExternalSignerExampleActivity, apiKey, options)
                
                showProgress(false)
                updateStatus("✅ External Signer Initialized!\n\n" +
                        "Public Key: ${externalSigner.publicKey.toBase58().take(20)}...\n\n" +
                        "⚠️ Warning: This key is in app memory (not secure).\n" +
                        "Use Vault for production apps.\n\n" +
                        "Tap 'Send Transfer' to test.")
                
                transferButton.isEnabled = true
                initButton.isEnabled = false
                
            } catch (e: Exception) {
                showProgress(false)
                showErrorDialog(
                    "Initialization Error",
                    "Error: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Perform Transaction with External Signer
     *
     * Demonstrates:
     * - No biometric prompt (external signer doesn't require it)
     * - Immediate signing
     * - Full control over signing logic
     */
    private fun performTransfer() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                updateStatus("Signing transfer with external signer...\n\n" +
                        "No biometric prompt needed.")
                
                val signer = customSigner ?: run {
                    throw Exception("Signer not initialized")
                }
                
                // Create test transaction
                val testTx = "Transfer 100 USDC to recipient".toByteArray()
                
                // Sign immediately (no prompt)
                val signature = signer.signMessage(testTx)
                
                showProgress(false)
                updateStatus("✅ Transaction Signed!\n\n" +
                        "Signature: ${signature.take(16).joinToString("") { "%02x".format(it) }}...\n\n" +
                        "Signed with external signer (immediate, no prompt).\n" +
                        "This is fast but less secure than Vault.")
                
            } catch (e: Exception) {
                showProgress(false)
                showErrorDialog(
                    "Signing Error",
                    "Error: ${e.message}"
                )
            }
        }
    }
    
    private fun generateTestKeyPair() {
        try {
            // Generate random 32-byte seed for Ed25519
            val seed = ByteArray(32)
            SecureRandom().nextBytes(seed)
            // Use runBlocking to call suspend function
            val keyPair = runBlocking {
                SolanaEddsa.createKeypairFromSecretKey(seed)
            }
            val privateKeyHex = seed.joinToString("") { "%02x".format(it) }
            
            privateKeyInput.setText(privateKeyHex)
            updateStatus("✅ Test key pair generated!\n\n" +
                    "Public Key: ${keyPair.publicKey.toBase58()}\n\n" +
                    "Tap 'Initialize' to use this key.")
            
        } catch (e: Exception) {
            showErrorDialog("Error", "Could not generate key pair: ${e.message}")
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
 * Custom Test Signer - Example of TransactionSigner Implementation
 *
 * This demonstrates how to implement the TransactionSigner interface
 * for any custom signing logic (hardware wallet, multi-sig, etc.)
 *
 * Key points:
 * - Implement TransactionSigner from SDK
 * - Provide publicKey property
 * - Implement signMessage() suspend function
 * - Handle all exceptions properly
 */
class CustomTestSigner(private val privateKeyHex: String) : TransactionSigner {
    
    private val keyPair: Keypair = try {
        // Parse hex string to 32-byte seed and create keypair
        val seed = ByteArray(32)
        for (i in 0 until minOf(64, privateKeyHex.length) step 2) {
            if (i + 1 < privateKeyHex.length) {
                seed[i / 2] = privateKeyHex.substring(i, i + 2).toInt(16).toByte()
            }
        }
        // Use runBlocking to call suspend function from init block
        runBlocking {
            SolanaEddsa.createKeypairFromSecretKey(seed)
        }
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid private key format: ${e.message}")
    }
    
    override val publicKey: PublicKey
        get() = keyPair.publicKey
    
    override suspend fun signMessage(message: ByteArray): ByteArray {
        return try {
            // Sign using SolanaEddsa (Metaplex signing utility)
            SolanaEddsa.sign(message, keyPair)
        } catch (e: Exception) {
            throw RuntimeException("Failed to sign message: ${e.message}")
        }
    }
}
