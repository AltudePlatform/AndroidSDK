# Altude Android SDK

<div align="center">
  <img src="ALTUDE-ICON.jpg" alt="Altude Platform" width="200"/>
  
**Altude is Wallet Infrastructure for Non-Custodial Wallets on Solana**

***Fully Gasless, non-custodial and Simple***

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-2.2.0-blue.svg)](https://kotlinlang.org)

[Website](https://altude.so) • [Documentation](https://docs.altude.so) • [Discord](https://discord.gg/9gPsQeZD7x) • [Twitter](x.com/altudeso)

</div>

---

## 🌟 Overview

Altude is a comprehensive wallet infrastructure platform designed to simplify building non-custodial wallets on Solana. The Android SDK provides powerful, easy-to-use tools for developers to integrate wallet functionality, gasless transactions, provenance tracking, and more into their Android applications.

Whether you're building a DeFi app, NFT marketplace, or Web3 game, Altude provides the building blocks you need to create seamless user experiences on Solana.

## ✨ Features

- **🔑 Wallet Management** - Secure key generation, mnemonic support, and encrypted storage
- **⛽ Gasless Transactions** - Fully gasless SDK
- **💸 Token Operations** - Send, receive, and swap SPL tokens gasless
- **🖼️ NFT Support** - Create collections and mint NFTs without gas fees using Metaplex standards
- **📊 Provenance Tracking** - Gasless tools for tracking asset provenance on Solana
- **🔐 Enterprise Security** - Built-in encryption and secure key management
- **🚀 Developer Friendly** - Clean APIs with full Kotlin coroutine support

## 📦 Modules

The Altude Android SDK is organized into focused modules that can be used independently or together:

### [`core`](./core)
**Shared low-level libraries and utilities**

The foundation of the SDK providing:
- RPC communication with Solana nodes
- Transaction building and signing
- Cryptographic primitives
- Mnemonic and key pair generation
- Secure storage services
- Network configuration

### [`gasstation`](./gasstation)
**Simple gasless primitives**

Enable sponsored transactions for your users:
- Send tokens without gas fees
- Batch transaction support
- Token swaps via Jupiter aggregator
- Account creation and management
- Balance and history queries
- Automatic fee payment handling

### [`smart-account`](./smart-account)
**Smart account abstractions** *(Coming Soon)*

Advanced account features:
- Multi-signature support
- Session keys
- Account recovery
- Custom authorization logic

### [`nft`](./nft)
**Gasless tools for NFTs on Solana**

NFT features:
- Create NFT collections
- Mint compressed NFTs
- Metadata management
- Metaplex Core integration

### [`provenance`](./provenance)
**Tools for Gasless provenance on Solana**

## 🚀 Getting Started

### Prerequisites

- Android Studio Arctic Fox or later
- Minimum SDK: 21 (Android 5.0)
- Target SDK: 36
- Kotlin 2.2.0+

### Installation

Add the Altude SDK to your project:

#### Option 1: Using JitPack (Recommended)

Add JitPack to your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add dependencies to your app's `build.gradle.kts`:

```kotlin
dependencies {
    // Core module (required)
    implementation("com.github.AltudePlatform.AndroidSDK:core:1.0.0")
    
    // Gas Station module (for gasless transactions)
    implementation("com.github.AltudePlatform.AndroidSDK:gasstation:1.0.0")
    
    // NFT module (for NFT operations)
    implementation("com.github.AltudePlatform.AndroidSDK:nft:1.0.0")
}
```

#### Option 2: Local Module

Clone this repository and include it as a local module in your project's `settings.gradle.kts`:

```kotlin
include(":core", ":gasstation", ":nft")
project(":core").projectDir = File("path/to/AndroidSDK/core")
project(":gasstation").projectDir = File("path/to/AndroidSDK/gasstation")
project(":nft").projectDir = File("path/to/AndroidSDK/nft")
```

### Initialize the SDK

```kotlin
import com.altude.core.config.SdkConfig
import com.altude.gasstation.Altude

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize SDK
        lifecycleScope.launch {
            SdkConfig.initialize()
            Altude.setApiKey(this@MyApplication, "your-api-key")
            
            // Optional: Set up a wallet with mnemonic
            Altude.saveMnemonic("your twelve word mnemonic phrase here")
        }
    }
}
```

## 📖 Quick Examples

### Create a Wallet

```kotlin
import com.altude.core.helper.Mnemonic
import com.altude.gasstation.Altude

// Generate a new 12-word mnemonic
val mnemonic = Mnemonic.generateMnemonic(12)
Altude.saveMnemonic(mnemonic)

// Or import an existing one
Altude.saveMnemonic("your existing mnemonic phrase here")
```

### Send SOL (Gasless)

```kotlin
import com.altude.gasstation.Altude
import com.altude.gasstation.data.SendOptions
import com.altude.gasstation.data.Commitment

val sendOptions = SendOptions(
    account = "",  // Uses default wallet
    to = "recipient-wallet-address",
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
    to = "recipient-wallet-address",
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
    slippageBps = 50,
    commitment = Commitment.confirmed
)

val result = Altude.swap(swapOptions)
```

### Get Token Balance

```kotlin
import com.altude.gasstation.data.GetBalanceOption

val balanceOptions = GetBalanceOption(
    account = "",
    token = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
    commitment = Commitment.confirmed
)

val result = Altude.getBalance(balanceOptions)
result.onSuccess { balance ->
    println("Balance: ${balance.balance}")
}
```

### Create NFT Collection

```kotlin
import com.altude.nft.NFTSdk
import com.altude.core.data.CreateNFTCollectionOption

val collectionOptions = CreateNFTCollectionOption(
    account = "",
    name = "My Collection",
    metadataUri = "https://arweave.net/your-metadata-uri",
    sellerFeeBasisPoints = 500 // 5% royalty
)

val result = NFTSdk.createNFTCollection(collectionOptions)
result.onSuccess { response ->
    println("Collection created! Signature: ${response.signature}")
}
```

### Mint an NFT

```kotlin
import com.altude.core.data.MintOption

val mintOptions = MintOption(
    account = "",
    name = "My NFT",
    symbol = "NFT",
    uri = "https://arweave.net/your-nft-metadata",
    sellerFeeBasisPoints = 500,
    collection = "collection-mint-address",
    owner = "" // Uses default wallet
)

val result = NFTSdk.mint(mintOptions)
```

## 🏗️ Architecture

```
┌─────────────────────────────────────────────┐
│           Your Android App                  │
└─────────────────────────────────────────────┘
                    │
        ┌───────────┴───────────┐
        │                       │
┌───────▼────────┐    ┌────────▼─────────┐
│   Gas Station  │    │       NFT        │
│    Module      │    │     Module       │
└───────┬────────┘    └────────┬─────────┘
        │                      │
        └──────────┬───────────┘
                   │
           ┌───────▼────────┐
           │  Core Module   │
           │  - RPC Layer   │
           │  - Crypto      │
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

- **Encrypted Storage**: All private keys and mnemonics are encrypted using Android Keystore
- **Secure Communication**: All API calls use HTTPS with certificate pinning
- **No Key Exposure**: Private keys never leave the device unencrypted
- **Open Source**: Fully auditable code

### Best Practices

- Always use the secure storage APIs provided by the SDK
- Never log or expose private keys or mnemonics
- Use appropriate commitment levels for your use case
- Validate all user inputs before creating transactions

## 📚 Documentation

- [Full API Reference](https://docs.altude.so/api-reference/introduction) *(Coming Soon)*
- [Integration Guide](#) *(Coming Soon)*
- [Example App](./app) - *(Coming Soon)*
- [Migration Guide](https://docs.altude.so/api-reference/gas-station/converting-from-kinetic)

## 🛠️ Development

### Building from Source

```bash
git clone https://github.com/AltudePlatform/AndroidSDK.git
cd AndroidSDK
./gradlew build
```

### Running Tests

```bash
./gradlew test
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
- [Metaplex](https://www.metaplex.com) - NFT standards and tools
- [Jupiter](https://jup.ag) - Token swap aggregation

---

<div align="center">

**Built with ❤️ by the Altude Team**

</div>
