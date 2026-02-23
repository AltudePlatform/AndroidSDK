# Vault Error Taxonomy & Developer Troubleshooting Guide

This document provides a comprehensive reference for understanding, handling, and debugging Vault errors in the Altude Android SDK.

## Quick Reference: Error Code Hierarchy

All Vault errors follow the format: `VAULT-XXXX` where the first two digits indicate the category:

| Code Range | Category | Examples |
|-----------|----------|----------|
| **01xx** | Initialization Errors | Vault setup, permissions, storage |
| **02xx** | Biometric/Authentication Errors | Enrollment, invalidation, failed auth |
| **03xx** | Storage/Encryption Errors | Corrupted data, decryption failure |
| **04xx** | Session/Runtime Errors | Vault locked, session expired |
| **05xx** | Configuration Errors | Invalid parameters, incompatible versions |

---

## Error Details & Remediation

### INITIALIZATION ERRORS (01xx)

#### VAULT-0101: Vault Initialization Failed

**What It Means:**
The app failed to create a new vault. This typically indicates a system-level issue preventing vault creation.

**Common Causes:**
1. **Insufficient Storage** - Device storage is full or near capacity
2. **Permission Denied** - App doesn't have write permission to file system
3. **Keystore Unavailable** - Android Keystore is inaccessible (rare)
4. **File System Error** - Disk corruption or SD card ejected
5. **Concurrency Issue** - Multiple init attempts simultaneously

**Error Code:** `VAULT-0101`

**User-Facing Errors:**
- "Failed to initialize vault"
- "Unable to create vault - storage error"
- "Permission denied - cannot create vault"

**Developer Remediation Steps:**

1. **Check available storage:**
   ```kotlin
   val runtime = Runtime.getRuntime()
   val freeMemory = runtime.freeMemory()
   if (freeMemory < 10 * 1024 * 1024) { // Less than 10MB
       Log.w("Vault", "Low device storage: ${freeMemory / 1024 / 1024}MB free")
   }
   ```

2. **Verify file system access:**
   ```kotlin
   val filesDir = context.filesDir
   if (!filesDir.canWrite()) {
       Log.e("Vault", "Cannot write to ${filesDir.absolutePath}")
       // Show user: "App doesn't have permission to store data"
   }
   ```

3. **Check app permissions:**
   ```kotlin
   // In AndroidManifest.xml:
   // <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
   ```

4. **Implement retryable initialization:**
   ```kotlin
   suspend fun initWithRetry(context: Context, apiKey: String, maxAttempts: Int = 3) {
       repeat(maxAttempts) { attempt ->
           try {
               AltudeGasStation.init(context, apiKey)
               return
           } catch (e: VaultInitFailedException) {
               if (attempt < maxAttempts - 1) {
                   Log.w("Vault", "Init attempt ${attempt + 1} failed, retrying...")
                   delay(1000 * (attempt + 1).toLong()) // Exponential backoff
               } else {
                   throw e
               }
           }
       }
   }
   ```

**User Actions:**
- Free up device storage: Settings > Storage > Delete cache/old files
- Restart device
- Uninstall and reinstall app

---

#### VAULT-0102: Initialization Permission Denied

**What It Means:**
The app doesn't have required permissions to create vault storage.

**Common Causes:**
1. `WRITE_EXTERNAL_STORAGE` not granted
2. Device policy restricts app storage
3. SD card mounted as read-only
4. User profile has restricted file access

**Developer Detection:**
```kotlin
try {
    AltudeGasStation.init(context, apiKey)
} catch (e: VaultInitFailedException) {
    if (e.errorCode == VaultErrorCodes.INIT_PERMISSION_DENIED) {
        // Handle permission-specific error
        showPermissionDialog()
    }
}
```

**Resolution:**
```kotlin
// Request runtime permission (Android 6.0+)
if (!hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
    ActivityCompat.requestPermissions(
        activity,
        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
        STORAGE_PERMISSION_REQUEST
    )
}
```

---

#### VAULT-0103: Insufficient Storage Space

**What It Means:**
Device doesn't have enough free space to create and store the vault.

**Common Causes:**
- Device storage < 50MB free
- User's internal partition nearly full
- Cache/temp files using significant space

**Minimum Space Required:**
- Seed storage: ~1KB
- Encrypted seed file: ~4KB
- Total + wiggle room: Recommend 10MB free

**Developer Check:**
```kotlin
fun getAvailableStorage(context: Context): Long {
    val stat = StatFs(context.filesDir.absolutePath)
    return stat.availableBlocksLong * stat.blockSizeLong
}

if (getAvailableStorage(context) < 10 * 1024 * 1024) {
    throw VaultInitFailedException(
        message = "Insufficient storage space",
        remediation = "Free at least 10MB and try again"
    )
}
```

