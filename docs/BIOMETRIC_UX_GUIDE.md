# BiometricPrompt Integration & UX Guide

## Overview

The Vault module integrates Jetpack BiometricPrompt for transaction authentication. This document covers:
- Two authentication modes (per-operation vs session-based)
- User experience flows
- Error handling and recovery
- Best practices
- Customization options

---

## Authentication Modes

### 1. PerOperation (Default) - Most Secure

**Behavior:** Prompt user for biometric on every signing operation.

```kotlin
// Default - every transaction requires authentication
val signer = VaultSigner.create(context, packageName)

// Or explicitly:
val signer = VaultSigner(
    context,
    appId = packageName,
    authMode = VaultAuthMode.PerOperation
)
```

**Flow Diagram:**
```
Transaction 1: Sign  →  Biometric Prompt  →  User Authenticates  →  Load Key  →  Sign  →  Clear Session
                            ↑                                                                    ↑
                          Fingerprint                                                      No Cache
                          Face Recognition
                          PIN

Transaction 2: Sign  →  Biometric Prompt  →  User Authenticates  →  Load Key  →  Sign  →  Clear Session
```

**Advantages:**
- ✅ Maximum security - no risk of key exposure from stale session
- ✅ Each transaction is independently verified by user
- ✅ No session timeout concerns
- ✅ Recommended for high-value transactions
- ✅ Complies with strictest security requirements

**Disadvantages:**
- ❌ More user friction (prompt on every transaction)
- ❌ Not ideal for batch operations or rapid transactions
- ❌ Slower user experience for power users

**Best For:**
- Most applications
- High-value transactions (>$1000)
- Enterprise applications
- Regulated environments

---

### 2. SessionBased (Advanced) - Better UX

**Behavior:** Prompt once, cache keypair for configurable TTL (typically 30-60 seconds).

```kotlin
// Session-based - batch operations with one prompt
val signer = VaultSigner.createWithSession(
    context,
    packageName,
    sessionTTLSeconds = 45  // 45 seconds = good balance
)

// Or explicitly:
val signer = VaultSigner(
    context,
    appId = packageName,
    authMode = VaultAuthMode.SessionBased(sessionTTLSeconds = 45)
)
```

**Flow Diagram:**
```
Transaction 1: Sign  →  Biometric Prompt  →  User Authenticates  →  Load Key  →  Sign  →  Cache Session
                            ↑                                                        ↑            ↑
                          Fingerprint                                            45s TTL      In Memory
                          Face Recognition                                       Start
                          PIN

Transaction 2: Sign  →  [Check Session Valid]  →  Session Valid (43s remaining)  →  Sign  →  Keep Cache
                                                                                            
Transaction 3: Sign (60s later)  →  Biometric Prompt  →  [Session Expired]  →  Sign  →  New Cache
```

**Configuration:**
| TTL Value | Use Case |
|-----------|----------|
| 20s | Paranoid security |  
| 30s | High security + decent UX |
| 45s | Default; good balance |
| 60s | Power users; batch trading |
| 120s | Custodial/testing only |

**Advantages:**
- ✅ Better UX - one prompt for multiple transactions
- ✅ Ideal for batch transfers, swaps, DeFi operations
- ✅ Configurable balance between security and usability
- ✅ Session expires gracefully (no lingering risk)

**Disadvantages:**
- ❌ Slight security reduction - keypair in memory
- ❌ Must choose appropriate TTL (too short = friction; too long = risk)
- ❌ Not recommended for very high-value operations

**Best For:**
- Batch token transfers
- DeFi trading (multiple transactions in sequence)
- Power users
- Testing and development

**Recommended TTL Values:**
```kotlin
// Conservative (high security)
VaultSigner.createWithSession(context, packageName, sessionTTLSeconds = 30)

// Balanced (default recommendation)
VaultSigner.createWithSession(context, packageName, sessionTTLSeconds = 45)

// Aggressive (power users only)
VaultSigner.createWithSession(context, packageName, sessionTTLSeconds = 60)
```

---

## User Experience Flows

### Flow 1: Single Transaction (PerOperation)

