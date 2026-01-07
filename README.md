# Gasless Android SDK

[![GitHub release](https://img.shields.io/github/tag/AltudePlatform/AndroidSDK.svg)](https://github.com/AltudePlatform/AndroidSDK/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.0-blue.svg)](https://kotlinlang.org/)


**An Android SDK for seamless, gasless blockchain interactions.**  

The Gasless Android SDK enables Android apps to interact with blockchain networks **without requiring end-users to pay gas fees**. It provides easy-to-use APIs for managing accounts, sending transactions, querying balances, and integrating meta-transactions.

For full documentation, see [Altude Docs](https://docs.altude.so/introduction) and [Altude Dashboard](https://dashboard.altude.so/) for API key.

---

## 🚀 Features

- **Gasless transactions** – Send blockchain transactions without users holding native tokens.  
- **Batch operations** – Execute multiple transactions in a single request.  
- **Account management** – Retrieve account info, balances, and transaction history.  
- **Secure key handling** – Integrates with wallets safely.  
- **Integration-ready** – Simple APIs designed for Android apps. 

---

## 📦 Getting Started

1. Add the SDK to your Android project via Gradle:

```gradle
dependencies {
    implementation 'com.github.AltudePlatform.AndroidSDK:gasstation:v0.1.6-alpha'
}
```

2. Set API Key
```kotlin
val context =ApplicationProvider.getApplicationContext()
val sdk = Altude.setApiKey(context,"")
```

2. Usage
```kotlin
val options = CreateAccountOption(
    account = keypair.publicKey.toBase58(),
    tokens = listOf(Token.KIN.mint()),
    commitment = Commitment.finalized,

)


val result = Altude.createAccount(options)

result
    .onSuccess { println("✅ Sent: ${it.Signature}") }
    .onFailure {
        println("❌ Failed: ${it.message}")
    }
```
