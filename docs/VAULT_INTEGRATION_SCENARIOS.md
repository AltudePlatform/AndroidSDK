# Vault Integration Scenarios

This guide covers practical integration patterns for common real-world scenarios.

---

## Scenario 1: Single-Wallet Default Setup

**When to use:** Most common case. One user account, per-operation biometric.

### Implementation

```kotlin
// Application.kt
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize once at app startup
        AltudeGasStation.init(this, apiKey = "your_api_key")
    }
}

// In Activity/Fragment
class TransferActivity : AppCompatActivity() {
    fun performTransfer(recipient: String, amount: Double) {
        lifecycleScope.launch {
            try {
                val signer = SdkConfig.getInstance().currentSigner
                val tx = createTransferTx(recipient, amount)
                
                // User sees biometric prompt HERE
                val signature = signer.signMessage(tx.message)
                
                // Send to network
                broadcastTransaction(signature, tx)
                
            } catch (e: BiometricAuthenticationFailedException) {
                // User cancelled or auth failed
                showMessage("Please try again")
            } catch (e: VaultException) {
                handleVaultError(e)
            }
        }
    }
}
```

### Advantages
- ✅ Most secure—biometric every transaction
- ✅ Simplest implementation
- ✅ No session management needed

### Disadvantages
- ❌ Repeated biometric prompts in batch operations

---

## Scenario 2: Batch Operations (Batch Transfer, Airdrop)

**When to use:** Multiple transactions in one user action (batch transfer, airdrop).

### Implementation with Session-Based Mode

```kotlin
class AirdropActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable session-based mode for batch efficiency
        val options = InitOptions.vault(
            sessionTTLSeconds = 120,  // 2 minutes for airdrop
            authMode = VaultAuthMode.SessionBased
        )
        AltudeGasStation.init(this, apiKey, options)
    }
    
    fun performAirdrop(recipients: List<String>, amount: Double) {
        lifecycleScope.launch {
            try {
                val signer = SdkConfig.getInstance().currentSigner
                val progressDialog = showProgressDialog("Preparing transactions...")
                
                // Biometric prompt on FIRST transaction only
                val transactions = recipients.mapIndexed { index, recipient ->
                    progressDialog.progress = (index * 100) / recipients.size
                    val tx = createTransferTx(recipient, amount)
                    
                    // First signature: prompts biometric
                    // Subsequent signatures: no prompt (within 120s session)
                    signer.signMessage(tx.message)
                }
                
                // Broadcast all signatures
                progressDialog.progress = 90
                broadcastBatch(transactions, recipients)
                
                progressDialog.dismiss()
                showMessage("✅ Airdrop complete: ${recipients.size} transfers")
                
            } catch (e: BiometricAuthenticationFailedException) {
                showMessage("Airdrop cancelled or auth failed")
            } catch (e: VaultException) {
                showMessage("Error: ${e.message}")
            }
        }
    }
}
```

### Key Points
- **First sign**: Prompts for biometric
- **Subsequent signs** (within 120s): No prompt (session active)
- **After 120s**: Next transaction requires biometric again
- **UX Benefit**: Single auth for multiple operations

### Try-Catch Pattern

```kotlin
fun performBatchWithRetry(operations: List<Operation>) {
    lifecycleScope.launch {
        var remainingOps = operations
        var attemptCount = 0
        
        while (remainingOps.isNotEmpty() && attemptCount < 3) {
            try {
                val signer = SdkConfig.getInstance().currentSigner
                
                for (op in remainingOps) {
                    val sig = signer.signMessage(op.transaction.message)
                    broadcastTransaction(sig, op)
                }
                
                break  // Success, exit loop
                
            } catch (e: BiometricAuthenticationFailedException) {
                when (e.failureReason) {
                    FailureReason.TooManyAttempts -> {
                        // Wait and retry
                        showMessage("Too many attempts. Waiting...")
                        delay(30_000)  // 30 seconds
                        attemptCount++
                    }
                    FailureReason.UserCancelled -> {
                        showMessage("Operation cancelled")
                        break
                    }
                    else -> throw e
                }
            } catch (e: VaultLockedException) {
                // Session expired mid-batch—restart
                attemptCount++
                delay(1000)
            }
        }
        
        if (attemptCount >= 3) {
            showMessage("Failed after 3 attempts")
        }
    }
}
```

---

## Scenario 3: Multi-Wallet Support

**When to use:** User has multiple wallets (e.g., "personal", "business", "savings").

### Implementation