```
User taps "Send Token"
  ↓
App calls: Altude.transferToken(...)
  ↓
VaultSigner.signMessage() called
  ↓
BiometricPrompt shown: "Confirm Your Identity"
  ↓
User: Fingerprint scan / Face recognition / Enter PIN
  ↓
[System verifies authentication]
  ↓
Vault storage decrypts seed
  ↓
Keypair derived with HKDF
  ↓
Transaction signed
  ↓
Session cleared immediately
  ↓
Transaction sent to blockchain
  ↓
Success!
```

---

### Flow 2: Batch Transfers (SessionBased)

```
User taps "Send to Multiple Addresses"
  ↓
App: Create VaultSigner.createWithSession(..., sessionTTLSeconds=45)
  ↓
Transfer 1: VaultSigner.signMessage()
  ↓
  BiometricPrompt shown: "Confirm Your Identity"
  ↓
  User: Biometric authentication
  ↓
  Keypair derived and cached (45s TTL starts)
  ↓
  Sign + Send
  ↓
Transfer 2: VaultSigner.signMessage()  [10 seconds later]
  ↓
  [Session still valid: 35s remaining]
  ↓
  Reuse cached keypair (NO PROMPT)
  ↓
  Sign + Send
  ↓
Transfer 3: VaultSigner.signMessage()  [20 seconds later]
  ↓
  [Session still valid: 15s remaining]
  ↓
  Reuse cached keypair (NO PROMPT)
  ↓
  Sign + Send
  ↓
Transfer 4: VaultSigner.signMessage()  [60 seconds later]
  ↓
  [Session EXPIRED: 0s remaining]
  ↓
  BiometricPrompt shown again
  ↓
  User: Biometric authentication
  ↓
  New keypair derived and cached
  ↓
  Sign + Send
  ↓
All complete!
```

---

### Flow 3: Session Expiration Recovery

```
User signing transaction near end of TTL...

Transaction 1 (at 44s):
  ↓
  Session valid (1s remaining)
  ↓
  Reuse keypair
  ↓
  Sign successful

Transaction 2 (at 46s):
  ↓
  Session EXPIRED
  ↓
  BiometricHandler.checkBiometricAvailability()
  ↓
  BiometricPrompt shown
  ↓
  If user authenticates: New session created
  ↓
  If user cancels: BiometricAuthenticationFailedException thrown
  ↓
     App can:
     - Show: "Authentication cancelled. Transaction not signed."
     - Retry: User taps "Try Again"
     - Or abort the operation
```

---

## Error Handling & Recovery

### Error: BiometricNotAvailableException

**Cause:** User hasn't set up biometric (fingerprint, face) or device credential (PIN)

```kotlin
try {
    val signer = VaultSigner.create(context, packageName)
    signer.signMessage(txnMessage)
} catch (e: BiometricNotAvailableException) {
    // Graceful recovery
    showDialog(
        title = "Setup Required",
        message = e.message,
        action = "Open Settings",
        onAction = { 
            launchBiometricSetup(context)
        }
    )
}
```

**User Recovery:**
1. Open Settings → Security → Biometric/Screen Lock
2. Set up fingerprint, face, or PIN
3. Retry operation

---

### Error: BiometricInvalidatedException

**Cause:** User enrolled new fingerprints, changed face, or updated PIN after Vault was initialized

```kotlin
catch (e: BiometricInvalidatedException) {
    // Critical error - keys are invalidated
    showDialog(
        title = "Authentication Changed",
        message = "Your stored encryption keys are no longer accessible.\n\n" +
                  "You must reinstall the app or clear app data to start fresh.",
        actions = listOf(
            "Clear App Data",
            "Uninstall & Reinstall"
        )
    )
}
```

**Why it happens:** Android invalidates keys encrypted with old biometric enrollment when:
- User adds new fingerprints
- User resets face recognition
- User changes PIN/pattern
- Biometric sensor is replaced

**Recovery:**
1. Clear app data (loses Vault entirely) → Start fresh
2. Uninstall & reinstall app
3. Re-initialize Vault with new biometric

---

### Error: BiometricAuthenticationFailedException

**Cause:** User cancelled prompt, authentication failed, or lockout after too many attempts