---

#### VAULT-0104: Vault Already Initialized

**What It Means:**
You're trying to call `AltudeGasStation.init()` when a vault already exists for this app/wallet.

**Common Causes:**
1. Called init more than once
2. Race condition from concurrent init calls
3. Dev testing without clearing data between runs

**Why It's Prevented:**
Prevents accidental vault overwrite that would lose existing keys and create unrecoverable transactions.

**Code Pattern:**
```kotlin
// ❌ WRONG - Creates wallet twice!
fun onCreate() {
    AltudeGasStation.init(context, apiKey)
    AltudeGasStation.init(context, apiKey) // CRASHES HERE
}

// ✅ CORRECT - Idempotent initialization
fun onCreate() {
    if (!VaultManager.vaultExists(context, appId)) {
        AltudeGasStation.init(context, apiKey)
    }
}

// ✅ ALSO CORRECT - Initialize in Application class once
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AltudeGasStation.init(this, apiKey) // Called once for entire app
    }
}
```

**Recovery:**
```kotlin
try {
    AltudeGasStation.init(context, apiKey)
} catch (e: VaultAlreadyInitializedException) {
    Log.i("Vault", "Vault already exists, using existing")
    // Continue - existing vault will be used
}
```

---

### BIOMETRIC/AUTHENTICATION ERRORS (02xx)

#### VAULT-0201: Biometric Not Available

**What It Means:**
User hasn't set up any biometric or device credential on the device.

**Common Causes:**
1. No fingerprints enrolled
2. No face recognition enrolled
3. No device PIN/pattern/password set
4. Device lacks biometric hardware
5. Biometric disabled by device policy

**Risk Level:** 🔴 **CRITICAL** - Cannot proceed with Vault (no auth available)

**Error Pattern:**
```
BiometricNotAvailableException(code=VAULT-0201)
    message: "Biometric authentication is not available on this device"
    remediation: "Enroll fingerprint, face, or set PIN in device settings"
```

**User Experience Flow:**

```
[User opens app] 
    ↓
[AltudeGasStation.init() called]
    ↓
[Vault tries to initialize biometric]
    ↓
[BiometricNotAvailableException thrown]
    ↓
[Show user dialog]:
  "⚠️ Security Issue: No Biometric Setup"
  "This app requires fingerprint, face, or PIN authentication."
  [Cancel] [Open Settings]
    ↓
[User taps "Open Settings"]
    ↓
[Launch Settings > Security > Biometric]
    ↓
[User enrolls fingerprint/face or sets PIN]
    ↓
[User returns to app, retry init]
```

**Developer Implementation:**

```kotlin
// Handle in try-catch
try {
    AltudeGasStation.init(context, apiKey)
} catch (e: BiometricNotAvailableException) {
    // Show user dialog
    showDialog(
        title = "Setup Required",
        message = e.remediation,
        positiveButton = "Open Settings" to {
            // Launch biometric settings
            val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
            startActivity(intent)
        },
        negativeButton = "Cancel" to { /* Exit */ }
    )
}

// Or detect early:
if (!BiometricHandler.isBiometricAvailable(context)) {
    showBiometricSetupDialog()
}
```

**Prevention:**
```kotlin
// Check before showing app
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    if (!BiometricHandler.isBiometricAvailable(this)) {
        showUnavailableDialog()
        return // Don't continue
    }
    
    // Safe to init Vault
    AltudeGasStation.init(this, apiKey)
}
```

**What Users Need to Do:**
1. Open Settings app
2. Find Security or Biometric settings
3. Enroll fingerprint, face, or set PIN (exact steps vary by device)
4. Return to your app and retry

**Device Settings Paths (by Manufacturer):**
- **Samsung:** Settings > Biometrics > Fingerprint (or Face)
- **Google Pixel:** Settings > Security > Fingerprint
- **OnePlus:** Settings > Security & privacy > Fingerprint
- **Generic Android:** Settings > Lock screen > Advanced > Fingerprint

---

#### VAULT-0202: Biometric Invalidated

**What It Means:**
Previously enrolled biometric used to create the vault has been invalidated. Android automatically invalidates keys when biometric credentials change for security.

**Common Causes:**
1. **Primary Cause:** User enrolled NEW fingerprints (old key invalidated by OS)
2. **Primary Cause:** User changed Face recognition (old key invalidated by OS)
3. **Primary Cause:** User changed PIN/pattern/password (old key invalidated)
4. **Secondary:** Biometric sensor replaced/updated
5. **Rare:** Android security update affected key storage

**Why This Matters:**
This is a **security feature**, not a bug. Android invalidates keys when biometric credentials change to prevent:
- Unauthorized key usage if biometric is compromised
- Key recovery after device is stolen and new fingerprints enrolled
- Replay attacks with old authentication sessions

