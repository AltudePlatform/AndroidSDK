# Vault Module - Advanced Guide

## Overview

This guide covers advanced Vault features for developers who need fine-grained control over key management, multi-account scenarios, custom signing strategies, and recovery options.

---

## Table of Contents

1. [Advanced Initialization](#advanced-initialization)
2. [Multi-Wallet Support](#multi-wallet-support)
3. [Custom Signers](#custom-signers)
4. [Session Management](#session-management)
5. [Programmatic Access](#programmatic-access)
6. [Biometric Configuration](#biometric-configuration)
7. [Key Derivation Details](#key-derivation-details)

---

## Advanced Initialization

### Full InitOptions Explained

```kotlin
import com.altude.core.config.InitOptions
import com.altude.core.config.SignerStrategy

val options = InitOptions(
    // Signer strategy (default: VaultDefault)
    signerStrategy = SignerStrategy.VaultDefault(
        enableBiometric = true,           // Require biometric auth (default: true)
        sessionTTLSeconds = 45,           // Session timeout in seconds (default: 45)
        appId = "com.myapp.vault",        // Vault identifier for key derivation (default: package name)
        walletIndex = 0                   // Multi-wallet support (default: 0)
    ),
    enableBiometric = true,               // Override at top level (default: true)
    sessionTTLSeconds = 45                // Override at top level (default: 45)
)

AltudeGasStation.init(context, apiKey, options)
```

### Initialization Flow

1. Check if context is FragmentActivity (required for biometric)
2. Verify biometric availability (if `enableBiometric = true`)
3. Initialize Core SDK (SdkConfig, StorageService)
4. Create or retrieve vault
5. Initialize signer (VaultSigner or External)
6. Set signer in SdkConfig as default
7. Generate mnemonic for backward compatibility

---

## Multi-Wallet Support

### Scenario: Multiple Accounts from Same Seed

Same seed can derive different keypairs for different purposes:

```kotlin
sealed class Wallet {
    data class User(val index: Int = 0)
    data class Trading(val index: Int = 1)
    data class Treasury(val index: Int = 2)
}

// Initialize user wallet
AltudeGasStation.init(
    context, apiKey,
    InitOptions(
        signerStrategy = SignerStrategy.VaultDefault(walletIndex = Wallet.User().index)
    )
)

// Sign transaction
Altude.send(options)  // Uses user wallet keypair

// Later, switch to trading wallet (different keypair, same seed)
AltudeGasStation.lockVault()  // Clear session

AltudeGasStation.init(
    context, apiKey,
    InitOptions(
        signerStrategy = SignerStrategy.VaultDefault(walletIndex = Wallet.Trading().index)
    )
)

// Next transaction uses trading wallet keypair
```

### Key Derivation Path

Each wallet uses deterministic derivation:

```
Root Seed (256-bit, random)
    ├─ HKDF extraction with salt "altude_vault_salt"
    └─ HKDF expansion with info "altude.vault.<appId>.<walletIndex>"
        └─ Wallet N keypair (deterministic)
```

**Key Point:** Same seed + same appId + same walletIndex = same keypair every time

---

## Custom Signers

### Implementing a Custom Signer

```kotlin
import com.metaplex.signer.Signer
import com.solana.core.PublicKey

class MyHardwareWalletSigner(
    private val hardwareAddress: String,
    override val publicKey: PublicKey
) : Signer {
    override suspend fun signMessage(message: ByteArray): ByteArray {
        // Send to hardware wallet, get signature back
        val signature = hardwareWallet.requestSignature(hardwareAddress, message)
        return signature
    }
}

class MyMultiSigSigner(
    private val signers: List<Signer>
) : Signer {
    override val publicKey: PublicKey
        get() = signers.first().publicKey

    override suspend fun signMessage(message: ByteArray): ByteArray {
        // Collect signatures from all signers
        val signatures = signers.map { it.signMessage(message) }
        // Aggregate (implementation depends on multisig scheme)
        return aggregateSignatures(signatures)
    }
}
```

### Use Custom Signer

```kotlin
val customSigner = MyHardwareWalletSigner(
    hardwareAddress = "hw://device1",
    publicKey = PublicKey("...")
)

AltudeGasStation.init(
    context,
    apiKey,
    InitOptions(
        signerStrategy = SignerStrategy.External(customSigner)
    )
)

// All subsequent transactions signed by hardware wallet
```

### Per-Transaction Signer Override

Even if Vault is default, override signer for specific transactions:

```kotlin
// Initialize with Vault
AltudeGasStation.init(context, apiKey)

// Use Vault for this transaction
Altude.send(options)

// Use custom signer for this specific transaction
val customSigner = MyCustomSigner()
GaslessManager.transferToken(options, signer = customSigner)
```

---

## Session Management

### Understanding Sessions

A session holds a decrypted keypair in memory with a TTL:

```
User Action → Biometric Prompt → Seed Decrypted → Keypair Derived
    ↓
    [Session Created - 45s duration]
    ↓
Transaction 1: Cached key (no prompt)
Transaction 2 (25s later): Cached key (no prompt)
Transaction 3 (50s later): EXPIRED → Re-prompt for biometric
```

### Session Control

```kotlin
// Check session validity
val isActive = AltudeGasStation.isVaultUnlocked()

// Force session expiration
AltudeGasStation.lockVault()

// Adjust TTL for next init
AltudeGasStation.init(
    context, apiKey,
    InitOptions(
        signerStrategy = SignerStrategy.VaultDefault(
            sessionTTLSeconds = 300  // 5 minutes for batch operations
        )
    )
)
```

### Session Behavior During App Lifecycle

```kotlin
// Good practice: Clear session when app goes to background
override fun onPause() {
    super.onPause()
    lifecycleScope.launch {
        AltudeGasStation.lockVault()
    }
}

// Session survives:
// - Configuration changes (Activity recreate)
// - NotificationManager interactions
// - Quick app backgrounding/foregrounding (within TTL)

// Session is cleared:
// - TTL expiration
// - Explicit lockVault() call
// - App process termination
// - Device reboot
```

---

## Programmatic Access

### Direct VaultManager Calls (Advanced)

For lower-level control, access VaultManager directly:

```kotlin
import com.altude.core.vault.manager.VaultManager

// Create vault (if not exists)
VaultManager.createVault(context, appId = context.packageName)

// Unlock and get keypair (prompts for biometric)
val keypair = VaultManager.unlockVault(
    context as FragmentActivity,
    appId = context.packageName,
    walletIndex = 0,
    sessionTTLSeconds = 60
)

// Use keypair for custom operations
val signature = VaultCrypto.signMessage(myMessage, keypair)

// Get current session
val session = VaultManager.getSession()
if (session != null && session.isValid()) {
    println("Session valid for ${session.remainingTimeMs()}ms")
}

// For testing: Clear vault
VaultManager.clearVault(context, appId)
```

### Direct VaultSigner Instantiation

Create VaultSigner directly instead of via AltudeGasStation:

```kotlin
import com.altude.core.vault.model.VaultSigner

val vaultSigner = VaultSigner.create(
    context as FragmentActivity,
    appId = context.packageName,
    walletIndex = 0,
    sessionTTLSeconds = 45
)

// Use in any Signer-compatible code
GaslessManager.transferToken(options, signer = vaultSigner)
```

---

## Biometric Configuration

### Prompt Customization (Future)

Currently, biometric prompts use system defaults. Future versions may allow customization:

```kotlin
// NOT YET IMPLEMENTED - placeholder structure
BiometricHandler.setPromptTitle("Sign Transaction")
BiometricHandler.setPromptSubtitle("Confirm with your fingerprint")
BiometricHandler.setPromptDescription("Authorizing token transfer to...")
```

### Biometric Feature Detection

Check what authentication methods are available:

```kotlin
import androidx.biometric.BiometricManager
import android.content.Context

val biometricManager = BiometricManager.from(context)
val canAuthenticate = biometricManager.canAuthenticate(
    BiometricManager.Authenticators.BIOMETRIC_STRONG or
    BiometricManager.Authenticators.DEVICE_CREDENTIAL
)

when (canAuthenticate) {
    BiometricManager.BIOMETRIC_SUCCESS -> println("Biometric ready")
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> println("No biometric set up")
    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> println("Hardware unavailable")
    // ... other conditions
}
```

### Disabling Biometric Requirement (Not Recommended)

For testing/demo scenarios only:

```kotlin
// ⚠️ NOT PRODUCTION SAFE - keys stored without auth gate
AltudeGasStation.init(
    context,
    apiKey,
    InitOptions(
        signerStrategy = SignerStrategy.VaultDefault(
            enableBiometric = false  // ⚠️ Dangerous!
        )
    )
)
```

**Never use in production.** Android Keystore will automatically use device credential (PIN/pattern) if biometric unavailable.

---

## Key Derivation Details

### HKDF Derivation Process

Vault uses HKDF-SHA256 for deterministic key derivation:

```
Inputs:
  - IKM (Input Keying Material): Root seed (256 bits from SecureRandom)
  - Salt: "altude_vault_salt" (14 bytes ASCII)
  - Info: "altude.vault.<appId>.<walletIndex>" (variable length)

Extract phase: PRK = HMAC-SHA256(salt, IKM)
Expand phase: OKM = HMAC-SHA256(PRK, count || info || length_octet)
  
Output: First 32 bytes of OKM used as Ed25519 seed
```

### Domain Separation

AppId and walletIndex are part of the info input to prevent cross-app/cross-wallet key collision:

```
appId = "com.myapp"
walletIndex = 0
→ info = "altude.vault.com.myapp.0"

appId = "com.myapp"
walletIndex = 1
→ info = "altude.vault.com.myapp.1"

appId = "com.otherapp"
walletIndex = 0
→ info = "altude.vault.com.otherapp.0"
```

Each produces a unique keypair from the same seed.

### Verification

To verify key derivation is deterministic:

```kotlin
// Initialize vault
AltudeGasStation.init(context, "key1")
val key1 = VaultManager.unlockVault(context as FragmentActivity, context.packageName, 0)
val sig1 = sign("test", key1)

// Later
AltudeGasStation.lockVault()
val key2 = VaultManager.unlockVault(context as FragmentActivity, context.packageName, 0)
val sig2 = sign("test", key2)

// sig1 should equal sig2 if derivation is deterministic
assert(sig1.contentEquals(sig2))
```

---

## Testing & Mocking

### Mock Biometric for Unit Tests

```kotlin
// In test setup
val mockBiometricHandler = MockBiometricHandler()
mockBiometricHandler.setAuthenticationResult(success = true)

// Inject into test
val vaultSigner = VaultSigner(context, appId)
// ... test signing
```

### Mock Signer for Integration Tests

```kotlin
class MockSigner : Signer {
    override val publicKey = PublicKey("11111111111111111111111111111111")
    override suspend fun signMessage(message: ByteArray): ByteArray {
        return ByteArray(64) { 0 }  // Dummy signature
    }
}

AltudeGasStation.init(
    context,
    apiKey,
    InitOptions(signerStrategy = SignerStrategy.External(MockSigner()))
)
```

---

## Unimplemented Features (Roadmap)

- [ ] Export vault data for migration (encrypted blob format)
- [ ] Import vault from exported blob
- [ ] Recovery key generation (optionally encrypted)
- [ ] Social recovery (guardians can recover vault)
- [ ] Hierarchical deterministic wallets (BIP44 support)
- [ ] Hardware wallet integration (Ledger, Trezor)
- [ ] Vault password backup (in addition to biometric)
- [ ] Session persistence across app restarts (with flag)

---

## Next Steps

See [Error Troubleshooting](VAULT_ERRORS.md) for common problems and solutions.
