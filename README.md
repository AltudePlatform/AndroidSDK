# Altude Android SDK

[![](https://jitpack.io/v/AltudePlatform/AndroidSDK.svg)](https://jitpack.io/#AltudePlatform/AndroidSDK)

The Altude Android SDK provides a comprehensive set of tools for integrating Solana blockchain functionality into your Android applications.

## Modules

- **core**: Core functionality and shared utilities
- **gasstation**: Gas station and transaction management
- **nft**: NFT operations and metadata handling  
- **smart-account**: Smart account management

## Installation

Add JitPack repository to your root `build.gradle.kts`:

```kotlin
allprojects {
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
    implementation("com.github.AltudePlatform.AndroidSDK:core:0.1.0-alpha")
    
    // Optional modules
    implementation("com.github.AltudePlatform.AndroidSDK:gasstation:0.1.0-alpha")
    implementation("com.github.AltudePlatform.AndroidSDK:nft:0.1.0-alpha")
    implementation("com.github.AltudePlatform.AndroidSDK:smart-account:0.1.0-alpha")
}
```

## Version Information

Current version: **0.1.0-alpha**

This is the lowest possible alpha version. The SDK is in early development and APIs may change.

## Usage

### Core Module

```kotlin
// Basic usage examples will be added here
```

### Gas Station Module

```kotlin
// Gas station usage examples will be added here
```

### NFT Module

```kotlin
// NFT usage examples will be added here
```

### Smart Account Module

```kotlin
// Smart account usage examples will be added here
```

## Requirements

- Android API level 24+ (Android 7.0)
- Java 11+
- Kotlin

## License

MIT License

## Contributing

Please read our contributing guidelines before submitting pull requests.

## Support

For support, please open an issue on GitHub or contact us at contact@altude.com