**Risk Level:** 🔴 **CRITICAL** - Existing vault unusable, requires re-init

**Error Pattern:**
```
BiometricInvalidatedException(code=VAULT-0202)
    message: "Biometric credentials have been invalidated (likely due to new enrollment)"
    remediation: "Clear App Data and reinitialize vault"
```

**User Experience Flow:**

```
[User changes fingerprint/face/PIN in device settings]
    ↓
[User opens app]
    ↓
[App tries to access vault with old key]
    ↓
[BiometricInvalidatedException thrown]
    ↓
[Show user dialog]:
  "🔐 Security Update Detected"
  "Your device security credentials changed. Your app vault needs to be recreated."
  [Clear & Restart] [Reinstall App]
    ↓
[User chooses option]
```

**Developer Implementation:**

The critical thing: **Don't try to recover the old vault.** Display clear options:

```kotlin
try {
    AltudeGasStation.init(context, apiKey)
} catch (e: BiometricInvalidatedException) {
    showBiometricInvalidatedDialog(
        title = "Security Update Required",
        message = "Your vault credentials need to be updated due to security changes.\n\n" +
                "This is expected if you recently changed your fingerprint, face, or PIN.",
        option1 = "Clear App Data" to {
            // Option 1: Clear data in code
            clearAppDataAndRestart()
        },
        option2 = "Reinstall App" to {
            // Option 2: Guide to reinstall
            showReinstallInstructions()
        }
    )
}

fun clearAppDataAndRestart() {
    val packageName = context.packageName
    val runtime = Runtime.getRuntime()
    runtime.exec("pm clear $packageName")
    // App will restart, user must init vault again
}
```

**Why Can't We Recover?**
The Android Keystore system prevents recovery of keys lost to invalidation:
1. Key is encrypted with old biometric credential
2. New biometric credential doesn't decrypt the old key
3. No way to bridge between old and new credentials
4. By design - prevents security bypass

**User Options:**

| Option | Impact | Time |
|--------|--------|------|
| **Clear App Data** | Loses vault, must recreate | 30 seconds |
| **Reinstall App** | Loses all app data, must setup | 2 minutes |
| **Restore Backup** | If you have private key backup | Depends |

**Best Practice for Sensitive Apps:**

For apps handling high-value transactions, implement key backup:

```kotlin
// ✅ After creating vault, export backup (encrypted)
suspend fun createVaultWithBackup(context: Context, apiKey: String) {
    try {
        AltudeGasStation.init(context, apiKey)
        
        // Optionally: Generate backup code user can store
        val backupCode = VaultManager.generateBackupCode(context, appId)
        showBackupCodeDialog(backupCode)
        
    } catch (e: VaultInitFailedException) {
        // Handle
    }
}

// ✅ If invalidated, offer restore
fun handleInvalidatedVault() {
    showDialog(
        title = "Restore from Backup?",
        message = "If you have a backup code, you can restore your wallet",
        positiveButton = "Enter Backup Code" to { 
            promptForBackupCode() // User enters code
        }
    )
}
```

---

#### VAULT-0203: Biometric Authentication Failed

**What It Means:**
User's biometric authentication failed (fingerprint/face not recognized) or user cancelled the prompt.

**Common Causes - Failed:**
1. Fingerprint doesn't match enrolled fingerprints
2. Face doesn't match enrolled face (lighting, angle, glasses)
3. Finger too wet, dry, or dirty
4. Face partially obscured or partially visible
5. Multiple failed attempts → locked out temporarily

**Common Causes - Cancelled:**
1. User tapped "Cancel" button
2. User pressed back button
3. App backgrounded during prompt
4. Timeout (biometric prompt expires)

**Risk Level:** 🟡 **MODERATE** - Transient, can retry

**Error Pattern:**
```
// Failed biometric
BiometricAuthenticationFailedException(
    failureReason = FailureReason.AuthenticationFailed,
    code = VAULT-0203
)

// User cancelled
BiometricAuthenticationFailedException(
    failureReason = FailureReason.UserCancelled,
    code = VAULT-0205
)

// Too many attempts
BiometricAuthenticationFailedException(
    failureReason = FailureReason.TooManyAttempts,
    code = VAULT-0204
)
```

**User Experience Flow (Typical Transaction):**

```
[User initiates transaction]
    ↓
[Biometric prompt shows: "Authenticate to sign transaction"]
    ↓
[User provides fingerprint/face]
    ↓
[❌ No match]
    ↓
[System shows: "Try again" + attempt counter]
    ↓
[User tries again]
    ↓
[✅ Match successful]
    ↓
[Transaction signs + proceeds]
```

**Developer Implementation:**