```kotlin
class MultiWalletActivity : AppCompatActivity() {
    
    enum class WalletType(val index: Int) {
        PERSONAL(0),
        BUSINESS(1),
        SAVINGS(2)
    }
    
    fun initializeWallet(walletType: WalletType) {
        val options = InitOptions.vault(
            walletIndex = walletType.index,
            sessionTTLSeconds = 45
        )
        AltudeGasStation.init(this, apiKey, options)
    }
    
    fun switchWallet(newWalletType: WalletType) {
        // Reinitialize with different walletIndex
        // This switches which Ed25519 keypair is derived from root seed
        initializeWallet(newWalletType)
        
        // Subsequent signing operations use new wallet
        val signer = SdkConfig.getInstance().currentSigner
        val publicKey = signer.publicKey
        updateUI("Switched to ${newWalletType.name}: $publicKey")
    }
    
    fun transferFromPersonalToBusinessWallet(amount: Double) {
        lifecycleScope.launch {
            try {
                // Sign from personal wallet
                initializeWallet(WalletType.PERSONAL)
                val personalSigner = SdkConfig.getInstance().currentSigner
                val fromAddress = personalSigner.publicKey.toBase58()
                
                // Create & sign transfer
                val tx = createTransferTx(
                    from = fromAddress,
                    to = getBusinessWalletAddress(),
                    amount = amount
                )
                val sig = personalSigner.signMessage(tx.message)
                broadcastTransaction(sig, tx)
                
                showMessage("✅ Transfer from Personal to Business complete")
                
            } catch (e: VaultException) {
                showMessage("Error: ${e.message}")
            }
        }
    }
}
```

### How Multi-Wallet Works
- **Root Seed**: Same for all wallets
- **Wallet Index**: Determins which keypair is derived
- **walletIndex = 0**: Personal wallet (Ed25519 keypair A)
- **walletIndex = 1**: Business wallet (Ed25519 keypair B)
- **walletIndex = 2**: Savings wallet (Ed25519 keypair C)

All wallets share same biometric protection and key encryption.

---

## Scenario 4: Custom Hardware Wallet Integration

**When to use:** User wants to use Ledger, Solflare, or other hardware wallet.

### Implementation

```kotlin
// Custom hardware wallet signer implementation
class HardwareWalletSigner(
    private val walletConnection: HardwareWalletConnection
) : TransactionSigner {
    
    override val publicKey: PublicKey
        get() = walletConnection.publicKey
    
    override suspend fun signMessage(message: ByteArray): ByteArray {
        // Delegate signing to hardware device
        return walletConnection.sign(message)
    }
}

class HardwareWalletActivity : AppCompatActivity() {
    
    fun initwitHardwareWallet() {
        lifecycleScope.launch {
            try {
                // Connect to hardware wallet
                val walletConnection = connectToLedger()
                val hwSigner = HardwareWalletSigner(walletConnection)
                
                // Initialize SDK with hardware signer
                val options = InitOptions.custom(hwSigner)
                AltudeGasStation.init(this@HardwareWalletActivity, apiKey, options)
                
                val signer = SdkConfig.getInstance().currentSigner
                showMessage("✅ Connected to hardware wallet: ${signer.publicKey}")
                
            } catch (e: Exception) {
                showMessage("Failed to connect: ${e.message}")
            }
        }
    }
    
    fun signWithHardware(tx: Transaction) {
        lifecycleScope.launch {
            try {
                // User sees hardware wallet prompt (on device)
                val signer = SdkConfig.getInstance().currentSigner
                val signature = signer.signMessage(tx.message)
                
                broadcastTransaction(signature, tx)
                showMessage("✅ Transaction signed by hardware wallet")
                
            } catch (e: Exception) {
                showMessage("Hardware signing failed: ${e.message}")
            }
        }
    }
}
```

### Key Differences from Vault
| Aspect | Vault | Hardware |
|--------|-------|----------|
| Key storage | Device Keystore | Hardware device |
| Biometric | App-level prompt | Device-level |
| Signing speed | Fast (cached) | Slower (device comm) |
| Use case | Mobile app | High security |

---

## Scenario 5: Offline Transaction Preparation

**When to use:** User can sign transactions offline, then broadcast when network is available.

### Implementation

