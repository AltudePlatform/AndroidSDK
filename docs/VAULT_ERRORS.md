# Vault Module - Error Troubleshooting Guide

## Error Taxonomy

This guide covers all Vault exceptions, their causes, and solutions.

---

## BiometricNotAvailableException

### Error Message
```
Biometric authentication is not available on this device
Go to Settings > Security > Biometric (or Screen Lock) to set up device authentication
```

### Causes
- Device doesn't support biometric (no fingerprint sensor, face camera, etc.)
- Biometric hardware is not available or disabled
- User hasn't enrolled any biometric credentials
- Device security update required for biometric support
- Biometric features not supported on this Android version (pre-API 28)

### Solutions

**For End Users:**
1. Go to device Settings
2. Navigate to Security or Biometrics
3. Enroll fingerprint, face, iris, or pattern/PIN
4. Return to app and retry

**For Developers:**
```kotlin
try {
    AltudeGasStation.init(context, apiKey)
} catch (e: BiometricNotAvailableException) {
    when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> {
            // Biometric not available on older Android
            showUserMessage("This device needs Android 9.0 or later for Vault")
        }
        else -> {
            // Show setup instructions
            showUserMessage(e.remediation)
            openSystemSecuritySettings(context)
        }
    }
}
```

**Fallback (Not Recommended):**
If your app absolutely cannot use Vault, use a non-Vault signer:
```kotlin
AltudeGasStation.init(
    context,
    apiKey,
    InitOptions(
        signerStrategy = SignerStrategy.External(customSigner)
    )
)
```

---

## BiometricInvalidatedException

### Error Message
```
Biometric credentials have been invalidated
Your stored keys are no longer accessible. Uninstall and reinstall the app, 
or clear app data to start fresh.
```

### Causes
- User's fingerprints changed or were re-scanned
- Biometric enrollment was updated or changed
- Face ID re-enrollment occurred
- Security policy changed on device
- Android Keystore invalidation triggered by system

### Why This Happens
Android Keystore **invalidates keys automatically** when biometric enrollment changes. This is a security feature - it prevents someone from silently re-enrolling a fingerprint and accessing your keys.

### Solutions

**For End Users:**
- Option 1: Follow app prompt to "Reset App" or "Clear Data"
  - Reinstalls key storage but loses wallet history
- Option 2: Uninstall and reinstall app
  - Clean slate, new vault on reinstall
- Option 3: Contact app support for data recovery (not always possible)

**For Developers:**
```kotlin
try {
    Altude.send(options)
} catch (e: BiometricInvalidatedException) {
    // Show user-friendly message
    showCriticalAlert(
        title = "Biometric Changed",
        message = e.remediation,  // "Clear app data or reinstall..."
        primaryAction = "Clear App Data" to { clearAppData() },
        secondaryAction = "Go to Settings" to { openSettings() }
    )
}
```

**Programmatic Recovery (Not Guaranteed):**
```kotlin
// Attempt to clear vault and reinitialize
try {
    val appId = context.packageName
    AltudeGasStation.clearVault(context, appId)
    AltudeGasStation.init(context, apiKey)
} catch (e: Exception) {
    // May still fail if Android Keystore is corrupted
    // User needs to clear app data
}
```

---

## BiometricAuthenticationFailedException

### Error Message
```
Biometric authentication failed or was cancelled
Try again or use another authentication method on your device
```

### Causes
- User tapped "Cancel" button on biometric prompt
- Biometric verification failed (fingerprint not recognized, face not matched)
- Biometric sensor malfunction
- Too many failed attempts (locked out temporarily)

### Solutions

**Recovery Steps:**
1. **User cancelled:** Just prompt again - no penalty
2. **Failed attempts:** Wait for timeout (usually 30 seconds) and retry
3. **Sensor malfunction:** Check device, may need hardware service
4. **Alternative auth:** Use PIN/pattern/password if available on device

**Code Example:**
```kotlin
var retryCount = 0
const val MAX_RETRIES = 3

suspend fun signWithRetry() {
    try {
        Altude.send(options)
    } catch (e: BiometricAuthenticationFailedException) {
        retryCount++
        if (retryCount < MAX_RETRIES) {
            showMessage("Authentication failed. Try again.")
            signWithRetry()
        } else {
            showMessage("Max retries exceeded. Please try later.")
        }
    }
}
```

