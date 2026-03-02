# Vault Error Codes Quick Reference

**Quick lookup table for all Vault error codes and common solutions.**

## Error Codes by Category

### 01xx - Initialization Errors

| Code | Name | Issue | Quick Fix |
|------|------|-------|----------|
| **0101** | Init Failed | Vault creation failed | Free storage, check permissions |
| **0102** | Permission Denied | No write permission | Grant WRITE_EXTERNAL_STORAGE |
| **0103** | Insufficient Storage | < 10MB free | Delete cache/files to free space |
| **0104** | Already Initialized | Init called twice | Don't call init() again |

### 02xx - Biometric/Authentication Errors

| Code | Name | Issue | Quick Fix |
|------|------|-------|----------|
| **0201** | Biometric Unavailable | No fingerprint/face/PIN | Enroll in device Settings |
| **0202** | Biometric Invalidated | Changed fingerprint/face/PIN | Clear app data & reinstall |
| **0203** | Auth Failed | Wrong fingerprint/face | Try again |
| **0204** | Lockout | 5 failed attempts | Wait 30 seconds |
| **0205** | Cancelled | User cancelled prompt | Retry when ready |

### 03xx - Storage/Encryption Errors

| Code | Name | Issue | Quick Fix |
|------|------|-------|----------|
| **0301** | Decryption Failed | Vault data corrupted | Clear app data |
| **0302** | Encryption Failed | Keystore error | Restart device |
| **0303** | Storage Corrupted | Partial deletion | Reinstall app |
| **0304** | Keystore Error | Keystore unavailable | Restart device |

### 04xx - Session/Runtime Errors

| Code | Name | Issue | Quick Fix |
|------|------|-------|----------|
| **0401** | Vault Locked | Vault not unlocked | Call init(), or re-authenticate |
| **0402** | Session Expired | TTL exceeded (session mode) | Automatic re-prompt on next operation |
| **0403** | Invalid Context | Wrong Context type | Pass FragmentActivity not Context |

### 05xx - Configuration Errors

| Code | Name | Issue | Quick Fix |
|------|------|-------|----------|
| **0501** | Invalid Config | Bad parameters | Check sessionTTLSeconds, walletIndex, appId |

---

## Error Lookup by Symptom

### User Can't Start App

- **No fingerprint/face?** → 0201 (Enroll biometric)
- **Device storage full?** → 0103 (Free space)
- **Permission denied?** → 0102 (Grant permission)
- **Crashed during init?** → 0101 (Check logs, retry)

### Transaction Won't Sign

- **"Failed to authenticate"** → 0203 (Try valid fingerprint)
- **Locked after 5 attempts** → 0204 (Wait 30s)
- **User cancelled** → 0205 (Tap retry)
- **Vault not initialized** → 0401 (Call init)

### Can't Use Updated Biometric

- **Changed fingerprint/face?** → 0202 (Reinstall app)
- **Added new fingerprint?** → 0202 (Reinstall app)
- **Changed device PIN?** → 0202 (Reinstall app)

### Data Loss Issues

- **Vault corrupted** → 0301 (Uninstall, reinstall)
- **Partial data deleted** → 0303 (Reinstall)
- **Can't read vault** → 0301 (Clear app data)

---

## Code Error Handling Template

### Minimal Error Handling

```kotlin
try {
    AltudeGasStation.init(context, apiKey)
} catch (e: VaultException) {
    Log.e("Vault", "[${e.errorCode}] ${e.message}", e)
    showError("${e.message}\n\n${e.remediation}")
}
```

### Comprehensive Error Handling

```kotlin
try {
    // Operation here
} catch (e: BiometricNotAvailableException) {
    // User needs fingerprint/face/PIN
    showDialog("Set up Biometric", e.remediation)
} catch (e: BiometricInvalidatedException) {
    // Vault corrupted - no recovery
    clearAppData()
} catch (e: BiometricAuthenticationFailedException) {
    // Auth failed - can retry
    when (e.failureReason) {
        FailureReason.UserCancelled -> showMessage("Try again")
        FailureReason.TooManyAttempts -> showMessage("Wait 30s")
        else -> showMessage("Authentication failed")
    }
} catch (e: VaultException) {
    // Other vault errors
    Log.e("Vault", "[${e.errorCode}] ${e.message}")
    showError(e.message ?: "Unknown error", e.remediation)
}
```

---

## Error Rates & Diagnostics

### Measure Error Frequency

```kotlin
val errorStats = VaultErrorTracker.getErrorStats()
errorStats.errorsByCode.forEach { (code, count) ->
    Log.i("Stats", "$code: $count occurrences")
}
```

### Debug Problematic Error Code

```kotlin
fun debugErrorCode(code: String) {
    when (code) {
        "VAULT-0201" -> checkBiometricSettings()
        "VAULT-0202" -> checkBiometricChanges()
        "VAULT-0301" -> checkVaultFileIntegrity()
        "VAULT-0401" -> checkVaultInitialization()
    }
}
```

---

## Prevention Checklist

- [ ] Call `AltudeGasStation.init()` only once (in Application.onCreate or MainActivity.onCreate)
- [ ] Pass `FragmentActivity`, not base `Context`
- [ ] Check device storage before init: `getAvailableStorageMB(context) > 50`
- [ ] Check biometric available: `BiometricHandler.isBiometricAvailable(context)`
- [ ] Use try-catch around all signing operations
- [ ] Log all errors with error code for debugging
- [ ] Test on devices with/without biometric
- [ ] Test low-storage scenario (< 100MB free)
- [ ] Test after changing device fingerprint/face

---

## Related Docs

- 📖 [ERROR_TAXONOMY_AND_TROUBLESHOOTING.md](ERROR_TAXONOMY_AND_TROUBLESHOOTING.md) - Full details for each error
- 📖 [ERROR_HANDLING_PATTERNS.md](ERROR_HANDLING_PATTERNS.md) - Code samples and recipes
- 📖 [VAULT_QUICKSTART.md](VAULT_QUICKSTART.md) - Getting started
- 📖 [VAULT_ADVANCED.md](VAULT_ADVANCED.md) - Advanced patterns
- 📖 [BIOMETRIC_UX_GUIDE.md](BIOMETRIC_UX_GUIDE.md) - UI/UX details

---

## Support

### For Users

**Is your error code in the table above?**
→ Follow the "Quick Fix" column

**Don't see your code?**
→ Report with logs to support@altude.com

### For Developers

**Implementing error handling?**
→ See [ERROR_HANDLING_PATTERNS.md](ERROR_HANDLING_PATTERNS.md)

**Understanding specific error?**
→ See [ERROR_TAXONOMY_AND_TROUBLESHOOTING.md](ERROR_TAXONOMY_AND_TROUBLESHOOTING.md)

**Questions on biometric auth?**
→ See [BIOMETRIC_UX_GUIDE.md](BIOMETRIC_UX_GUIDE.md)

