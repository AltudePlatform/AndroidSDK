# InitOptions & SignerStrategy API Design

## Overview

The **InitOptions** and **SignerStrategy** APIs provide a flexible, intuitive way to configure Altude SDK initialization and choose your transaction signing strategy.

This document covers:
- Available signer strategies (Vault, External)
- Initialization patterns (basic, custom, advanced)
- Factory methods for common use cases
- Future extensibility for new signer types

---

## Design Principles

### 1. **Secure by Default**
- Vault with biometric authentication is the default
- No plaintext keys at rest
- No insecure fallbacks

### 2. **Intuitive API**
- One-liner for 95% of use cases: `AltudeGasStation.init(context, apiKey)`
- Factory methods for common patterns
- Explicit type-safety via sealed classes

### 3. **Future-Proof**
- Sealed class allows new strategies without breaking changes
- Clear extension points for MPC, multi-sig, custodians, etc.

### 4. **Type-Safe**
```kotlin
// Compiler ensures exhaustiveness in when statements
when (options.signerStrategy) {
    is SignerStrategy.VaultDefault -> { /* handle vault */ }
    is SignerStrategy.External -> { /* handle external */ }
    // Future: is SignerStrategy.MultiSig -> { /* handle multi-sig */ }
}
```

---

## SignerStrategy

### Available Strategies

```kotlin
sealed class SignerStrategy {
    data class VaultDefault(...) : SignerStrategy()
    data class External(val signer: TransactionSigner) : SignerStrategy()
    
    companion object {
        fun vaultWithBiometric(...): VaultDefault
        fun vaultWithoutBiometric(...): VaultDefault
        fun external(signer: TransactionSigner): External
        fun default(): VaultDefault
    }
}
```

---

### 1. VaultDefault (Client-Side Encrypted)

**Use Case:** Most applications - secure, client-side key storage with biometric authentication.

```kotlin
// Constructor form
SignerStrategy.VaultDefault(
    enableBiometric = true,
    sessionTTLSeconds = 45,
    appId = "com.example.app",
    walletIndex = 0
)

// Factory form (recommended)
SignerStrategy.vaultWithBiometric(
    sessionTTLSeconds = 45,
    appId = "",  // auto-filled from package name
    walletIndex = 0
)

// Without biometric (NOT recommended)
SignerStrategy.vaultWithoutBiometric(
    sessionTTLSeconds = 60,
    appId = "",
    walletIndex = 0
)
```

**Configuration:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `enableBiometric` | Boolean | `true` | Require biometric/device credential for key access |
| `sessionTTLSeconds` | Int | `45` | Session timeout (45s balances security & UX) |
| `appId` | String | `""` | Unique identifier for key derivation; auto-filled if empty |
| `walletIndex` | Int | `0` | For multi-wallet support (derivation index) |

**Features:**
- ✅ Keys never leave device
- ✅ Encrypted at rest with Android Keystore
- ✅ Biometric/device credential gating
- ✅ Session-based caching (configurable TTL)
- ✅ Multi-wallet support
- ✅ Deterministic key derivation (recovery support)

**HKDF Domain Separation:**
```
Domain: "altude:vault:solana::<appId>:<walletIndex>"
Salt: "altude_vault_salt"
Algorithm: HKDF-SHA256
Output: 32-byte Ed25519 seed
```

---

### 2. External (Custom Signers)

**Use Case:** Hardware wallets, multi-sig schemes, custodial solutions, MPC, custom key management.

```kotlin
// Custom signer import
import com.altude.core.model.TransactionSigner
import com.solana.core.PublicKey

// Implement TransactionSigner
class MyHardwareWalletSigner : TransactionSigner {
    override val publicKey: PublicKey = TODO()
    
    override suspend fun signMessage(message: ByteArray): ByteArray {
        // Your signing logic
        TODO()
    }
}

// Use in init
val signer = MyHardwareWalletSigner()
SignerStrategy.External(signer)

// Or use factory
SignerStrategy.external(signer)
```

**Examples:**

