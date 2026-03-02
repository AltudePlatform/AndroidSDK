# Vault Error Handling Implementation Guide

Quick reference for implementing error handling in your app. Copy-paste ready code patterns.

## 1. Basic Error Handling (Per-Operation Mode)

### Transaction Signing with Error Handling

```kotlin
suspend fun signTransactionSafely(
    context: FragmentActivity,
    transaction: Transaction
): TransactionSignResult {
    return try {
        val signer = SdkConfig.currentSigner 
            ?: throw VaultLockedException()
        
        val signature = signer.signMessage(transaction.message)
        TransactionSignResult.Success(signature)
        
    } catch (e: BiometricAuthenticationFailedException) {
        when (e.failureReason) {
            FailureReason.UserCancelled -> {
                Log.i("Vault", "User cancelled authentication")
                TransactionSignResult.UserCancelled
            }
            FailureReason.TooManyAttempts -> {
                Log.w("Vault", "Too many failed attempts")
                TransactionSignResult.LockedOut(e.message ?: "Try again in 30 seconds")
            }
            else -> {
                Log.e("Vault", "Authentication failed: ${e.message}")
                TransactionSignResult.Error(e.message ?: "Authentication failed", e)
            }
        }
    } catch (e: BiometricNotAvailableException) {
        Log.e("Vault", "Biometric not available: ${e.message}")
        TransactionSignResult.Error(
            message = "Device security not set up",
            exception = e,
            remediation = e.remediation
        )
    } catch (e: VaultException) {
        Log.e("Vault", "[${e.errorCode}] ${e.message}", e)
        TransactionSignResult.Error(e.message ?: "Unknown error", e, e.remediation)
    } catch (e: Exception) {
        Log.e("Vault", "Unexpected error", e)
        TransactionSignResult.Error("Unexpected error: ${e.message}", e)
    }
}

sealed class TransactionSignResult {
    data class Success(val signature: ByteArray) : TransactionSignResult()
    object UserCancelled : TransactionSignResult()
    data class LockedOut(val message: String) : TransactionSignResult()
    data class Error(
        val message: String,
        val exception: Exception,
        val remediation: String = ""
    ) : TransactionSignResult()
}
```

### UI Implementation

```kotlin
lifecycleScope.launch {
    val result = signTransactionSafely(this@MainActivity, tx)
    
    when (result) {
        is TransactionSignResult.Success -> {
            showSuccess("Transaction signed successfully")
            proceedWithTransaction(result.signature)
        }
        
        is TransactionSignResult.UserCancelled -> {
            showMessage("Transaction cancelled. Try again when ready.")
        }
        
        is TransactionSignResult.LockedOut -> {
            showDialog(
                title = "Too Many Attempts",
                message = result.message,
                button = "OK"
            )
        }
        
        is TransactionSignResult.Error -> {
            showErrorDialog(result)
        }
    }
}

private fun showErrorDialog(error: TransactionSignResult.Error) {
    showDialog(
        title = "Error",
        message = error.message,
        detail = "Code: ${(error.exception as? VaultException)?.errorCode ?: "UNKNOWN"}",
        negativeButton = "Cancel",
        positiveButton = "Retry" to {
            // Retry
        },
        tertiaryButton = if (error.remediation.isNotEmpty()) {
            "Help" to {
                showDialog(
                    title = "How to Fix",
                    message = error.remediation,
                    button = "OK"
                )
            }
        } else null
    )
}
```

---

## 2. Initialization Error Handling

### Safe Vault Initialization