```kotlin
class OfflineActivity : AppCompatActivity() {
    
    // Sign transactions even without network
    fun prepareOfflineTransactions(recipients: List<String>) {
        lifecycleScope.launch {
            try {
                val signer = SdkConfig.getInstance().currentSigner
                val signedTxs = mutableListOf<SignedTransaction>()
                
                for (recipient in recipients) {
                    // Create but don't broadcast
                    val tx = createTransferTx(recipient, 1.0)
                    
                    // Sign (requires biometric, but no network)
                    val signature = signer.signMessage(tx.message)
                    
                    signedTxs.add(SignedTransaction(tx, signature))
                }
                
                // Store signed transactions locally
                saveSignedTransactions(signedTxs)
                showMessage("✅ Prepared ${signedTxs.size} transactions (offline)")
                
            } catch (e: VaultException) {
                showMessage("Error: ${e.message}")
            }
        }
    }
    
    // Broadcast when network is back
    fun broadcastWhenOnline() {
        lifecycleScope.launch {
            val signedTxs = loadSignedTransactions()
            
            signedTxs.forEach { (tx, sig) ->
                try {
                    broadcastTransaction(sig, tx)
                } catch (e: NetworkException) {
                    showMessage("Network error: ${e.message}")
                }
            }
            
            showMessage("✅ Broadcast ${signedTxs.size} transactions")
        }
    }
}

data class SignedTransaction(
    val transaction: Transaction,
    val signature: ByteArray
)
```

---

## Scenario 6: Error Recovery with User Guidance

**When to use:** Provide helpful remediation for common errors.

### Implementation

```kotlin
class RobustTransferActivity : AppCompatActivity() {
    
    fun performTransferWithGracefulRecovery(recipient: String, amount: Double) {
        lifecycleScope.launch {
            try {
                val signer = SdkConfig.getInstance().currentSigner
                val tx = createTransferTx(recipient, amount)
                val signature = signer.signMessage(tx.message)
                
                broadcastTransaction(signature, tx)
                showSuccess("✅ Transfer complete!")
                
            } catch (e: BiometricNotAvailableException) {
                // Guide user to set up biometric
                showUserGuidance(
                    title = "Set Up Biometric",
                    message = "You need to set up fingerprint, face, or PIN to use this wallet.\n\n" +
                            "Settings > Security > Biometric",
                    action = "Open Settings",
                    onAction = { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                )
                
            } catch (e: BiometricInvalidatedException) {
                // CRITICAL: Keys are lost
                showDataLossWarning(
                    title = "Security Update",
                    message = "Your biometric enrollment has changed (fingerprints scanned, face updated).\n\n" +
                            "Your wallet keys cannot be recovered for security.\n\n" +
                            "NEXT STEPS:\n" +
                            "1. Note your backup phrases\n" +
                            "2. Clear app data: Settings > Apps > [App] > Storage > Clear Data\n" +
                            "3. Reinstall and restore from backup",
                    actions = listOf(
                        "Clear App Data" to { /* Open Settings */ },
                        "Restore from Backup" to { /* Backup recovery flow */ }
                    )
                )
                
            } catch (e: BiometricAuthenticationFailedException) {
                // Transient error—allow retry
                when (e.failureReason) {
                    FailureReason.UserCancelled -> {
                        showSnackbar("Transaction cancelled")
                    }
                    FailureReason.TooManyAttempts -> {
                        showError(
                            title = "Too Many Attempts",
                            message = "Please wait 30 seconds, then try again.",
                            retryAfterMs = 30_000
                        )
                    }
                    FailureReason.AuthenticationFailed -> {
                        showTip(
                            "Authentication failed. Try again.\n\n" +
                            "💡 Tips:\n" +
                            "• Keep your finger dry and clean\n" +
                            "• Ensure good lighting for facial recognition\n" +
                            "• Position face directly at camera"
                        )
                    }
                }
                
            } catch (e: VaultException) {
                // Generic vault error with remediation
                showError(
                    title = "Error (${e.errorCode})",
                    message = e.message ?: "Unknown error",
                    remediation = e.remediation
                )
            }
        }
    }
    
    private fun showUserGuidance(
        title: String,
        message: String,
        action: String,
        onAction: () -> Unit
    ) = AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(action) { _, _ -> onAction() }
        .setNegativeButton("Cancel", null)
        .show()
    
    private fun showDataLossWarning(
        title: String,
        message: String,
        actions: List<Pair<String, () -> Unit>>
    ) {
        // Show prominent warning dialog
        // User must acknowledge data loss
    }
    
    private fun showError(
        title: String,
        message: String,
        remediation: String = "",
        retryAfterMs: Long = 0
    ) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(if (remediation.isNotEmpty()) "$message\n\n$remediation" else message)
                .setPositiveButton("OK", null)
                .show()
            
            if (retryAfterMs > 0) {
                // Auto-enable retry button after timeout
            }
        }
    }
}
```

---

## Scenario 7: Analytics & Monitoring

**When to use:** Track Vault operations for analytics or debugging.

### Implementation

