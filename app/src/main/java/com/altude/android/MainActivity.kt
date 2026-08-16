package com.altude.android

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.altude.core.config.SdkConfig
import com.altude.gasstation.AltudeGasStation
import kotlinx.coroutines.launch

/**
 * Minimal example using Core's encrypted local key manager.
 */
class MainActivity : AppCompatActivity() {
    private val apiKey = "my_apikey"
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var initButton: Button
    private lateinit var signButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        initButton = findViewById(R.id.initButton)
        signButton = findViewById(R.id.transferButton)

        initButton.setOnClickListener { initializeGasStation() }
        signButton.setOnClickListener { signTestMessage() }

        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }

        findViewById<TextView>(R.id.tvBuildInfo).text =
            "pkg: $packageName  •  v${packageInfo.versionName}"
    }

    private fun initializeGasStation() {
        lifecycleScope.launch {
            showProgress(true)
            updateStatus("Initializing Gas Station...")

            AltudeGasStation.init(this@MainActivity, apiKey)
                .onSuccess {
                    val address = SdkConfig.currentSigner?.publicKey?.toBase58()
                    showProgress(false)
                    updateStatus(
                        "Gas Station initialized.\n\n" +
                            "Local account: $address\n\n" +
                            "Tap 'Sign Test Message' to test signing."
                    )
                    signButton.isEnabled = true
                    initButton.isEnabled = false
                }
                .onFailure { error ->
                    showProgress(false)
                    showErrorDialog("Initialization Error", "Error: ${error.message}")
                }
        }
    }

    private fun signTestMessage() {
        lifecycleScope.launch {
            try {
                showProgress(true)
                val signer = requireNotNull(SdkConfig.currentSigner) {
                    "Gas Station is not initialized"
                }
                val signature = signer.signMessage("Hello from Altude SDK".toByteArray())

                showProgress(false)
                updateStatus(
                    "Message signed with the Core-managed local key.\n\n" +
                        "Signature: ${signature.take(16).joinToString("") { "%02x".format(it) }}..."
                )
            } catch (error: Exception) {
                showProgress(false)
                showErrorDialog("Signing Error", "Error: ${error.message}")
            }
        }
    }

    private fun updateStatus(message: String) {
        statusText.text = message
    }

    private fun showProgress(show: Boolean) {
        progressBar.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