```kotlin
sealed class VaultInitResult {
    object Success : VaultInitResult()
    data class BiometricNotAvailable(val remediation: String) : VaultInitResult()
    data class InsufficientStorage(val requiredMB: Int, val availableMB: Int) : VaultInitResult()
    data class PermissionDenied(val permission: String) : VaultInitResult()
    data class AlreadyInitialized(val suggestion: String) : VaultInitResult()
    data class Error(val code: String, val message: String, val remediation: String) : VaultInitResult()
}

suspend fun initializeVaultSafely(
    context: FragmentActivity,
    apiKey: String
): VaultInitResult {
    return try {
        // Pre-check 1: Biometric availability
        if (!BiometricHandler.isBiometricAvailable(context)) {
            return VaultInitResult.BiometricNotAvailable(
                remediation = "Go to Settings > Biometric and set up fingerprint or face ID"
            )
        }
        
        // Pre-check 2: Storage space
        val availableMB = getAvailableStorageMB(context)
        if (availableMB < 50) {
            return VaultInitResult.InsufficientStorage(50, availableMB)
        }
        
        // Pre-check 3: Permissions
        if (!hasPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            return VaultInitResult.PermissionDenied(
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
        
        // Initialize vault
        AltudeGasStation.init(context, apiKey)
        VaultInitResult.Success
        
    } catch (e: BiometricNotAvailableException) {
        VaultInitResult.BiometricNotAvailable(e.remediation)
        
    } catch (e: VaultAlreadyInitializedException) {
        VaultInitResult.AlreadyInitialized(
            suggestion = "Vault is already set up. You can start using it immediately."
        )
        
    } catch (e: VaultInitFailedException) {
        VaultInitResult.Error(
            code = e.errorCode,
            message = e.message ?: "Initialization failed",
            remediation = e.remediation
        )
        
    } catch (e: VaultException) {
        VaultInitResult.Error(
            code = e.errorCode,
            message = e.message ?: "Unknown error",
            remediation = e.remediation
        )
        
    } catch (e: Exception) {
        Log.e("VaultInit", "Unexpected error", e)
        VaultInitResult.Error(
            code = "VAULT-9999",
            message = "Unexpected error: ${e.message}",
            remediation = "Try restarting the app"
        )
    }
}

fun getAvailableStorageMB(context: Context): Int {
    val stat = StatFs(context.filesDir.absolutePath)
    return (stat.availableBlocksLong * stat.blockSizeLong / 1024 / 1024).toInt()
}

fun hasPermission(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == 
           PackageManager.PERMISSION_GRANTED
}
```

### UI for Initialization

```kotlin
private fun initializeVaultWithUI() {
    // Show loading dialog
    val loadingDialog = showLoadingDialog("Initializing secure vault...")
    
    lifecycleScope.launch {
        val result = initializeVaultSafely(this@MainActivity, API_KEY)
        loadingDialog.dismiss()
        
        when (result) {
            is VaultInitResult.Success -> {
                showSuccessDialog(
                    title = "Vault Ready",
                    message = "Your secure wallet has been initialized.",
                    button = "Continue" to { proceedToApp() }
                )
            }
            
            is VaultInitResult.BiometricNotAvailable -> {
                showDialog(
                    title = "Biometric Not Set Up",
                    message = result.remediation,
                    positiveButton = "Open Settings" to {
                        launchBiometricSettings()
                    },
                    negativeButton = "Cancel",
                    onDismiss = { exitApp() }
                )
            }
            
            is VaultInitResult.InsufficientStorage -> {
                showDialog(
                    title = "Storage Space Required",
                    message = "Please free up at least ${result.requiredMB}MB.\n" +
                            "Current available: ${result.availableMB}MB",
                    positiveButton = "Open Settings" to {
                        val intent = Intent(Settings.ACTION_MANAGE_SPACE)
                        startActivity(intent)
                    }
                )
            }
            
            is VaultInitResult.PermissionDenied -> {
                showDialog(
                    title = "Permission Required",
                    message = "This app needs storage permission to secure your data.",
                    positiveButton = "Grant Permission" to {
                        requestPermission(result.permission)
                    }
                )
            }
            
            is VaultInitResult.AlreadyInitialized -> {
                Log.i("Vault", result.suggestion)
                proceedToApp()
            }
            
            is VaultInitResult.Error -> {
                showErrorDetailsDialog(
                    title = "Initialization Error",
                    code = result.code,
                    message = result.message,
                    remediation = result.remediation
                )
            }
        }
    }
}

private fun launchBiometricSettings() {
    val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
    startActivity(intent)
}
```

