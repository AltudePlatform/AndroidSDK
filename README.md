<div align="center">
  <img src="ALTUDE-ICON.jpg" alt="Altude Platform" width="100"/>

  # Altude Android SDK

**Altude is Gasless Wallet Infrastructure for Solana**

***Gasless. Non-custodial. Simple by default.***

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-2.2.0-blue.svg)](https://kotlinlang.org)

[Website](https://altude.so) • [Documentation](https://docs.altude.so) • [Discord](https://discord.gg/9gPsQeZD7x) • [Twitter](x.com/altudeso)

</div>

---

## 🌟 Overview

Altude is gasless transaction infrastructure for Solana. The Android SDK lets your app send, swap,
and manage SPL tokens without your users ever needing SOL for gas — Altude sponsors the fees.

Core includes basic encrypted on-device key management, so a standard integration does not need
to implement a signer. Advanced applications can still provide a custom
[`TransactionSigner`](./core/src/main/java/com/altude/core/model/TransactionSigner.kt) for
hardware wallets, KMS, MPC, or other signing strategies.

## ✨ Features

- **⛽ Gasless Transactions** - Send, swap, and manage SPL tokens without gas fees
- **💸 Token Operations** - Send, receive, and swap SPL tokens via Jupiter aggregator
- **🔑 Built-In Key Management** - Encrypted local keys work without custom signer code
- **🚀 Developer Friendly** - Clean APIs with full Kotlin coroutine support

## 📦 Modules

The Altude Android SDK ships two modules, plus a minimal example app:

### [`core`](./core)
**Shared low-level libraries and utilities**

The foundation of the SDK, providing:
- RPC communication with Solana nodes
- Transaction building
- Key-management primitives (`KeyManager`, `LocalKeyManager`, `TransactionSigner`, `HotSigner`)
- Mnemonic/keypair helpers and Android Keystore-backed encrypted local storage
- Network configuration

### [`gasstation`](./gasstation)
**Gasless transaction primitives**

Enable sponsored transactions for your users:
- Send tokens without gas fees
- Batch transaction support
- Token swaps via Jupiter aggregator
- Account creation and management
- Balance and history queries
- Automatic fee payment handling

### [`app`](./app)
A minimal example Android app demonstrating built-in local signing.

## 🔑 Key Management

The basic integration creates or reuses an encrypted local signer managed by Core:

```kotlin
import com.altude.gasstation.AltudeGasStation

AltudeGasStation.init(context, apiKey)
```

Core's [`LocalKeyManager`](./core/src/main/java/com/altude/core/keys/LocalKeyManager.kt) exposes
simple create, import, list, select, and delete operations. Keys are encrypted at rest with Android
Keystore. For an advanced integration, pass a custom signer during initialization:

```kotlin
AltudeGasStation.init(context, apiKey, myHardwareBackedSigner)
```

Notes:
- Individual Gas Station operations also accept a per-call signer override, letting you sign
  a specific operation with a different key than the one registered at init time.
- Gas Station does not choose keys from transaction account input. Initialization registers one
  deterministic default signer, which can be replaced explicitly.

## 📖 Quick Examples

### Initialize the SDK

```kotlin
import com.altude.gasstation.AltudeGasStation

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        lifecycleScope.launch {
            AltudeGasStation.init(this@MyApplication, "your-api-key")
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
│        (uses local or custom signing)        │
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
             │  - Key Manager │
             │  - Signer API  │
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

- **Local custody**: Core's default key manager stores encrypted key material on the device; it is
  never sent to Altude.
- **Pluggable signing**: Apps can replace local signing with a hardware, HSM, MPC, or remote signer.
- **Secure communication**: API calls use HTTPS.
- **Open Source**: Fully auditable code.

### Best Practices

- Use a custom `TransactionSigner` when the built-in local key manager does not meet your threat
  model or recovery requirements.
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