#### Hardware Wallet (Ledger-style)
```kotlin
class LedgerSigner(val derivationPath: String) : TransactionSigner {
    override val publicKey: PublicKey = getLedgerPublicKey(derivationPath)
    
    override suspend fun signMessage(message: ByteArray): ByteArray {
        return withContext(Dispatchers.IO) {
            ledgerDevice.sign(derivationPath, message)
        }
    }
}

val signer = LedgerSigner("m/44'/501'/0'/0'")
AltudeGasStation.init(
    context, 
    apiKey,
    InitOptions(signerStrategy = SignerStrategy.external(signer))
)
```

#### Multi-Sig (2-of-3 example)
```kotlin
class MultiSigSigner(val signers: List<TransactionSigner>, val threshold: Int) : TransactionSigner {
    override val publicKey: PublicKey = multiSigAddress // derived from signers
    
    override suspend fun signMessage(message: ByteArray): ByteArray {
        val signatures = signers
            .take(threshold)
            .map { it.signMessage(message) }
        return combineSignatures(signatures)
    }
}
```

#### Custodial Solution
```kotlin
class CustodialSigner(val userId: String, val apiClient: CustodyService) : TransactionSigner {
    override val publicKey: PublicKey = apiClient.getUserPublicKey(userId)
    
    override suspend fun signMessage(message: ByteArray): ByteArray {
        return apiClient.requestSign(userId, message)
    }
}
```

---

## InitOptions

### Available Options

```kotlin
data class InitOptions(
    val signerStrategy: SignerStrategy = SignerStrategy.VaultDefault(),
    val enableBiometric: Boolean = true,
    val sessionTTLSeconds: Int = 45
) {
    companion object {
        fun default(): InitOptions
        fun vault(sessionTTLSeconds: Int = 45, ...): InitOptions
        fun custom(signer: TransactionSigner): InitOptions
        fun vaultNoBiometric(sessionTTLSeconds: Int = 45, ...): InitOptions
    }
}
```

---

## Initialization Patterns

### Pattern 1: Default (One-Liner) ⭐ Recommended

**For:** 95% of applications

```kotlin
// Best for most apps
AltudeGasStation.init(context, apiKey)

// First transaction shows biometric prompt
// Subsequent transactions within 45s reuse session (no prompt)
// After 45s, next transaction re-prompts
```

---

### Pattern 2: Custom Vault Settings

**For:** Apps with custom security/UX requirements

```kotlin
// Vault with 30-second session (tighter security)
AltudeGasStation.init(
    context,
    apiKey,
    InitOptions.vault(sessionTTLSeconds = 30)
)

// Vault without biometric (not recommended)
AltudeGasStation.init(
    context,
    apiKey,
    InitOptions.vaultNoBiometric(sessionTTLSeconds = 120)
)

// Multi-wallet app (multiple signer instances)
val signer1 = VaultSigner(context, packageName, walletIndex = 0)
val signer2 = VaultSigner(context, packageName, walletIndex = 1)
// Each signs with different derived key
```

---

### Pattern 3: Direct Construction