---

## 3. Session-Based Batch Operations

### Batch Signing with Session-Based Auth

```kotlin
suspend fun signTransactionBatch(
    context: FragmentActivity,
    transactions: List<Transaction>,
    sessionTTLSeconds: Int = 45
): BatchSignResult {
    return try {
        val signer = VaultSigner.createWithSession(
            context,
            appId = "com.example.app",
            sessionTTLSeconds = sessionTTLSeconds
        )
        
        val signatures = mutableListOf<ByteArray>()
        
        transactions.forEachIndexed { index, transaction ->
            try {
                val signature = signer.signMessage(transaction.message)
                signatures.add(signature)
                
                // Progress update
                Log.i("Vault", "Signed transaction ${index + 1}/${transactions.size}")
                
            } catch (e: BiometricAuthenticationFailedException) {
                // Session might have expired, but signer will re-prompt automatically
                Log.w("Vault", "Auth failed for tx ${index + 1}, retrying...")
                
                if (e.failureReason == FailureReason.UserCancelled) {
                    // User cancelled - stop batch
                    return BatchSignResult.PartiallySkipped(
                        signed = index,
                        skipped = transactions.size - index,
                        signatures = signatures
                    )
                } else {
                    // Retry current transaction
                    val retrySignature = signer.signMessage(transaction.message)
                    signatures.add(retrySignature)
                }
            }
        }
        
        BatchSignResult.Success(signatures)
        
    } catch (e: VaultException) {
        Log.e("Vault", "[${e.errorCode}] Batch failed: ${e.message}")
        BatchSignResult.Failed(
            code = e.errorCode,
            message = e.message ?: "Batch signing failed",
            remediation = e.remediation
        )
    } catch (e: Exception) {
        Log.e("Vault", "Unexpected batch error", e)
        BatchSignResult.Failed(
            code = "VAULT-9999",
            message = "Unexpected error: ${e.message}",
            remediation = "Retry the operation"
        )
    }
}

sealed class BatchSignResult {
    data class Success(val signatures: List<ByteArray>) : BatchSignResult()
    data class PartiallySkipped(
        val signed: Int,
        val skipped: Int,
        val signatures: List<ByteArray>
    ) : BatchSignResult()
    data class Failed(
        val code: String,
        val message: String,
        val remediation: String
    ) : BatchSignResult()
}
```

### UI for Batch Operations

```kotlin
lifecycleScope.launch {
    showLoadingDialog("Signing ${txList.size} transactions...", cancellable = false)
    
    val result = signTransactionBatch(this@MainActivity, txList, sessionTTLSeconds = 120)
    
    when (result) {
        is BatchSignResult.Success -> {
            showSuccess("All ${result.signatures.size} transactions signed!")
            submitTransactions(txList.zip(result.signatures))
        }
        
        is BatchSignResult.PartiallySkipped -> {
            showWarningDialog(
                title = "Some Transactions Skipped",
                message = "Signed: ${result.signed}\nSkipped: ${result.skipped}",
                positiveButton = "Submit Signed" to {
                    submitTransactions(txList.take(result.signed).zip(result.signatures))
                },
                negativeButton = "Retry All"
            )
        }
        
        is BatchSignResult.Failed -> {
            showErrorDialog(
                title = "Batch Failed",
                code = result.code,
                message = result.message,
                remediation = result.remediation,
                retryButton = true
            )
        }
    }
}
```

---

## 4. Error Analytics & Logging

### Error Tracking