```kotlin
// In transaction flow
suspend fun signTransaction(context: Context, tx: Transaction): ByteArray {
    try {
        val signer = SdkConfig.currentSigner 
            ?: throw VaultLockedException("No signer configured")
        
        return signer.signMessage(tx.message)
        
    } catch (e: BiometricAuthenticationFailedException) {
        when (e.failureReason) {
            FailureReason.UserCancelled -> {
                // User cancelled - just notify, let them retry
                showMessage("Transaction cancelled. Try again when ready.")
                throw e
            }
            
            FailureReason.TooManyAttempts -> {
                // Locked out - show timeout message
                showDialog(
                    title = "Too Many Attempts",
                    message = "Biometric locked for 30 seconds. Please try again later."
                )
                throw e
            }
            
            FailureReason.AuthenticationFailed -> {
                // Failed - allow retry
                showMessage("Fingerprint/Face not recognized. Try again.")
                throw e
            }
            
            else -> throw e
        }
    }
}

// Recommendation: Allow retry with counters
var biometricFailureCount = 0
suspend fun signWithRetry(context: Context, tx: Transaction, maxRetries: Int = 3) {
    repeat(maxRetries) { attempt ->
        try {
            return signTransaction(context, tx)
        } catch (e: BiometricAuthenticationFailedException) {
            biometricFailureCount++
            
            if (attempt < maxRetries - 1) {
                // More attempts available
                if (e.failureReason == FailureReason.TooManyAttempts) {
                    delay(30_000) // Wait 30 seconds for lock
                }
                // Continue to next attempt
            } else {
                // No more attempts
                showDialog(
                    title = "Authentication Failed",
                    message = "Unable to authenticate after $maxRetries attempts.",
                    button = "OK"
                )
                throw e
            }
        }
    }
}
```

**Device Lockout Behavior:**
```
After 5 failed attempts on most devices:
  → Biometric locked for ~30 seconds
  → User can retry after lockout expires
  → After device reboots, counter resets

Exception thrown: BiometricAuthenticationFailedException(
    failureReason = FailureReason.TooManyAttempts,
    code = VAULT-0204
)
```

**User Tips:**
- Keep fingers clean and dry for fingerprint
- Ensure face is well-lit and visible for face recognition
- Don't rush - give sensor time to scan
- If device is wet, wait for it to dry
- If using glasses, ensure you're in same condition as enrollment

**Developer Best Practices:**

```kotlin
// ✅ DO: Allow retry attempts
// ✅ DO: Show helpful error messages
// ✅ DO: Track failure count
// ✅ DO: Provide fallback (device PIN)
// ❌ DON'T: Require app restart on failure
// ❌ DON'T: Show cryptic error codes to user
// ❌ DON'T: Lock user out permanently
```

---

#### VAULT-0204: Biometric Lockout

**What It Means:**
User exceeded maximum failed attempts, biometric is temporarily locked.

**Lock Duration:**
- Standard devices: 30 seconds
- Some devices with security policies: 1 minute
- After device reboot: Counter resets

**Developer Handling:**
```kotlin
catch (e: BiometricAuthenticationFailedException) {
    if (e.failureReason == FailureReason.TooManyAttempts) {
        // Wait and allow retry
        delay(30_000)
        showMessage("Lockout expired, try again")
    }
}
```

---

#### VAULT-0205: Biometric Cancelled

**What It Means:**
User explicitly cancelled the biometric prompt (tapped "Cancel" or pressed back).

**Common Causes:**
1. User changed mind about transaction
2. User accidentally tapped cancel
3. App backgrounded during prompt
4. Timeout expired

**Developer Handling:**
```kotlin
catch (e: BiometricAuthenticationFailedException) {
    if (e.failureReason == FailureReason.UserCancelled) {
        // Just notify, don't retry automatically
        showMessage("Authentication cancelled. Transaction not processed.")
    }
}
```

---

### STORAGE/ENCRYPTION ERRORS (03xx)

#### VAULT-0301: Decryption Failed

**What It Means:**
The app couldn't decrypt the stored seed. The encrypted data may be corrupted, or the encryption key was lost.

**Common Causes:**
1. **Most Common:** User upgraded app version that changed encryption format
2. Vault file corrupted by filesystem error
3. Android Keystore data corrupted
4. Device downgraded Android version
5. Partial app uninstall (files deleted, app data remains)

**Risk Level:** 🔴 **CRITICAL** - Vault inaccessible, data loss likely

**Error Pattern:**
```
VaultDecryptionFailedException(code=VAULT-0301)
    message: "Failed to decrypt vault data"
    remediation: "Vault data may be corrupted. Uninstall and reinstall..."
```

**Scenarios & Recovery:**