```kotlin
catch (e: BiometricAuthenticationFailedException) {
    if (e.message.contains("locked out")) {
        showDialog(
            title = "Too Many Attempts",
            message = "Biometric locked for security. Use device credential (PIN) or wait for timeout.",
            actions = listOf(
                "Use PIN Instead",
                "Try Later"
            )
        )
    } else {
        // User cancelled or failed attempt
        showDialog(
            title = "Authentication Failed",
            message = "Try again or cancel transaction",
            actions = listOf(
                "Retry",
                "Cancel"
            )
        )
    }
}
```

**Recoverable:**
- ✅ Retry immediately
- ✅ Use device credential as fallback
- ✅ Try again after timeout (if locked out)

---

## Customizing Prompts

### Custom Messages

```kotlin
// Default messages
val signer1 = VaultSigner.create(context, packageName)
// Shows: "Confirm Your Identity"

// Custom messages for specific operations
val authMessages = VaultSigner.AuthMessages(
    title = "Sign Transaction",
    description = "Authorize payment of 1000 USDC to alice.sol",
    cancelButton = "Deny"
)

val signer2 = VaultSigner(
    context,
    appId = packageName,
    authMode = VaultAuthMode.PerOperation,
    authMessages = authMessages
)

signer2.signMessage(txn)
// Shows custom message
```

**Consider Context for Messaging:**
```kotlin
// High-value transaction
val highValueMessage = VaultSigner.AuthMessages(
    title = "Confirm Large Payment",
    description = "You are about to send 50,000 USDC. Confirm to proceed.",
    cancelButton = "Deny Payment"
)

// Batch operation
val batchMessage = VaultSigner.AuthMessages(
    title = "Batch Operations",
    description = "Performing 10 transactions. Confirm to proceed.",
    cancelButton = "Cancel All"
)

// Governance vote
val govMessage = VaultSigner.AuthMessages(
    title = "Vote on Proposal",
    description = "Vote YES on governance proposal #42",
    cancelButton = "Decline Vote"
)
```

---

## Implementation Patterns

### Pattern 1: Default Secure (Recommended for Most Apps)

```kotlin
// One-liner - per-operation, most secure
AltudeGasStation.init(context, apiKey)

// Every transaction prompts user
// Best for: Most apps, high-value operations
```

---

### Pattern 2: Batch Operations

```kotlin
// Use session for batch transfers
val signer = VaultSigner.createWithSession(
    context,
    packageName,
    sessionTTLSeconds = 45
)

SdkConfig.setSigner(signer)

// Now multiple transfers use one prompt
for (recipient in recipients) {
    Altude.transferToken(
        SendOptions(
            account = myAccount,
            toAddress = recipient,
            token = "USDC",
            amount = 100.0
        )
    )
    // Transactions 2+ reuse session (no prompt)
}
```

---

### Pattern 3: Transaction-Specific Customization

```kotlin
// Use per-operation for high-value, session for batch
fun sendLargeAmount(recipient: String, amount: Double) {
    // High security - prompt every time
    val signer = VaultSigner.create(
        context,
        packageName,
        authMessages = VaultSigner.AuthMessages(
            title = "Confirm Large Transfer",
            description = "Send $amount USDC to $recipient?",
            cancelButton = "Deny Transfer"
        )
    )
    
    SdkConfig.setSigner(signer)
    Altude.transferToken(options)
}

fun batchSwap(swaps: List<SwapOption>) {
    // Better UX - one prompt for all
    val signer = VaultSigner.createWithSession(
        context,
        packageName,
        sessionTTLSeconds = 60  // Longer for complex operations
    )
    
    SdkConfig.setSigner(signer)
    for (swap in swaps) {
        Altude.swap(swap)
    }
}
```

---

### Pattern 4: Runtime Mode Selection

```kotlin
sealed class TransactionType {
    object HighValue : TransactionType()
    object Batch : TransactionType()
    object Normal : TransactionType()
}

fun getSigner(txnType: TransactionType): VaultSigner {
    return when (txnType) {
        TransactionType.HighValue -> {
            // Maximum security - per-operation
            VaultSigner.create(context, packageName)
        }
        TransactionType.Batch -> {
            // Good UX balance - 45s session
            VaultSigner.createWithSession(context, packageName, 45)
        }
        TransactionType.Normal -> {
            // Default secure
            VaultSigner.create(context, packageName)
        }
    }
}
```