```kotlin
object VaultErrorTracker {
    private val errorEvents = mutableListOf<ErrorEvent>()
    
    data class ErrorEvent(
        val timestamp: Long = System.currentTimeMillis(),
        val errorCode: String,
        val errorMessage: String,
        val errorType: String,
        val remediation: String,
        val userAction: String? = null,
        val deviceModel: String = Build.MODEL,
        val osVersion: Int = Build.VERSION.SDK_INT
    )
    
    fun trackError(exception: Exception, userAction: String? = null) {
        val event = when (exception) {
            is VaultException -> {
                ErrorEvent(
                    errorCode = exception.errorCode,
                    errorMessage = exception.message ?: "Unknown",
                    errorType = exception.javaClass.simpleName,
                    remediation = exception.remediation,
                    userAction = userAction
                )
            }
            else -> {
                ErrorEvent(
                    errorCode = "VAULT-9999",
                    errorMessage = exception.message ?: "Unknown",
                    errorType = exception.javaClass.simpleName,
                    remediation = "Check logs for details",
                    userAction = userAction
                )
            }
        }
        
        errorEvents.add(event)
        
        // Send to analytics
        FirebaseAnalytics.getInstance(context).logEvent("vault_error") {
            param("error_code", event.errorCode)
            param("error_type", event.errorType)
            param("device_model", event.deviceModel)
        }
        
        // Log for debugging
        Log.e("VaultError", "[${event.errorCode}] ${event.errorMessage}", exception)
    }
    
    fun getErrorStats(): ErrorStats {
        return ErrorStats(
            totalErrors = errorEvents.size,
            errorsByCode = errorEvents.groupingBy { it.errorCode }.eachCount(),
            errorsByType = errorEvents.groupingBy { it.errorType }.eachCount()
        )
    }
}

data class ErrorStats(
    val totalErrors: Int,
    val errorsByCode: Map<String, Int>,
    val errorsByType: Map<String, Int>
)
```

### Error Recovery Patterns

```kotlin
// Pattern 1: Exponential backoff retry
suspend fun <T> retryWithBackoff(
    maxRetries: Int = 3,
    initialDelayMs: Long = 1000,
    block: suspend () -> T
): T {
    var lastException: Exception? = null
    
    repeat(maxRetries) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            lastException = e
            
            if (attempt < maxRetries - 1) {
                val delayMs = initialDelayMs * (2L pow attempt)
                Log.w("Vault", "Retry ${attempt + 1}/$maxRetries after ${delayMs}ms")
                delay(delayMs)
            }
        }
    }
    
    throw lastException ?: Exception("Max retries exceeded")
}

// Pattern 2: Circuit breaker
class VaultCircuitBreaker(
    private val failureThreshold: Int = 5,
    private val resetTimeoutMs: Long = 60_000
) {
    private var failureCount = 0
    private var lastFailureTime = 0L
    
    suspend fun <T> execute(block: suspend () -> T): T {
        if (failureCount >= failureThreshold) {
            if (System.currentTimeMillis() - lastFailureTime > resetTimeoutMs) {
                failureCount = 0
            } else {
                throw VaultLockedException("Circuit breaker open, retry after cooldown")
            }
        }
        
        return try {
            block()
        } catch (e: Exception) {
            failureCount++
            lastFailureTime = System.currentTimeMillis()
            throw e
        }
    }
}

// Usage
val breaker = VaultCircuitBreaker()

lifecycleScope.launch {
    try {
        breaker.execute {
            signTransaction(tx)
        }
    } catch (e: VaultException) {
        // Handle
    }
}
```

---

## 5. Error Recovery Strategies

### Automatic Recovery for Non-Critical Errors

