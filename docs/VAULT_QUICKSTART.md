# Vault Integration Quick Start Guide

## Overview

The Altude Vault provides **invisible key storage with biometric authentication** out of the box. Initialize once, then use the signer for all transactions—biometric prompts appear automatically when needed.

**Key Benefits:**
- 🔐 Keys never leave the device
- 👆 Biometric authentication on every transaction (or once per 45s session)
- 🚫 No plaintext keys at rest
- 🔄 Configurable session TTL (0 = per-operation, 45s = default session)
- 💾 Deterministic key derivation (same seed = same key every time)

---

## 1. Initialize (One Line)

The simplest way to get started:

```kotlin
// In your Application class onCreate() or early in MainActivity
import com.altude.gasstation.AltudeGasStation

AltudeGasStation.init(context, apiKey = "ak_xECEd2kxw8siDNxUXAhfGIJf_YJ7nUrZx-fAHXg9NJk")
```

**What happened:**
- ✅ Vault initialized with default settings
- ✅ Biometric-encrypted key generated automatically  
- ✅ No plaintext keys stored anywhere
- ✅ Ready to sign transactions

That's it! Vault is now initialized and ready. The first transaction will prompt for biometric/device credential.

---

## 2. Sign a Transaction

```kotlin
import com.altude.core.config.SdkConfig
import kotlinx.coroutines.launch

class MyActivity : AppCompatActivity() {
    fun signTransaction() {
        lifecycleScope.launch {
            try {
                // Get the Vault signer
                val signer = SdkConfig.getInstance().currentSigner
                
                // Create a transaction
                val transaction = /* your Solana transaction */
                
                // Sign it (biometric prompt appears HERE)
                val signature = signer.signMessage(transaction.message)
                
                // Send transaction to blockchain
                sendToBlockchain(signature)
                
            } catch (e: BiometricNotAvailableException) {
                showMessage("Please set up biometric on your device first")
            } catch (e: BiometricAuthenticationFailedException) {
                showMessage("Biometric authentication failed. Try again.")
            } catch (e: VaultException) {
                showMessage("Error: ${e.message}")
            }
        }
    }
}
```

**Key points:**
- Biometric prompt appears **automatically** on signature
- Returns signature directly—no additional steps needed
- Error handling with specific exception types

---

## 3. Handle Common Errors

### Error: "Biometric Not Available"
**Cause:** Device has no fingerprint/face/PIN enrollment

**Recovery:**
```kotlin
catch (e: BiometricNotAvailableException) {
    AlertDialog.Builder(this)
        .setTitle("Set Up Biometric")
        .setMessage("Please set up fingerprint, face, or PIN on your device.\n\n" +
                "Settings > Security > Fingerprint / Biometric")
        .setPositiveButton("Open Settings") { _, _ ->
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
        .show()
}
```

### Error: "Biometric Invalidated"  
**Cause:** User changed fingerprints or face (security feature—keys unrecoverable)

**Recovery:**
```kotlin
catch (e: BiometricInvalidatedException) {
    AlertDialog.Builder(this)
        .setTitle("Security Update Required")
        .setMessage("Your biometric has changed. Vault keys cannot be recovered.\n\n" +
                "Clear app data to start fresh:\n" +
                "Settings > Apps > [App] > Storage > Clear Data")
        .setPositiveButton("OK", null)
        .show()
}
```

### Error: "Authentication Failed"
**Cause:** Wrong fingerprint/face or user cancelled

**Recovery:**
```kotlin
catch (e: BiometricAuthenticationFailedException) {
    when (e.failureReason) {
        BiometricAuthenticationFailedException.FailureReason.UserCancelled ->
            showMessage("Transaction cancelled.")
        BiometricAuthenticationFailedException.FailureReason.TooManyAttempts ->
            showMessage("Too many attempts. Wait 30 seconds, then try again.")
        else ->
            showMessage("Authentication failed. Try again.\n" +
                    "Tips: Keep fingers clean, ensure good lighting.")
    }
}
```