---

## BiometricStatusUpdatedException

### Error Message
```
Biometric credentials have changed
Biometric status has been updated. Please authenticate again.
```

### Causes
- Biometric enrollment status changed during operation
- Biometric was disabled mid-transaction
- Security policy changed mid-transaction
- Device locked or unlocked mid-transaction

### Solutions
Simply retry - this is typically transient:
```kotlin
try {
    Altude.send(options)
} catch (e: BiometricStatusUpdatedException) {
    // Retry automatically once
    Altude.send(options)
}
```

---

## VaultLockedException

### Error Message
```
Vault is locked or not initialized
Call AltudeGasStation.init() to initialize the vault
```

### Causes
- `AltudeGasStation.init()` was not called
- Session expired and no keypair cached
- `AltudeGasStation.lockVault()` was called

### Solutions

**If Vault Not Initialized:**
```kotlin
// Must call init first
AltudeGasStation.init(context, apiKey)

// Then use Altude
Altude.send(options)
```

**If Session Expired:**
Just make another transaction - it will automatically re-prompt:
```kotlin
// Transaction 1 (prompts)
Altude.send(options1)

// Wait > 45 seconds...

// Transaction 2 (prompts again)
Altude.send(options2)
```

**If Manually Locked:**
```kotlin
// You locked the vault
AltudeGasStation.lockVault()

// Next transaction will prompt for biometric
Altude.send(options)
```

---

## VaultInitFailedException

### Error Message
```
Failed to initialize vault
Check device storage and permissions, then try again
```

### Causes
- Insufficient storage space
- Android Keystore initialization failure
- File system permission denied
- Corrupted Keystore data
- Device security restrictions

### Solutions

**Storage Check:**
```kotlin
val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
val availableBytes = storageManager.storageVolume?.getDirectory()?.freeSpace ?: 0
if (availableBytes < 1_000_000) {  // <1MB
    showMessage("Insufficient storage. Please free up space.")
}
```

**Permission Check:**
```kotlin
// Ensure app has permission to write to app-specific storage
if (!context.filesDir.canWrite()) {
    showMessage("App doesn't have storage permission")
}
```

**Retry Logic:**
```kotlin
suspend fun initWithRetry(maxAttempts: Int = 3) {
    repeat(maxAttempts) { attempt ->
        try {
            AltudeGasStation.init(context, apiKey)
            return  // Success
        } catch (e: VaultInitFailedException) {
            if (attempt < maxAttempts - 1) {
                delay(1000L * (attempt + 1))  // Exponential backoff
            } else {
                throw e  // All retries failed
            }
        }
    }
}
```

---

## VaultDecryptionFailedException

### Error Message
```
Failed to decrypt vault data
Vault data may be corrupted. Uninstall and reinstall the app to reset.
```

### Causes
- Encrypted file corrupted
- Keystore key deleted
- Incompatible vault format version
- Data tampered with
- Keystore hardware failure

### Solutions

**Nuclear Option (Lossy):**
```kotlin
// Clear vault and start fresh (loses key history)
AltudeGasStation.clearVault(context, context.packageName)
AltudeGasStation.init(context, apiKey)
```

**User-Friendly Approach:**
```kotlin
try {
    Altude.send(options)
} catch (e: VaultDecryptionFailedException) {
    showAlert(
        title = "Vault Corrupted",
        message = "The vault data appears to be corrupted. " + e.remediation,
        action1 = "Reset Vault" to { resetVault() },
        action2 = "Contact Support" to { openSupport() }
    )
}

private fun resetVault() {
    lifecycleScope.launch {
        AltudeGasStation.clearVault(context, context.packageName)
        AltudeGasStation.init(context, apiKey)
        showMessage("Vault reset. You can sign transactions again.")
    }
}
```

---

## VaultAlreadyInitializedException

### Error Message
```
Vault is already initialized
Vault has been created. Use the existing vault or clear data to create a new one.
```

### Causes
- Calling `VaultManager.createVault()` when vault already exists
- Calling `AltudeGasStation.init()` multiple times

### Solutions

**Safe Pattern:**
```kotlin
// AltudeGasStation.init() handles already-initialized vault gracefully
AltudeGasStation.init(context, apiKey)
AltudeGasStation.init(context, apiKey)  // Safe; no error
```

