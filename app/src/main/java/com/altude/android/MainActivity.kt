package com.altude.android

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Main launcher — choose which SDK example to run.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnVaultExample).setOnClickListener {
            startActivity(Intent(this, VaultExampleActivity::class.java))
        }

        findViewById<Button>(R.id.btnSignerExamples).setOnClickListener {
            startActivity(Intent(this, SignerExamplesActivity::class.java))
        }

        findViewById<Button>(R.id.btnExternalSigner).setOnClickListener {
            startActivity(Intent(this, ExternalSignerExampleActivity::class.java))
        }

        findViewById<Button>(R.id.btnErrorHandling).setOnClickListener {
            startActivity(Intent(this, ErrorHandlingExampleActivity::class.java))
        }

        // Show build info so it's always obvious which APK / version is running
        val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        else
            @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0)

        findViewById<TextView>(R.id.tvBuildInfo).text =
            "pkg: $packageName  •  v${pInfo.versionName}"
    }
}