```kotlin
sealed class RecoveryStrategy {
    object Ignore : RecoveryStrategy()
    object Retry : RecoveryStrategy()
    data class RetryAfter(val delayMs: Long) : RecoveryStrategy()
    data class ClearData(val reason: String) : RecoveryStrategy()
    data class NotifyUser(val title: String, val message: String) : RecoveryStrategy()
}

object VaultErrorRecovery {
    fun getRecoveryStrategy(exception: VaultException): RecoveryStrategy {
        return when (exception.errorCode) {
            VaultErrorCodes.BIOMETRIC_UNAVAILABLE -> 
                RecoveryStrategy.NotifyUser(
                    "Biometric Required",
                    "Please set up fingerprint or face ID in device settings"
                )
            
            VaultErrorCodes.BIOMETRIC_INVALIDATED -> 
                RecoveryStrategy.ClearData(
                    reason = "Biometric credentials were updated"
                )
            
            VaultErrorCodes.BIOMETRIC_LOCKOUT -> 
                RecoveryStrategy.RetryAfter(30_000) // 30 seconds
            
            VaultErrorCodes.SESSION_EXPIRED -> 
                RecoveryStrategy.Retry // Automatic retry by signer
            
            VaultErrorCodes.INIT_PERMISSION_DENIED -> 
                RecoveryStrategy.NotifyUser(
                    "Permission Required",
                    "This app needs storage permission"
                )
            
            VaultErrorCodes.DECRYPTION_FAILED -> 
                RecoveryStrategy.ClearData(
                    reason = "Vault data corrupted"
                )
            
            else -> RecoveryStrategy.NotifyUser(
                "Error",
                exception.message ?: "Unknown error"
            )
        }
    }
    
    suspend fun applyRecovery(
        context: Context,
        strategy: RecoveryStrategy,
        onRetry: suspend () -> Unit = {}
    ) {
        when (strategy) {
            is RecoveryStrategy.Ignore -> {
                Log.i("VaultRecovery", "Ignoring error")
            }
            
            is RecoveryStrategy.Retry -> {
                Log.i("VaultRecovery", "Retrying operation")
                onRetry()
            }
            
            is RecoveryStrategy.RetryAfter -> {
                Log.i("VaultRecovery", "Retrying after ${strategy.delayMs}ms")
                delay(strategy.delayMs)
                onRetry()
            }
            
            is RecoveryStrategy.ClearData -> {
                Log.w("VaultRecovery", "Clearing vault data: ${strategy.reason}")
                // Clear corrupted vault
                File(context.filesDir, "vault_seed_$appId.encrypted").delete()
                
                // Restart app
                val intent = Intent(context, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                              Intent.FLAG_ACTIVITY_CLEAR_TASK
                context.startActivity(intent)
            }
            
            is RecoveryStrategy.NotifyUser -> {
                // UI will handle notification
            }
        }
    }
}
```

---

## 6. Testing Error Scenarios

### Unit Test Examples

```kotlin
@RunWith(RobolectricTestRunner::class)
class VaultErrorHandlingTest {
    
    private lateinit var context: Context
    
    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }
    
    @Test
    fun testBiometricNotAvailableHandling() {
        // When biometric is unavailable
        val exception = BiometricNotAvailableException()
        
        // Then error code is correct
        assertEquals("VAULT-0201", exception.errorCode)
        assertEquals(exception.remediation.isNotEmpty(), true)
    }
    
    @Test
    fun testVaultInitializationWithInvalidConfig() {
        // When creating signer with negative TTL
        val result = runBlocking {
            try {
                VaultSigner.createWithSession(context, "app", sessionTTLSeconds = -1)
                false
            } catch (e: VaultConfigurationException) {
                e.errorCode == VaultErrorCodes.INVALID_CONFIG
            }
        }
        
        assertTrue(result)
    }
    
    @Test
    fun testErrorTrackingAndAnalytics() {
        // When error occurs
        val exception = VaultLockedException()
        VaultErrorTracker.trackError(exception, "signTransaction")
        
        // Then error is tracked
        val stats = VaultErrorTracker.getErrorStats()
        assertEquals(1, stats.totalErrors)
        assertEquals(1, stats.errorsByCode[VaultErrorCodes.VAULT_LOCKED])
    }
}
```

---

## Error Handling Checklist

- [ ] Catch all `VaultException` types in sensitive operations
- [ ] Log errors with error code and remediation
- [ ] Display user-friendly messages (not technical details)
- [ ] Provide "Details" or "Help" button with remediation
- [ ] Implement retry logic for transient errors
- [ ] Track error analytics for debugging
- [ ] Test error scenarios (biometric disabled, storage full, etc.)
- [ ] Show loading/progress during operations
- [ ] Never show cryptic error codes in main UI message
- [ ] Provide clear call-to-action for recovery operations