**Scenario 1: App Version Upgrade Mismatch**
```
Version 1.0: Uses AES-256-CBC
    ↓ [User upgrades]
Version 2.0: Uses AES-256-GCM
    ↓
[Try to decrypt v1.0 data with v2.0 algorithm]
    ↓
[FAILURE - format mismatch]
```

Solution: Implement version detection in VaultStorage:
```kotlin
fun decryptSeed(context: Context, appId: String): ByteArray {
    val vaultFile = File(context.filesDir, "vault_seed_$appId.encrypted")
    
    // Detect version from file header/metadata
    val (version, encryptedData) = readVersionedVaultFile(vaultFile)
    
    return when (version) {
        1 -> decryptV1(encryptedData)  // Old AES-256-CBC format
        2 -> decryptV2(encryptedData)  // New AES-256-GCM format
        else -> throw VaultDecryptionFailedException(
            message = "Unsupported vault version: $version",
            remediation = "Upgrade or downgrade app to compatible version"
        )
    }
}
```

**Scenario 2: Corrupted File**
```
[Filesystem corruption occurs]
    ↓
[Vault file bytes become invalid]
    ↓
[Checksum validation fails]
    ↓
[VaultDecryptionFailedException thrown]
```

**Scenario 3: Biometric Invalidation + File Persistence**
```
[User changes fingerprint]
    ↓
[Android invalidates Keystore key]
    ↓
[Vault file still exists but can't decrypt]
    ↓
[Try to decrypt with invalid key → FAILURE]
```

**Developer Detection & Recovery:**

```kotlin
// Method 1: Try to decrypt on startup
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    try {
        // Test vault accessibility
        testVaultDecryption()
    } catch (e: VaultDecryptionFailedException) {
        // Vault corrupted - show user options
        showVaultRecoveryDialog(e)
    }
}

fun testVaultDecryption() {
    val seed = VaultManager.retrievSeed(context, appId) // Will throw if corrupted
}

fun showVaultRecoveryDialog(exception: VaultDecryptionFailedException) {
    showDialog(
        title = "Vault Corrupted",
        message = exception.remediation + "\n\nChoose an option below:",
        buttons = listOf(
            "Clear & Restart" to { clearAndRestart() },
            "Restore from Backup" to { restoreFromBackup() },
            "Reinstall App" to { showReinstallSteps() }
        )
    )
}

fun clearAndRestart() {
    // Delete corrupted vault file
    File(context.filesDir, "vault_seed_$appId.encrypted").delete()
    
    // Restart app to trigger re-init
    val intent = Intent(context, MainActivity::class.java)
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    startActivity(intent)
    finish()
}
```

---

#### VAULT-0302: Encryption Failed

**What It Means:**
The app couldn't encrypt the seed when trying to store it.

**Common Causes:**
1. Keystore inaccessible
2. Insufficient memory
3. File write permission denied
4. Device running out of space mid-encryption

**Risk Level:** 🟡 **MODERATE** - Init fails, can retry

**Developer Handling:**
```kotlin
try {
    AltudeGasStation.init(context, apiKey)
} catch (e: VaultInitFailedException) {
    if (e.message?.contains("Encryption failed") == true) {
        // Try again after brief delay
        delay(1000)
        AltudeGasStation.init(context, apiKey)
    }
}
```

---

#### VAULT-0303: Storage Corrupted

**What It Means:**
Vault metadata or structure is corrupted.

**Common Causes:**
1. Partial file deletion
2. Database corruption
3. Concurrent access corruption

**Developer Cleanup:**
```kotlin
fun cleanupCorruptedVault(context: Context, appId: String) {
    File(context.filesDir, "vault_seed_$appId.encrypted").delete()
    File(context.filesDir, "vault_metadata_$appId").delete()
    
    // Safe to re-initialize
    AltudeGasStation.init(context, apiKey)
}
```

---

#### VAULT-0304: Keystore Error

**What It Means:**
Android Keystore is unavailable or corrupted.

**Common Causes:**
1. Device memory full
2. Keystore database corruption
3. Rare: Device boot state incomplete

**Developer Mitigation:**
```kotlin
fun initializeKeystore(): Boolean {
    return try {
        val key = KeyGenParameterSpec.Builder(
            VAULT_KEY_ALIAS,
            KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT
        ).build()
        
        KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        ).init(key)
        
        true
    } catch (e: Exception) {
        Log.e("Vault", "Keystore error", e)
        false
    }
}

if (!initializeKeystore()) {
    throw VaultInitFailedException(
        message = "Cannot access Android Keystore",
        remediation = "Restart device and try again"
    )
}
```

---

### SESSION/RUNTIME ERRORS (04xx)

#### VAULT-0401: Vault Locked

**What It Means:**
Vault is not unlocked. You can't sign transactions. Need to authenticate with biometric.