```kotlin
class AnalyticsHelper {
    
    fun logVaultOperation(operation: String, result: Result) {
        val metadata = mapOf(
            "operation" to operation,
            "timestamp" to System.currentTimeMillis(),
            "result" to when (result) {
                is Success -> "success"
                is Failure -> "failure"
            }
        )
        
        when (result) {
            is Success -> {
                analytics.log("vault_operation_success", metadata)
            }
            is Failure -> {
                val errorMetadata = metadata + mapOf(
                    "error_code" to result.exception.errorCode,
                    "error_type" to result.exception::class.simpleName
                )
                analytics.log("vault_operation_failure", errorMetadata)
            }
        }
    }
    
    fun trackBiometricPrompt(operation: String, outcome: String) {
        analytics.log("biometric_prompt", mapOf(
            "operation" to operation,
            "outcome" to outcome,  // "success", "cancelled", "failed", "too_many_attempts"
            "timestamp" to System.currentTimeMillis()
        ))
    }
    
    fun trackSessionUsage(sessionDurationMs: Long, operationCount: Int) {
        analytics.log("vault_session", mapOf(
            "duration_ms" to sessionDurationMs,
            "operations" to operationCount,
            "ops_per_auth" to operationCount / 1.0  // Efficiency metric
        ))
    }
}

class TransferActivityWithAnalytics : AppCompatActivity() {
    
    private val analytics = AnalyticsHelper()
    
    fun performTransfer(recipient: String, amount: Double) {
        val startTime = System.currentTimeMillis()
        
        lifecycleScope.launch {
            try {
                val signer = SdkConfig.getInstance().currentSigner
                val tx = createTransferTx(recipient, amount)
                val sig = signer.signMessage(tx.message)
                
                broadcastTransaction(sig, tx)
                
                analytics.logVaultOperation(
                    "transfer",
                    Success()
                )
                
            } catch (e: BiometricAuthenticationFailedException) {
                analytics.trackBiometricPrompt(
                    "transfer",
                    when (e.failureReason) {
                        FailureReason.UserCancelled -> "cancelled"
                        FailureReason.TooManyAttempts -> "too_many_attempts"
                        else -> "failed"
                    }
                )
                
                analytics.logVaultOperation("transfer", Failure(e))
                
            } catch (e: VaultException) {
                analytics.logVaultOperation("transfer", Failure(e))
            }
        }
    }
}
```

---

## Scenario 8: Graceful Vault Migration

**When to use:** Existing app using HotSigner, migrate to Vault safely.

### Implementation

```kotlin
class MigrationHelper {
    
    fun shouldMigrateToVault(): Boolean {
        val lastVersion = getAppVersion()
        return lastVersion < 2  // Version < 2 = old HotSigner
    }
    
    fun migrateToVault(context: Context, apiKey: String) {
        // Show migration prompt
        AlertDialog.Builder(context)
            .setTitle("Security Update")
            .setMessage("This app now uses biometric encryption for your wallet.\n\n" +
                    "Your existing wallet will be preserved. Next time you sign,\n" +
                    "you'll see a biometric prompt.")
            .setPositiveButton("Upgrade") { _, _ ->
                // Initialize Vault (coexists with old HotSigner)
                AltudeGasStation.init(context, apiKey)
                
                setAppVersion(2)
                setMigrationComplete(true)
            }
            .setNegativeButton("Later", null)
            .show()
    }
    
    fun ensureMigrationComplete(context: Context, apiKey: String) {
        if (shouldMigrateToVault() && !isMigrationComplete()) {
            migrateToVault(context, apiKey)
        }
    }
}

// In Activity
class MainActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val migration = MigrationHelper()
        
        // Check if migration needed
        migration.ensureMigrationComplete(this, apiKey)
        
        // After migration prompt, SDK is ready
        // Subsequent calls use Vault automatically
    }
}
```

---

## Summary: When to Use Each Pattern

| Scenario | Pattern | Key Feature |
|----------|---------|-------------|
| Simple app | Single wallet + per-op | Most secure |
| Batch operations | Session-based (2min) | Single auth for many ops |
| Multiple users | Multi-wallet | Different keypairs, same seed |
| High security | Hardware wallet | Keys never on phone |
| Offline prep | Sign then broadcast | Works offline |
| Production | Error guidance | User-friendly errors |
| Analytics | Monitoring hooks | Track usage |
| Legacy app | Safe migration | Backward compatible |

---

## Next Steps

- See [VaultExampleActivity.kt](../app/src/main/java/com/altude/android/VaultExampleActivity.kt) for complete single-wallet example
- See [ErrorHandlingExampleActivity.kt](../app/src/main/java/com/altude/android/ErrorHandlingExampleActivity.kt) for error patterns
- Read [VAULT_ADVANCED.md](VAULT_ADVANCED.md) for deep dives
- Review [ERROR_HANDLING_PATTERNS.md](ERROR_HANDLING_PATTERNS.md) for production patterns