**For:** Full control (when factories aren't sufficient)

```kotlin
AltudeGasStation.init(
    context,
    apiKey,
    InitOptions(
        signerStrategy = SignerStrategy.VaultDefault(
            enableBiometric = true,
            sessionTTLSeconds = 60,
            appId = "custom.app.id",
            walletIndex = 0
        )
    )
)
```

---

### Pattern 4: External Signer (Hardware Wallet)

**For:** Hardware wallets, multi-sig, custodial

```kotlin
// Your custom signer implementation
class MyHardwareWallet : TransactionSigner {
    // ... implementation
}

val signer = MyHardwareWallet()

AltudeGasStation.init(
    context,
    apiKey,
    InitOptions.custom(signer)
)

// Or directly:
AltudeGasStation.init(
    context,
    apiKey,
    InitOptions(
        signerStrategy = SignerStrategy.external(signer)
    )
)
```

---

### Pattern 5: Runtime Signer Selection

**For:** Apps that let users choose signing method

```kotlin
fun initOnboardingFlow(context: Context, apiKey: String, userChoice: SigningMethod) {
    val strategy = when (userChoice) {
        SigningMethod.VAULT_SECURE -> SignerStrategy.vaultWithBiometric()
        SigningMethod.HARDWARE_WALLET -> SignerStrategy.external(hardwareWalletSigner)
        SigningMethod.MULTI_SIG -> SignerStrategy.external(multiSigSigner)
        SigningMethod.CUSTODIAL -> SignerStrategy.external(custodialSigner)
    }
    
    AltudeGasStation.init(
        context,
        apiKey,
        InitOptions(signerStrategy = strategy)
    )
}
```

---

## Best Practices

### 1. **Always Use Custom Context Types**
```kotlin
// ✅ Correct: FragmentActivity for Vault
val activity: FragmentActivity = this
AltudeGasStation.init(activity, apiKey)

// ❌ Wrong: Doesn't support biometric
val context: Context = this
AltudeGasStation.init(context, apiKey)  // Will throw error if using Vault
```

### 2. **Handle VaultException**
```kotlin
val result = AltudeGasStation.init(context, apiKey)
result.onFailure { error ->
    when (error) {
        is BiometricNotAvailableException -> {
            // User hasn't set up biometric
        }
        is BiometricInvalidatedException -> {
            // Biometric enrollment changed; keys invalidated
            // User must reinstall app or clear data
        }
        is VaultInitFailedException -> {
            // Storage or permission issue
        }
    }
}
```

### 3. **Cache InitOptions if Reusing**
```kotlin
val vaultConfig = InitOptions.vault(sessionTTLSeconds = 60)

// Use in multiple screens
AltudeGasStation.init(context1, apiKey, vaultConfig)
AltudeGasStation.init(context2, apiKey, vaultConfig)
```

### 4. **Document Your Custom Signer**
```kotlin
/// Your custom TransactionSigner implementation.
/// 
/// Security considerations:
/// - Keys are stored in [your location]
/// - Signing takes [duration] ms
/// - Supports [your supported transaction types]
class MyCustomSigner : TransactionSigner {
    // ...
}
```

---

## Future Extensions

The sealed class design allows for new signer strategies without breaking existing code:

```kotlin
// Future: Multi-signature support
sealed class SignerStrategy {
    // ... existing strategies ...
    
    data class MultiSig(
        val signers: List<TransactionSigner>,
        val threshold: Int
    ) : SignerStrategy()
}

// Future: MPC (Multi-Party Computation)
data class MPC(
    val mpcProvider: String,
    val session: MpcSession
) : SignerStrategy()

// Future: Custodial
data class Custodial(
    val serviceUrl: String,
    val apiKey: String
) : SignerStrategy()
```

When new strategies are added, developers must update their when statements:
```kotlin
val signer = when (options.signerStrategy) {
    is SignerStrategy.VaultDefault -> { /* ... */ }
    is SignerStrategy.External -> { /* ... */ }
    is SignerStrategy.MultiSig -> { /* ... */ }  // New!
    is SignerStrategy.MPC -> { /* ... */ }        // New!
}
```

---

## API Reference

### SignerStrategy.VaultDefault

```kotlin
data class VaultDefault(
    val enableBiometric: Boolean = true,
    val sessionTTLSeconds: Int = 45,
    val appId: String = "",
    val walletIndex: Int = 0
) : SignerStrategy()
```

**Validation:**
- `sessionTTLSeconds > 0` or throws `IllegalArgumentException`
- `walletIndex >= 0` or throws `IllegalArgumentException`

### SignerStrategy.External

```kotlin
data class External(val signer: TransactionSigner) : SignerStrategy()
```

**Requirements:**
- `signer` must implement `TransactionSigner` interface
- Must provide both `publicKey` property and `signMessage()` method

### SignerStrategy Factories

```kotlin
companion object {
    fun vaultWithBiometric(
        sessionTTLSeconds: Int = 45,
        appId: String = "",
        walletIndex: Int = 0
    ): VaultDefault
    
    fun vaultWithoutBiometric(
        sessionTTLSeconds: Int = 45,
        appId: String = "",
        walletIndex: Int = 0
    ): VaultDefault
    
    fun external(signer: TransactionSigner): External
    
    fun default(): VaultDefault
}
```

### InitOptions.Factories

```kotlin
companion object {
    fun default(): InitOptions
    
    fun vault(
        sessionTTLSeconds: Int = 45,
        appId: String = "",
        walletIndex: Int = 0
    ): InitOptions
    
    fun custom(signer: TransactionSigner): InitOptions
    
    fun vaultNoBiometric(
        sessionTTLSeconds: Int = 45,
        appId: String = "",
        walletIndex: Int = 0
    ): InitOptions
}
```

---

## Examples by Use Case

### Mobile Wallet App
```kotlin
// Use Vault - keys stay on device
AltudeGasStation.init(context, apiKey)
```

### Enterprise DeFi App
```kotlin
// Use multi-sig or custodial
val signer = enterpriseCustodialSigner
AltudeGasStation.init(context, apiKey, InitOptions.custom(signer))
```

### Hardware Wallet Integration
```kotlin
// Use external hardware signer
val signer = ledgerUSBSigner
AltudeGasStation.init(context, apiKey, InitOptions.custom(signer))
```

### Multi-Wallet App
```kotlin
// Create multiple signers from same source
val wallet1 = VaultSigner(context, packageName, walletIndex = 0)
val wallet2 = VaultSigner(context, packageName, walletIndex = 1)

// Switch between them
currentSigner = wallet1  // or wallet2
```

### Testing Mock Signer
```kotlin
class MockSigner : TransactionSigner {
    override val publicKey = PublicKey("11111111111111111111111111111111")
    override suspend fun signMessage(message: ByteArray) = ByteArray(64)
}

// In tests:
AltudeGasStation.init(context, apiKey, InitOptions.custom(MockSigner()))
```

---

## Migration Guide

### From HotSigner (Legacy)
```kotlin
// Old way (legacy Altude.setApiKey)
Altude.setApiKey(apiKey)
// Uses HotSigner - keys in memory, no encryption

// New way (recommended)
AltudeGasStation.init(context, apiKey)
// Uses VaultSigner by default - keys encrypted, biometric gated
```

### To External Signer
```kotlin
// Old: Vault only
AltudeGasStation.init(context, apiKey)

// New: Switch to hardware wallet
val hardwareSigner = MyHardwareWalletSigner()
AltudeGasStation.init(
    context,
    apiKey,
    InitOptions.custom(hardwareSigner)
)
```

---

## Troubleshooting

**Q: How do I change signer after init?**
```kotlin
// Re-init with new strategy
AltudeGasStation.init(context, apiKey, InitOptions.custom(newSigner))
```

**Q: Can I use both Vault and external signer?**
```kotlin
// Create two separate signer instances
val vaultSigner = VaultSigner(context, packageName)
val hardwareSigner = MyHardwareWalletSigner()

// Use them for different purposes:
// - Transactions: use vaultSigner
// - Governance: use hardwareSigner
```

**Q: What if I need a custom signer not listed?**
```kotlin
// Implement TransactionSigner interface
class MyCustomSigner : TransactionSigner {
    override val publicKey: PublicKey = TODO()
    override suspend fun signMessage(message: ByteArray): ByteArray = TODO()
}

AltudeGasStation.init(
    context,
    apiKey,
    InitOptions.custom(MyCustomSigner())
)
```

---

## See Also

- [VAULT_QUICKSTART.md](VAULT_QUICKSTART.md) - Quick start for Vault
- [VAULT_ADVANCED.md](VAULT_ADVANCED.md) - Advanced Vault topics
- [VAULT_ERRORS.md](VAULT_ERRORS.md) - Error handling
- [TransactionSigner Interface](../core/src/main/java/com/altude/core/model/TransactionSigner.kt)