**When This Occurs:**
1. Immediately at app start (before first unlock)
2. After manual lock
3. App backgrounded and session cleared
4. Between transactions in per-operation mode

**Not an Error in Per-Operation Mode:**
In per-operation mode (default), this is expected - each transaction triggers unlock:

```
[User initiates transaction]
    ↓
[Vault locked → Unlock attempt]
    ↓
[Biometric prompt]
    ↓
[✅ Authenticated → Transaction completes]
    ↓
[Vault locked again immediately after]
```

**Developer Handling:**

```kotlin
// ❌ WRONG - Doesn't handle locked vault
suspend fun signTransaction(tx: Transaction): ByteArray {
    val signer = SdkConfig.currentSigner 
        ?: throw VaultLockedException()
    return signer.signMessage(tx.message)
}

// ✅ CORRECT - Handles and recovers from lock
suspend fun signTransaction(tx: Transaction): ByteArray {
    return try {
        val signer = SdkConfig.currentSigner 
            ?: throw VaultLockedException("No signer configured")
        signer.signMessage(tx.message)
    } catch (e: VaultLockedException) {
        // Expected in per-operation mode
        // Signer will re-prompt for biometric automatically
        // Just propagate or retry
        throw e
    }
}
```

**For Session-Based Mode:**
```kotlin
// Session-based signing (45-second cached auth)
val signer = VaultSigner.createWithSession(
    context, 
    appId, 
    sessionTTLSeconds = 45
)

// First call: Prompts biometric
signer.signMessage(tx1)

// Calls 2-45: No prompt (session valid)
signer.signMessage(tx2)
signer.signMessage(tx3)

// Call at 46 seconds: Prompts again (session expired)
signer.signMessage(tx4)
```

---

#### VAULT-0402: Session Expired

**What It Means:**
Session-based authentication session has expired. Must re-authenticate.

**When This Occurs:**
- In session-based mode: When sessionTTLSeconds has elapsed
- **Not thrown in per-operation mode** (which locks immediately)

**Example Timeline:**
```
00:00  → First transaction: Biometric prompt shown
00:05  → Second transaction: No prompt (session active)
00:22  → Third transaction: No prompt (session active)
00:45  → EXPIRED: Session TTL (45s) exceeded
00:46  → Fourth transaction: Biometric prompt shown again
```

**Developer Handling:**

```kotlin
// Build transaction batch with session-based signing
suspend fun batchSignTransactions(
    context: Context,
    transactions: List<Transaction>
): List<ByteArray> {
    val signer = VaultSigner.createWithSession(context, appId, 45)
    
    return transactions.mapIndexed { idx, tx ->
        try {
            val signature = signer.signMessage(tx.message)
            Log.i("Vault", "Signed tx ${idx + 1}/${transactions.size}")
            signature
        } catch (e: VaultLockedException) {
            // Session expired - automatic re-prompt happens
            Log.i("Vault", "Session expired, re-authenticating...")
            signer.signMessage(tx.message) // Will show biometric again
        }
    }
}
```

---

#### VAULT-0403: Invalid Context

**What It Means:**
The Context passed to Vault is invalid or incompatible.

**Common Causes:**
1. Passing base Context instead of FragmentActivity
2. Context leaked from old Activity
3. Context from different app/process
4. Context is null/disposed

**Developer Prevention:**

```kotlin
// ❌ WRONG - Base Context doesn't have FragmentManager
val baseContext: Context = this
VaultSigner.create(baseContext, appId)

// ✅ CORRECT - Use Activity with FragmentManager
val activity: FragmentActivity = this
VaultSigner.create(activity, appId)

// ✅ CORRECT - In Fragment
val activity: FragmentActivity = requireActivity()
VaultSigner.create(activity, appId)
```

**Type Safety Pattern:**
```kotlin
fun createVaultSigner(context: Any): VaultSigner {
    if (context !is FragmentActivity) {
        throw VaultConfigurationException(
            message = "Context must be FragmentActivity, got ${context.javaClass.simpleName}",
            remediation = "Pass 'this' if in Activity, or 'requireActivity()' if in Fragment"
        )
    }
    return VaultSigner.create(context, appId)
}
```

---

### CONFIGURATION ERRORS (05xx)

#### VAULT-0501: Invalid Configuration

**What It Means:**
Vault configuration parameters are invalid.

**Common Invalid Values:**

```
❌ sessionTTLSeconds = -1      (negative)
❌ sessionTTLSeconds = 0       (reserved for per-operation)
✅ sessionTTLSeconds = 45      (valid, 45 seconds)

❌ walletIndex = -1            (negative)
✅ walletIndex = 0             (first wallet)
✅ walletIndex = 1             (second wallet)

❌ appId = ""                  (empty)
✅ appId = "com.example.app"  (valid identifier)

❌ context = Context           (base Context)
✅ context = FragmentActivity  (correct type)
```