---

## 4. Batch Operations

Sign multiple transactions with **fewer or same biometric prompts**:

```kotlin
lifecycleScope.launch {
    try {
        val signer = SdkConfig.getInstance().currentSigner
        
        // Create multiple transactions
        val transfers = listOf(
            createTransfer(wallet1, 0.5),
            createTransfer(wallet2, 1.0),
            createTransfer(wallet3, 0.25)
        )
        
        progressBar.visibility = View.VISIBLE
        transfers.forEachIndexed { index, tx ->
            progressBar.progress = (index + 1) * 100 / transfers.size
            val signature = signer.signMessage(tx.message)
            // Send to blockchain...
        }
        
        progressBar.visibility = View.GONE
        showMessage("✅ All 3 transfers complete!")
        
    } catch (e: VaultException) {
        showMessage("Batch failed: ${e.message}")
    }
}
```

---

## 5. Advanced: Custom Signer

For hardware wallets or other custom signers, skip Vault entirely:

```kotlin
// Create your custom signer
class MyHotWalletSigner(private val privateKey: ByteArray) : TransactionSigner {
    override val publicKey: PublicKey = /* derived from key */
    override suspend fun signMessage(message: ByteArray): ByteArray = 
        sign(message, privateKey)
}

// Initialize with it
val customSigner = MyHotWalletSigner(myKey)
val options = InitOptions.custom(customSigner)
AltudeGasStation.init(this, apiKey, options)

// Signing works the same—no biometric
val signer = SdkConfig.getInstance().currentSigner
val signature = signer.signMessage(tx.message)
```

See [ExternalSignerExampleActivity.kt](../app/src/main/java/com/altude/android/ExternalSignerExampleActivity.kt) for a complete working example.

---

## 6. Advanced: Session-Based Mode

By default, **every transaction requires biometric** (most secure). For better UX, use session-based mode to reuse authentication:

```kotlin
// Authenticate once, reuse key for 45 seconds
val options = InitOptions.vault(
    sessionTTLSeconds = 45,  
    authMode = VaultAuthMode.SessionBased
)
AltudeGasStation.init(context, apiKey, options)

// Multiple signings within 45s: only 1st prompts
val sig1 = signer.signMessage(tx1)  // Biometric prompt
val sig2 = signer.signMessage(tx2)  // No prompt (session valid)
val sig3 = signer.signMessage(tx3)  // No prompt (session valid)
// After 45s: next signer.signMessage() prompts again
```

**Trade-offs:**
- **Per-Operation (default)**: Most secure—biometric every time
- **SessionBased**: Convenient—biometric once per 45s

---

## 7. Complete Example Activities

### Default Vault (Biometric per Transaction)
See [VaultExampleActivity.kt](../app/src/main/java/com/altude/android/VaultExampleActivity.kt) for a complete working example showing:
- ✅ One-liner initialization
- ✅ Per-operation signing with biometric prompts
- ✅ Batch operations
- ✅ Error handling and recovery

### Error Recovery Patterns
See [ErrorHandlingExampleActivity.kt](../app/src/main/java/com/altude/android/ErrorHandlingExampleActivity.kt) for complete examples of:
- ✅ Biometric Not Available (guide to settings)
- ✅ Biometric Invalidated (data loss recovery)
- ✅ Authentication Failed (retry with tips)
- ✅ Vault Locked (reinitialization)
- ✅ Session Expired (automatic re-prompt)
- ✅ Storage Errors (free space recovery)

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| "Vault not initialized" | Call `AltudeGasStation.init()` in Application.onCreate() or early in Activity |
| Biometric prompt doesn't appear | Check targetSdkVersion >= 30 and device has biometric enrolled |
| Keys lost after changing fingerprints | This is by design—biometric invalidation prevents unauthorized access |
| Custom signer used instead of Vault | Check that you're NOT passing `InitOptions.custom()` |
| Session expires too quickly | Increase `sessionTTLSeconds` (default is 45) or use PerOperation mode |
| Batch operations slow (repeated prompts) | Use `sessionTTLSeconds > 0` or `VaultAuthMode.SessionBased` |