**If Using VaultManager Directly:**
```kotlin
// Check first
val appId = context.packageName
if (!VaultManager.vaultExists(context, appId)) {
    VaultManager.createVault(context, appId)
}

// Or use idempotent pattern
try {
    VaultManager.createVault(context, appId)
} catch (e: VaultAlreadyInitializedException) {
    // Expected; vault exists
}
```

---

## IllegalArgumentException: FragmentActivity Required

### Error Message
```
VaultSigner requires FragmentActivity context for biometric prompts. 
Got [ClassName] instead.
```

### Cause
Passed a non-Activity context (e.g., Service, Application) where FragmentActivity is required.

### Solutions

**Pass Correct Context:**
```kotlin
// ❌ Wrong
class MyService : Service {
    fun init() {
        AltudeGasStation.init(this, apiKey)  // 'this' is Service, not Activity
    }
}

// ✅ Correct
class MainActivity : FragmentActivity {
    fun init() {
        AltudeGasStation.init(this, apiKey)  // 'this' is FragmentActivity
    }
}
```

**If in Service/ViewModel:**
```kotlin
class MyViewModel(private val activity: FragmentActivity) : ViewModel() {
    suspend fun initVault() {
        AltudeGasStation.init(activity, apiKey)  // Pass Activity reference
    }
}
```

---

## Generic Exception Handling

For errors not caught specifically:

```kotlin
suspend fun safeTransaction() {
    try {
        Altude.send(options)
    } catch (e: BiometricNotAvailableException) {
        handleBiometricUnavailable(e)
    } catch (e: BiometricInvalidatedException) {
        handleBiometricInvalidated(e)
    } catch (e: VaultException) {
        // Catch all vault-specific errors
        showError(e.message ?: "Vault error", e.remediation)
    } catch (e: Exception) {
        // Unknown error
        Log.e("Vault", "Unknown error", e)
        showError("Transaction failed", "Please try again later")
    }
}
```

---

## Debugging

### Enable Verbose Logging

```kotlin
// In Application onCreate()
if (BuildConfig.DEBUG) {
    object : DebugTree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            super.log(priority, tag, message, t)
            // Log to file or analytics for debugging
        }
    }.apply { Timber.plant(this) }
}
```

### Vault State Inspection

```kotlin
lifecycleScope.launch {
    val appId = context.packageName
    println("Vault exists: ${VaultManager.vaultExists(context, appId)}")
    println("Vault unlocked: ${AltudeGasStation.isVaultUnlocked()}")
    
    val session = VaultManager.getSession()
    if (session != null) {
        println("Session remaining: ${session.remainingTimeMs()}ms")
    }
}
```

---

## Common Error Chains

### Init → BiometricNotAvailable → User Enrolls → Init Success

```
User installs app
   ↓
AltudeGasStation.init() called
   ↓
BiometricNotAvailableException thrown
   ↓
User opens Settings
   ↓
User enrolls fingerprint
   ↓
User returns to app
   ↓
AltudeGasStation.init() called again
   ↓
Success ✓
```

### Transaction → BiometricInvalidated → Clear Vault → New Key

```
User originally enrolled fingerprint A
   ↓
Vault created with fingerprint A gating
   ↓
User changes to fingerprint B (in Settings)
   ↓
Android Keystore invalidates vault key
   ↓
Next Altude.send() throws BiometricInvalidatedException
   ↓
App clears vault data
   ↓
AltudeGasStation.init() creates new vault with fingerprint B gating
   ↓
New key created ✓
```

---

## Support Resources

- **Code Examples:** See [Quick Start](VAULT_QUICKSTART.md) and [Advanced Guide](VAULT_ADVANCED.md)
- **API Reference:** Javadoc in source code
- **Sample App:** Check `app/` module for complete examples
- **Community:** GitHub Issues and Discussions

---

## Still Need Help?

1. **Check logs** - Search for "Vault" or exception name in Logcat
2. **Isolate** - Try with mock signer to rule out keystore issues
3. **Reinstall** - Clear app data and reinstall if vault is corrupted
4. **Report** - File an issue with:
   - Android version and device model
   - Exception type and full stack trace
   - Steps to reproduce
   - Expected vs. actual behavior