---

## Best Practices

### 1. **Use Per-Operation by Default**
```kotlin
// ✅ Most secure
AltudeGasStation.init(context, apiKey)

// Only switch to session if needed
val signer = VaultSigner.createWithSession(...)
```

### 2. **Gracefully Handle Cancellation**
```kotlin
runCatchingCancellable {
    signer.signMessage(message)
}.onFailure { error ->
    when (error) {
        is BiometricAuthenticationFailedException -> {
            if ("cancelled" in error.message.lowercase()) {
                // User cancelled - doesn't need error message
                return
            }
            showError("Authentication failed: ${error.message}")
        }
        is BiometricInvalidatedException -> {
            // Critical - guide user to reinstall
            showCriticalError("Keys invalidated. Clear app data.")
        }
    }
}
```

### 3. **Provide Context in Messages**
```kotlin
// ❌ Generic
BiometricPrompt.PromptInfo.Builder()
    .setTitle("Confirm Identity")
    .setDescription("Use biometric to sign")

// ✅ Contextual
BiometricPrompt.PromptInfo.Builder()
    .setTitle("Sign 50 USDC Transfer")
    .setDescription("To: alice.sol | Amount: 50 USDC | Gas: 5000 lamports")
```

### 4. **Session TTL Considerations**
```kotlin
// Security vs UX tradeoff
val ttl = when {
    transactionValue > 10000 -> 30    // High value: strict security
    isBatchOperation -> 60            // Batch: allow more time
    else -> 45                        // Default: balanced
}

val signer = VaultSigner.createWithSession(context, packageName, ttl)
```

### 5. **Never Cache Signer Across App Restarts**
```kotlin
// ❌ Wrong - VaultSigner keeps session in memory
class MyApp : Application() {
    companion object {
        val globalSigner = VaultSigner.create(...)  // BAD!
    }
}

// ✅ Correct - Create fresh on each screen
class PaymentActivity : FragmentActivity() {
    private val signer by lazy {
        VaultSigner.create(this, packageName)
    }
}
```

---

## Testing

### Mock Signer for Tests
```kotlin
class MockVaultSigner : TransactionSigner {
    override val publicKey = PublicKey(ByteArray(32))
    override suspend fun signMessage(message: ByteArray) = ByteArray(64)
}

// In tests
AltudeGasStation.init(
    context,
    apiKey,
    InitOptions.custom(MockVaultSigner())
)
```

### Testing Biometric Flows
```kotlin
@Test
fun testBiometricAuthentication() {
    val authenticated = BiometricHandler.checkBiometricAvailability(context)
    assertTrue(authenticated)
}

@Test
fun testBiometricPromptDisplay() {
    val signer = VaultSigner.create(mockActivity, packageName)
    // Run on emulator with biometric mock enabled
    val signature = runBlocking { signer.signMessage(message) }
    assertEquals(64, signature.size)
}
```

---

## Troubleshooting

**Q: User keeps seeing biometric prompt on every transaction - is this normal?**
```
A: Yes! That's the secure per-operation mode (default). 
Use VaultSigner.createWithSession() for batch operations.
```

**Q: How do I batch sign without prompting each time?**
```
A: Use session mode:
val signer = VaultSigner.createWithSession(context, packageName, 45)
SdkConfig.setSigner(signer)
// Now 10 transactions use 1 prompt within 45s window
```

**Q: Biometric keys became invalid. What happened?**
```
A: User enrolled new biometric, changed PIN, or updated face after vault init.
Android invalidates keys for security. User must clear app data or reinstall.
```

**Q: Can I use external hardware wallet instead?**
```
A: Yes! Use SignerStrategy.external():
AltudeGasStation.init(context, apiKey, InitOptions.custom(hardwareWalletSigner))
```

---

## See Also

- [VAULT_QUICKSTART.md](VAULT_QUICKSTART.md)
- [INIT_SIGNER_API.md](INIT_SIGNER_API.md)
- [VAULT_ERRORS.md](VAULT_ERRORS.md)