---

## API Reference Summary

### One-Liner Init (Default - Most Secure)
```kotlin
AltudeGasStation.init(context, apiKey)
// Uses Vault + per-operation biometric
```

### With Custom Options
```kotlin
val options = InitOptions.vault(
    sessionTTLSeconds = 45,      // 0 = per-operation (default), N > 0 = session mode
    authMode = VaultAuthMode.PerOperation  // or SessionBased
)
AltudeGasStation.init(context, apiKey, options)
```

### Custom Signer
```kotlin
val customSigner = MyTransactionSigner()
val options = InitOptions.custom(customSigner)
AltudeGasStation.init(context, apiKey, options)
```

### Get Signer & Sign Transaction
```kotlin
val signer = SdkConfig.getInstance().currentSigner
val signature = signer.signMessage(message)
```

---

## Error Codes Reference

| Code | Meaning | Severity |
|------|---------|----------|
| VAULT-0101 | Seed generation failed | Critical |
| VAULT-0102 | Permission/storage error | Critical |
| VAULT-0201 | Biometric not available | High |
| VAULT-0202 | Biometric invalidated | Critical |
| VAULT-0203 | Authentication failed | Medium |
| VAULT-0401 | Vault not initialized | Critical |
| VAULT-0402 | Session expired | Low |

**Full reference:** [ERROR_CODES_QUICK_REFERENCE.md](ERROR_CODES_QUICK_REFERENCE.md)

---

## Security Architecture

### Key Storage
- **Root Seed**: Generated cryptographically secure (SecureRandom), encrypted with Android Keystore AES-256-GCM
- **At Rest**: Never plaintext—always encrypted
- **Key Derivation**: HKDF-SHA256 with domain separation (`"altude:vault:solana::<appId>:<walletIndex>"`)

### Authentication
- **Biometric Gate**: Every transaction requires fingerprint, face, or PIN authentication (unless in valid session TTL)
- **Session Caching**: Optional 45-second session (default) or per-operation only
- **No Fallback**: If biometric fails, transaction fails—no silent fallback to plaintext

### Signing
- **Algorithm**: Ed25519 with Solana SDK (`solana.web3` library)
- **Address**: Public key derived deterministically from seed
- **Signature**: Computed in-memory, never cached

### Session Management
- **In-Memory Only**: Session key held in RAM, cleared on app background
- **TTL-Based**: Configurable timeout (0 = per-operation, 45 = default)
- **Automatic Expiration**: Session expires on timeout OR on app background

---

## Next Steps

1. **Copy & run sample:** [VaultExampleActivity.kt](../app/src/main/java/com/altude/android/VaultExampleActivity.kt)
2. **Learn error handling:** [ErrorHandlingExampleActivity.kt](../app/src/main/java/com/altude/android/ErrorHandlingExampleActivity.kt)
3. **Explore advanced patterns:** [VAULT_ADVANCED.md](VAULT_ADVANCED.md)
4. **Test your implementation:** [VAULT_TESTING_GUIDE.md](VAULT_TESTING_GUIDE.md)
5. **Troubleshoot issues:** [ERROR_TAXONOMY_AND_TROUBLESHOOTING.md](ERROR_TAXONOMY_AND_TROUBLESHOOTING.md)

---

## Support & Documentation

- **Common Questions?** → [Troubleshooting](#troubleshooting) section above
- **Error Codes & Recovery?** → [ERROR_CODES_QUICK_REFERENCE.md](ERROR_CODES_QUICK_REFERENCE.md)
- **Production Patterns?** → [ERROR_HANDLING_PATTERNS.md](ERROR_HANDLING_PATTERNS.md)
- **Advanced Features?** → [VAULT_ADVANCED.md](VAULT_ADVANCED.md)
- **Testing Guide?** → [VAULT_TESTING_GUIDE.md](VAULT_TESTING_GUIDE.md)
