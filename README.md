<div align="center">
  <img src="ALTUDE-ICON.jpg" alt="Altude Platform" width="100"/>

  # Altude Android SDK

**Altude is Gasless Wallet Infrastructure for Solana**

***Gasless. Non-custodial. App-owned keys.***

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-2.2.0-blue.svg)](https://kotlinlang.org)

[Website](https://altude.so) • [Documentation](https://docs.altude.so) • [Discord](https://discord.gg/9gPsQeZD7x) • [Twitter](x.com/altudeso)

</div>

---

## 🌟 Overview

Altude is gasless transaction infrastructure for Solana. The Android SDK lets your app send, swap,
and manage SPL tokens without your users ever needing SOL for gas — Altude sponsors the fees.

The SDK is intentionally small in scope: it does **not** generate, store, or manage private keys on
your behalf. Your app owns key custody end-to-end and supplies a signer that implements
[`com.altude.core.model.TransactionSigner`](./core/src/main/java/com/altude/core/model/TransactionSigner.kt).
How you obtain and protect that signer (Android Keystore, a hardware wallet, a remote signing
service, or a simple in-memory key) is entirely up to you.

## ✨ Features

- **⛽ Gasless Transactions** - Send, swap, and manage SPL tokens without gas fees
- **💸 Token Operations** - Send, receive, and swap SPL tokens via Jupiter aggregator
- **🔑 App-Owned Signing** - You provide a `TransactionSigner`; the SDK never generates or defaults to one
- **🚀 Developer Friendly** - Clean APIs with full Kotlin coroutine support

## 📦 Modules

The Altude Android SDK ships two modules, plus a minimal example app:

### [`core`](./core)
**Shared low-level libraries and utilities**

The foundation of the SDK, providing:
- RPC communication with Solana nodes
- Transaction building
- Cryptographic primitives (`TransactionSigner` interface, `HotSigner` reference implementation)
- Mnemonic/keypair helpers and optional encrypted local storage
- Network configuration

### [`gasstation`](./gasstation)
**Gasless transaction primitives**

Enable sponsored transactions for your users:
- Send tokens without gas fees
- Batch transaction support
- Token swaps via Jupiter aggregator
- Account creation and management
- Balance and history queries
- Automatic fee payment handling — requires an app-provided signer (see below)

### [`app`](./app)
A minimal example Android app demonstrating the app-owned-signer integration pattern.

## 🔑 Signer Ownership Model

Gas Station **requires** an application-provided `TransactionSigner` — there is no default,
Vault-backed, or SDK-generated signer. You must construct and supply one before Gas Station can
authorize any transaction:

```kotlin
import com.altude.core.model.TransactionSigner
import com.altude.gasstation.AltudeGasStation

// Implement TransactionSigner yourself (Android Keystore, HSM, hardware wallet,
// remote signing service, or an in-memory keypair for prototyping).
val signer: TransactionSigner = MyAppOwnedSigner(/* ... */)

// Required initialization — signer is mandatory, not optional.
AltudeGasStation.init(context, apiKey, signer)
```

If no signer is configured, Gas Station calls fail immediately with a clear error rather than
silently falling back to any default key material.

Notes on the signer contract:
- `core` ships [`HotSigner`](./core/src/main/java/com/altude/core/model/HotSigner.kt) as an
  optional, explicit `TransactionSigner` implementation apps may choose to use — it is never
  constructed or registered automatically by the SDK.
- Individual Gas Station operations also accept a per-call signer override, letting you sign
  a specific operation with a different key than the one registered at init time.
- There is no SDK-owned mnemonic/private-key storage or auto-signer path reachable from Gas
  Station. `AltudeGasStation.init`/`Altude.setApiKey` require a real `TransactionSigner`
  argument — there is no default or nullable signer path.

## 📖 Quick Examples

### Initialize the SDK

```kotlin
import com.altude.core.model.TransactionSigner
import com.altude.gasstation.AltudeGasStation

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        lifecycleScope.launch {
            val signer: TransactionSigner = MyAppOwnedSigner(/* ... */)
            AltudeGasStation.init(this@MyApplication, "your-api-key", signer)
        }
    }
}
```

### Send SOL (Gasless)

```kotlin
import com.altude.gasstation.Altude
import com.altude.gasstation.data.SendOptions
import com.altude.gasstation.data.Commitment

val sendOptions = SendOptions(
    account = "",  // Uses the configured signer's account
    toAddress = "recipient-wallet-address",
    amount = 1.0,  // 1 SOL
    token = "So11111111111111111111111111111111111111112", // SOL mint
    commitment = Commitment.confirmed
)

val result = Altude.send(sendOptions)
result
    .onSuccess { response ->
        println("Transaction sent! Signature: ${response.Signature}")
    }
    .onFailure { error ->
        println("Failed: ${error.message}")
    }
```

### Send SPL Tokens

```kotlin
val sendOptions = SendOptions(
    account = "",
    toAddress = "recipient-wallet-address",
    amount = 100.0,
    token = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", // USDC
    commitment = Commitment.confirmed
)

Altude.send(sendOptions)
```

### Swap Tokens

```kotlin
import com.altude.gasstation.data.SwapOption

val swapOptions = SwapOption(
    account = "",
    inputMint = "So11111111111111111111111111111111111111112", // SOL
    outputMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", // USDC
    amount = 1.0,
    commitment = Commitment.confirmed
)

val result = Altude.swap(swapOptions)
```

### Get Token Balance

```kotlin
import com.altude.gasstation.data.GetBalanceOption

val balanceOptions = GetBalanceOption(
    account = "",
    token = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
)

val result = Altude.getBalance(balanceOptions)
result.onSuccess { balance ->
    println("Balance: ${balance.balance}")
}
```

## 🏗️ Architecture

```
┌─────────────────────────────────────────────┐
│           Your Android App                  │
│  (owns and supplies the TransactionSigner)   │
└───────────────────┬───────────────────────────┘
                     │
             ┌───────▼────────┐
             │  Gas Station   │
             │    Module      │
             └───────┬────────┘
                     │
             ┌───────▼────────┐
             │  Core Module   │
             │  - RPC Layer   │
             │  - Signer API  │
             │  - Storage     │
             └───────┬────────┘
                     │
           ┌─────────▼─────────────┐
           │   Altude Platform     │
           │(Fee Sponsoring/Relay) │
           └─────────┬─────────────┘
                     │
              ┌──────▼───────┐
              │    Solana    │
              │   Blockchain │
              └──────────────┘
```

## 🔐 Security

- **App-owned keys**: The SDK never generates, stores, or defaults to a signer — your app controls
  key custody end-to-end.
- **Optional encrypted storage**: `core`'s `StorageService` is a standalone utility apps may use
  directly (not through Gas Station) to store/retrieve key material and build their own
  `TransactionSigner`. Gas Station never calls it automatically and has no fallback path to it.
- **Secure communication**: API calls use HTTPS.
- **Open Source**: Fully auditable code.

### Best Practices

- Implement `TransactionSigner` using key storage appropriate for your threat model (Android
  Keystore, HSM, remote signing service, hardware wallet).
- Never log or expose private keys or mnemonics.
- Use appropriate commitment levels for your use case.
- Validate all user inputs before creating transactions.

## 🛠️ Development

### Building from Source

```bash
git clone https://github.com/AltudePlatform/AndroidSDK.git
cd AndroidSDK
./gradlew build
```

### Running Tests

```bash
./gradlew :core:testDebugUnitTest
./gradlew :gasstation:testDebugUnitTest
./gradlew connectedAndroidTest
```

## 🤝 Contributing

We welcome contributions! Please see our [Contributing Guide](#) for details.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](./LICENSE) file for details.

## 🆘 Support

- **Documentation**: [docs.altude.so](http://docs.altude.so)
- **Discord**: [Join our community](https://discord.gg/9gPsQeZD7x)
- **Email**: andrew@altude.so
- **Twitter**: [@AltudePlatform](x.com/altudeso)

## 🙏 Acknowledgments

Built with:
- [Solana](https://solana.com) - High-performance blockchain
- [Jupiter](https://jup.ag) - Token swap aggregation

---

<div align="center">

**Built with ❤️ by the Altude Team**

</div>
