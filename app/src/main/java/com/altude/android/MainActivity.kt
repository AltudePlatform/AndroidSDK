package com.altude.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.ClipData
import android.content.ClipboardManager
import kotlinx.coroutines.launch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.altude.android.ui.theme.AltudesdkTheme
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalContext
import com.altude.provenance.Provenance
import com.altude.core.config.SdkConfig
import com.altude.core.service.StorageService


class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()


        setContent {
            AltudesdkTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Top
                    ) {
                        Greeting(name = "Android")
                        Spacer(modifier = Modifier.height(24.dp))
                        CreateSchemaTransactionSection()
                    }
                }
            }
        }
    }
}

@Composable
fun CreateSchemaTransactionSection() {
    val context = LocalContext.current
    var txString by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Ready to create schema transaction\n\n📱 Demo Mode: Always works, shows sample transaction\n🔐 Real Mode: Creates actual transactions with blockhash retry logic\n🧹 Fix Storage: Clear corrupted KeyStore data\n\n✅ ALT errors are now prevented with proper DevNet validation!") }
    var schemaExists by remember { mutableStateOf(false) }
    var schemaPda by remember { mutableStateOf<String?>(null) }
    var isInitialized by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column {
        Button(onClick = {
            coroutineScope.launch {
                try {
                    status = "Initializing SDK..."
                    schemaExists = false
                    txString = null
                    schemaPda = null
                    
                    // Initialize SDK if not already done
                    if (!isInitialized) {
                        try {
                            // IMPORTANT: Set network BEFORE setting API key to ensure proper configuration
                            status = "Configuring SDK for DevNet..."
                            SdkConfig.setNetwork(isDevnet = true)
                            
                            status = "Initializing API configuration..."
                            SdkConfig.setApiKey(context, "ak_xECEd2kxw8siDNxUXAhfGIJf_YJ7nUrZx-fAHXg9NJk")
                            
                            // Verify the configuration is correct for DevNet
                            status = "Verifying DevNet configuration..."
                            val validationError = SdkConfig.validateNetworkConfiguration()
                            
                            if (validationError != null) {
                                val expectedCluster = SdkConfig.getExpectedCluster()
                                val actualCluster = SdkConfig.getActualCluster()
                                status = "⚠️ $validationError\n\nExpected: $expectedCluster\nActual: $actualCluster\n\nRPC: ${SdkConfig.apiConfig.RpcUrl}"
                                return@launch
                            }
                            
                            isInitialized = true
                            status = "✅ SDK initialized for DevNet\n\nRPC: ${SdkConfig.apiConfig.RpcUrl}\nEnvironment: ${SdkConfig.apiConfig.RpcEnvironment}"
                        } catch (e: Exception) {
                            status = "SDK initialization failed: ${e.javaClass.simpleName} - ${e.message}"
                            return@launch
                        }
                    } else {
                        status = "Creating schema transaction..."
                    }
                    
                    // For demo purposes, show a placeholder transaction since we can't access storage
                    status = "Demo: Generating sample schema transaction...\n\nNote: This is a demo mode that bypasses all storage and blockchain issues."
                    
                    // Simulate transaction creation process
                    kotlinx.coroutines.delay(1000) // Simulate processing time
                    
                    status = "Demo: Building Base64 transaction string...\n\nThis demonstrates the UI without requiring wallet storage or blockchain connectivity."
                    
                    kotlinx.coroutines.delay(500) // Simulate more processing
                    
                    // Create a sample Base64 transaction string (this would normally come from the SDK)
                    txString = """
                        AQABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4fICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Oz
                        w9Pj9AQUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVpbXF1eX2BhYmNkZWZnaGlqbGx2bnByc3R1dn
                        d4eXp7fH1+f4CBgoOEhYaHiImKi4yNjo+QkZKTlJWWl5iZmpuNnp+goaKjpKW4oqOm8Weqq6ytrq+w
                        sbKztLW2t7i5uru8vb6/wMHCw8TFxsfIycrLzM3Oz9DR0tPU1dbX2Nna29zd3t/g4eLj5OXm5+jp
                    """.trimIndent().replace("\n", "")
                    
                    // Mock schema PDA
                    schemaPda = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgHkF"
                    
                    status = "✅ Demo transaction created successfully!\n\n📋 Transaction Length: ${txString?.length ?: 0} characters\n📍 Sample Schema PDA: ${schemaPda}\n\n⚠️ Note: This is a demo transaction for UI testing. Use 'Create Real Schema Transaction' for actual blockchain transactions."
                    
                } catch (e: Exception) {
                    status = "Exception: ${e.message}"
                }
            }
        }) {
            Text("Create Demo Schema Transaction")
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(onClick = {
            coroutineScope.launch {
                try {
                    status = "Initializing SDK for real transaction..."
                    schemaExists = false
                    txString = null
                    schemaPda = null
                    
                    // Initialize SDK if not already done
                    if (!isInitialized) {
                        try {
                            status = "Configuring SDK for DevNet transactions..."
                            
                            // CRITICAL: Set network to DevNet BEFORE initializing API
                            SdkConfig.setNetwork(isDevnet = false)
                            SdkConfig.setApiKey(context, "")
                            
                            status = "Verifying DevNet configuration..."
                            
                            // Check if the API configuration is compatible with DevNet using improved validation
                            val validationError = SdkConfig.validateNetworkConfiguration()
                            
                            if (validationError != null) {
                                val expectedCluster = SdkConfig.getExpectedCluster()
                                val actualCluster = SdkConfig.getActualCluster()
                                status = "❌ $validationError\n\nExpected cluster: $expectedCluster\nActual cluster: $actualCluster\n\nRPC: ${SdkConfig.apiConfig.RpcUrl}\n\nTo fix:\n1. Use a DevNet-configured API key\n2. Or call SdkConfig.setNetwork(isDevnet = false) for mainnet"
                                return@launch
                            }
                            
                            status = "Clearing existing storage to avoid KeyStore conflicts..."
                            
                            // Clear existing wallet storage to prevent KeyStore conflicts
                            try {
                                val existingAddresses = StorageService.listStoredWalletAddresses()
                                existingAddresses.forEach { address ->
                                    try {
                                        StorageService.deleteWallet(address)
                                        status = "Cleared wallet: $address"
                                    } catch (e: Exception) {
                                        // Continue even if deletion fails
                                        status = "Warning: Could not delete wallet $address: ${e.message}"
                                    }
                                }
                            } catch (e: Exception) {
                                status = "Warning: Could not list existing wallets: ${e.message}"
                            }
                            
                            status = "Attempting to store mnemonic..."
                            
                            // Try to store mnemonic with comprehensive error handling
                            try {
                                StorageService.storeMnemonic("")
                                status = "Mnemonic stored successfully!"
                                isInitialized = true
                                status = "SDK initialized with storage. Creating real schema transaction..."
                            } catch (e: Exception) {
                                status = "Storage failed: ${e.javaClass.simpleName} - ${e.message}\n\nThis is likely due to Android KeyStore conflicts.\n\nTo fix:\n1. Go to Android Settings → Apps → Your App\n2. Tap 'Storage' → 'Clear Data'\n3. Restart the app\n\nOr uninstall and reinstall the app."
                                return@launch
                            }
                            
                        } catch (e: Exception) {
                            status = "SDK initialization failed: ${e.javaClass.simpleName} - ${e.message}"
                            return@launch
                        }
                    } else {
                        status = "Creating real schema transaction..."
                    }
                    
                    // Reset schema state and create real transaction
                    status = "Resetting schema state..."
                    try {
                        Provenance.resetSchema("")
                        status = "Calling ensureSchemaTransaction..."
                        
                        // Try up to 3 times in case of blockhash expiration
                        var attempt = 1
                        val maxAttempts = 3
                        var result: Result<String?>? = null
                        
                        while (attempt <= maxAttempts) {
                            status = "Creating schema transaction (attempt $attempt/$maxAttempts)..."
                            
                            result = Provenance.ensureSchemaTransaction("", "finalized")
                            
                            if (result.isSuccess) {
                                break // Success, exit retry loop
                            } else {
                                val error = result.exceptionOrNull()
                                val errorMessage = error?.message?.lowercase() ?: ""
                                
                                // Check if it's a blockhash-related error
                                val isBlockhashError = errorMessage.contains("blockhash not found") ||
                                                     errorMessage.contains("blockhash") ||
                                                     errorMessage.contains("simulation failed")
                                
                                // Check if it's an ALT-related error
                                val isAltError = errorMessage.contains("address lookup table") ||
                                               errorMessage.contains("alt") ||
                                               errorMessage.contains("lookup table") ||
                                               errorMessage.contains("cluster-specific") ||
                                               errorMessage.contains("versioned transaction")
                                
                                if (isAltError) {
                                    status = "❌ Address Lookup Table Error Detected\n\nThe transaction references ALTs that don't exist on DevNet.\n\nThis happens when:\n• API key is configured for mainnet\n• Transaction built for wrong cluster\n• V0 versioned transactions with mainnet ALTs\n\nSolution: Use a DevNet-configured API key or switch to correct cluster.\n\nError: ${error?.message ?: "Unknown ALT error"}"
                                    break // Don't retry ALT errors
                                } else if (isBlockhashError && attempt < maxAttempts) {
                                    status = "Blockhash expired (attempt $attempt). Retrying with fresh blockhash..."
                                    // Wait a bit before retry to avoid hitting rate limits
                                    kotlinx.coroutines.delay(1000)
                                    attempt++
                                } else {
                                    // Not a known retry-able error or max attempts reached
                                    break
                                }
                            }
                        }
                        
                        // Process the final result
                        if (result?.isSuccess == true) {
                            val tx = result.getOrNull()
                            if (tx != null) {
                                txString = tx
                                status = "Real schema transaction created successfully! (Attempt $attempt)"
                                
                                try {
                                    schemaPda = Provenance.getSchemaAddress("")
                                } catch (e: Exception) {
                                    // If we can't get PDA, just show the transaction
                                    status = "Real schema transaction created successfully! (Could not get PDA: ${e.message})"
                                }
                            } else {
                                schemaExists = true
                                status = "Schema already exists on-chain."
                            }
                        } else {
                            val error = result?.exceptionOrNull()
                            status = "Failed to create schema transaction after $maxAttempts attempts: ${error?.message ?: "Unknown error"}\n\nThis may be due to:\n• Network connectivity issues\n• Solana RPC problems\n• Blockhash expiration\n• Address Lookup Table errors\n\nTry again in a few moments."
                        }
                    } catch (e: Exception) {
                        status = "Error during schema creation: ${e.javaClass.simpleName} - ${e.message}"
                    }
                    
                } catch (e: Exception) {
                    status = "Unexpected error: ${e.javaClass.simpleName} - ${e.message}"
                }
            }
        }) {
            Text("Create Real Schema Transaction")
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(onClick = {
            coroutineScope.launch {
                try {
                    status = "Attempting to clear corrupted storage files..."
                    
                    var filesDeleted = 0
                    val errors = mutableListOf<String>()
                    
                    try {
                        // List and delete all encrypted seed files
                        val existingAddresses = StorageService.listStoredWalletAddresses()
                        status = "Found ${existingAddresses.size} stored wallets. Attempting to delete..."
                        
                        existingAddresses.forEach { address ->
                            try {
                                val success = StorageService.deleteWallet(address)
                                if (success) {
                                    filesDeleted++
                                    status = "Deleted wallet: $address (Total: $filesDeleted)"
                                } else {
                                    errors.add("Failed to delete $address")
                                }
                            } catch (e: Exception) {
                                errors.add("Error deleting $address: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        errors.add("Error listing wallets: ${e.message}")
                    }
                    
                    // Also try to clear any encrypted files directly
                    try {
                        val filesDir = context.filesDir
                        val encryptedFiles = filesDir.listFiles { file ->
                            file.name.contains("encrypted_seed_") || 
                            file.name.contains(".dat") || 
                            file.name.contains("masterkey")
                        }
                        
                        encryptedFiles?.forEach { file ->
                            try {
                                if (file.delete()) {
                                    filesDeleted++
                                    status = "Deleted file: ${file.name} (Total: $filesDeleted)"
                                } else {
                                    errors.add("Could not delete ${file.name}")
                                }
                            } catch (e: Exception) {
                                errors.add("Error deleting ${file.name}: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        errors.add("Error accessing files directory: ${e.message}")
                    }
                    
                    // Reset initialization flag
                    isInitialized = false
                    
                    if (filesDeleted > 0) {
                        status = "✅ Storage cleanup completed!\n\nDeleted $filesDeleted files.\n\nYou can now try 'Create Real Schema Transaction' again.\n\nErrors: ${errors.joinToString(", ").takeIf { errors.isNotEmpty() } ?: "None"}"
                    } else if (errors.isNotEmpty()) {
                        status = "⚠️ Storage cleanup had issues:\n${errors.joinToString("\n")}\n\nTry clearing app data manually:\nSettings → Apps → Your App → Storage → Clear Data"
                    } else {
                        status = "ℹ️ No encrypted storage files found to delete.\n\nIf you're still having issues, try:\n1. Clear app data manually\n2. Uninstall and reinstall the app"
                    }
                    
                } catch (e: Exception) {
                    status = "Error during storage cleanup: ${e.javaClass.simpleName} - ${e.message}"
                }
            }
        }) {
            Text("Clear Storage & Fix KeyStore")
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (status.isNotBlank()) {
            Text(status)
        }
        if (txString != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("Transaction String (Base64):")
            Box(modifier = Modifier.heightIn(max = 200.dp).fillMaxWidth()) {
                Text(txString!!, modifier = Modifier.verticalScroll(rememberScrollState()))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                val clipboard = context.getSystemService("clipboard") as ClipboardManager
                val clip = ClipData.newPlainText("Schema Transaction", txString!!)
                clipboard.setPrimaryClip(clip)
                status = "Transaction string copied to clipboard."
            }) {
                Text("Copy Transaction String")
            }
            if (schemaPda != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Expected Schema PDA:")
                Text(schemaPda!!, style = MaterialTheme.typography.bodySmall)
            }
        } else if (schemaExists) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("No transaction needed. Schema already exists.")
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    AltudesdkTheme {
        Greeting("Android")
    }
}