**Developer Validation:**

```kotlin
fun validateVaultConfig(
    sessionTTLSeconds: Int?,
    walletIndex: Int,
    appId: String,
    context: Any
): Boolean {
    require(sessionTTLSeconds == null || sessionTTLSeconds >= 0) {
        "sessionTTLSeconds must be >= 0, got $sessionTTLSeconds"
    }
    
    require(walletIndex >= 0) {
        "walletIndex must be >= 0, got $walletIndex"
    }
    
    require(appId.isNotEmpty()) {
        "appId cannot be empty"
    }
    
    require(context is FragmentActivity) {
        "context must be FragmentActivity"
    }
    
    return true
}
```

---

## Error Handling Strategy

### Option 1: Per-Operation (Recommended Default)

Each transaction triggers biometric auth:

```kotlin
// Setup
val signer = VaultSigner.create(context, appId)

// Transaction 1: Prompts
signer.signMessage(tx1)

// Transaction 2: Prompts again
signer.signMessage(tx2)

// Transaction 3: Prompts again
signer.signMessage(tx3)
```

**Pros:**
- Most secure (fresh auth every time)
- No session expiry concerns
- Appropriate error handling is straightforward

**Error Handling:**
```kotlin
try {
    signer.signMessage(message)
} catch (e: BiometricAuthenticationFailedException) {
    if (e.failureReason == FailureReason.UserCancelled) {
        // User cancelled, ask if they want to retry
        showMessage("Try again when ready")
    }
    // Otherwise: retry automatically or show error
}
```

---

### Option 2: Session-Based (Advanced)

Cache auth result for configurable TTL:

```kotlin
// Setup with 45-second session
val signer = VaultSigner.createWithSession(context, appId, 45)

// Transaction 1: Prompts
signer.signMessage(tx1)

// Transactions 2-N (within 45s): No prompts
repeat(10) {
    signer.signMessage(txN)
}

// After 45 seconds: Prompts again
signer.signMessage(tx_after_ttl)
```

**Pros:**
- Better UX for batch operations
- No repeated prompts for bulk transactions
- Reduced auth friction

**Cons:**
- Must handle session expiry
- More complex error states

---

## Decision Trees

### Initialization Flow

```
[Call AltudeGasStation.init()]
    ↓
[Check is context FragmentActivity?]
├─ NO → VaultConfigurationException (0501)
└─ YES → Continue
    ↓
[Check device storage]
├─ < 10MB → VaultInitFailedException (0103)
└─ OK → Continue
    ↓
[Check write permissions]
├─ Denied → VaultInitFailedException (0102)
└─ OK → Continue
    ↓
[Check biometric availability]
├─ Not set → BiometricNotAvailableException (0201)
└─ OK → Continue
    ↓
[Generate + encrypt seed]
├─ Encryption failed → VaultInitFailedException (0101)
└─ SUCCESS → Vault ready
```

### Transaction Signing Flow

```
[Call signer.signMessage()]
    ↓
[Check vault initialized]
├─ NO → VaultLockedException (0401)
└─ YES → Continue
    ↓
[Per-operation mode?]
├─ YES → Always unlock
│   ├─ Biometric prompt shown
│   ├─ User cancelled → BiometricAuthenticationFailedException (0205)
│   ├─ Auth failed → BiometricAuthenticationFailedException (0203)
│   └─ SUCCESS → Sign + lock immediately
│
└─ NO (Session mode) → Check session
    ├─ Valid → Sign immediately (no prompt)
    ├─ Expired → Unlock
    │   ├─ Biometric prompt shown
    │   └─ ... (same as per-operation)
    └─ Invalidated → BiometricInvalidatedException (0202)
```

---

## Testing Error Scenarios

### Unit Tests

```kotlin
class VaultExceptionTest {
    
    @Test
    fun testBiometricNotAvailableException() {
        // Given: Biometric not available on device
        assertThat(BiometricHandler.isBiometricAvailable(context)).isFalse()
        
        // When: Init vault
        assertThrows<BiometricNotAvailableException> {
            AltudeGasStation.init(context, apiKey)
        }
        
        // Then: Error code is correct
        // assertTrue(e.errorCode == VaultErrorCodes.BIOMETRIC_UNAVAILABLE)
    }
    
    @Test
    fun testVaultAlreadyInitializedException() {
        // Given: Vault already initialized
        AltudeGasStation.init(context, apiKey)
        
        // When: Init again
        // Then: Exception thrown
        assertThrows<VaultAlreadyInitializedException> {
            AltudeGasStation.init(context, apiKey)
        }
    }
    
    @Test
    fun testInvalidConfiguration() {
        // Given: Invalid sessionTTLSeconds
        assertThrows<VaultConfigurationException> {
            VaultSigner.createWithSession(context, appId, -1)
        }
    }
}
```

### Manual Testing Scenarios

**Test 1: No Biometric Enrolled**
- Device: Reset fingerprints/face
- Action: Launch app, call init
- Expected: BiometricNotAvailableException

**Test 2: Biometric Invalidation**
- Device: Enroll fingerprint initially
- Action: Init vault successfully
- Device: Add new fingerprint to device
- Action: Try to sign transaction
- Expected: BiometricInvalidatedException

**Test 3: Authentication Failure**
- Action: Show biometric prompt
- Action: Provide wrong fingerprint 5 times
- Expected: BiometricAuthenticationFailedException with TooManyAttempts

**Test 4: Storage Full**
- Action: Fill device storage to < 10MB
- Action: Call init
- Expected: VaultInitFailedException (0103)

---

## FAQ & Common Issues

### Q: User sees "Biometric Not Available" but has fingerprint set up

**A:** Check:
1. Device Settings → Security → Fingerprint → Verify fingerprint(s) enrolled
2. Device Settings → Biometrics enabled globally
3. App might be running on emulator without biometric support
4. Samsung devices: Check if biometric is disabled in Settings > Biometrics

### Q: Session expires mid-transaction batch

**A:** This is expected in session mode when TTL expires between transactions.

**Solutions:**
1. Increase sessionTTLSeconds (e.g., 120 instead of 45)
2. Use per-operation mode (default, always re-prompts)
3. Wrap in try-catch and retry on expiration

### Q: "Vault Already Initialized" error on app restart

**A:** Happens if:
1. Vault was created in first launch
2. You're calling init() again on app restart

**Fix:**
```kotlin
// ✅ Initialize once in Application.onCreate()
class MyApp : Application() {
    override fun onCreate() {
        if (!VaultManager.vaultExists(this, appId)) {
            AltudeGasStation.init(this, apiKey)
        }
    }
}

// ✅ Or in MainActivity without checking
override fun onCreate(savedInstanceState: Bundle?) {
    try {
        AltudeGasStation.init(this, apiKey)
    } catch (e: VaultAlreadyInitializedException) {
        // Already initialized, continue
    }
}
```

### Q: Decryption failed after app update

**A:** Possible version mismatch in encryption format.

**Fix:**
1. Implement version detection in VaultStorage
2. Support multiple decryption algorithms
3. Or: Clear app data and reinitialize

### Q: Biometric prompt never appears

**A:** Verify:
1. BiometricPrompt library imported correctly
2. Activity has FragmentManager (must be FragmentActivity)
3. Biometric available on device
4. Not calling from background thread (must be Main thread)

---

## Error Codes Reference Table

| Code | Category | Name | Cause | Retry | Fix |
|------|----------|------|-------|-------|-----|
| 0101 | Init | Init Failed | Storage/permission issue | YES | Free space, check permissions |
| 0102 | Init | Permission Denied | No WRITE permission | YES | Grant permission |
| 0103 | Init | Insufficient Storage | < 10MB free | YES | Free space |
| 0104 | Init | Already Initialized | Init called twice | NO | Don't call init again |
| 0201 | Bio | Unavailable | No biometric set up | NO | Enroll fingerprint/face |
| 0202 | Bio | Invalidated | Biometric changed | NO | Clear data or reinstall |
| 0203 | Bio | Auth Failed | Wrong fingerprint/face | YES | Try again |
| 0204 | Bio | Lockout | 5 failed attempts | WAIT | Wait 30s then retry |
| 0205 | Bio | Cancelled | User cancelled | YES | Retry when ready |
| 0301 | Storage | Decryption Failed | Corrupted data | NO | Clear data |
| 0302 | Storage | Encryption Failed | Keystore issue | YES | Restart device |
| 0303 | Storage | Corrupted | Partial deletion | NO | Clear data |
| 0304 | Storage | Keystore Error | Keystore unavailable | YES | Restart device |
| 0401 | Runtime | Locked | Not unlocked | AUTO | Automatic unlock on sign |
| 0402 | Runtime | Session Expired | TTL exceeded | AUTO | Auto re-unlocks |
| 0403 | Runtime | Invalid Context | Wrong context type | NO | Pass FragmentActivity |
| 0501 | Config | Invalid Config | Bad parameters | NO | Verify parameters |

---

## Related Documentation

- [VAULT_QUICKSTART.md](VAULT_QUICKSTART.md) - Getting started
- [VAULT_ADVANCED.md](VAULT_ADVANCED.md) - Advanced usage
- [BIOMETRIC_UX_GUIDE.md](BIOMETRIC_UX_GUIDE.md) - Biometric integration details
- [INIT_SIGNER_API.md](INIT_SIGNER_API.md) - API design and factories

