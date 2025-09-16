# JitPack.io Setup Complete! 

## ? What's Been Configured

### Modules Ready for JitPack
- **core**: `com.github.AltudePlatform.AndroidSDK:core:0.1.0-alpha`
- **gasstation**: `com.github.AltudePlatform.AndroidSDK:gasstation:0.1.0-alpha`
- **nft**: `com.github.AltudePlatform.AndroidSDK:nft:0.1.0-alpha`
- **smart-account**: `com.github.AltudePlatform.AndroidSDK:smart-account:0.1.0-alpha`

### Version Information
- **Current Version**: `0.1.0-alpha` (lowest possible alpha version)
- **Git Tag**: `v0.1.0-alpha`
- **Group ID**: `com.github.AltudePlatform`

## ?? How to Use

### 1. Add JitPack Repository
In your root `build.gradle.kts`:
```kotlin
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### 2. Add Dependencies
In your app's `build.gradle.kts`:
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

### 3. Alternative: Use Latest Commit
You can also use the latest commit instead of a specific version:
```kotlin
implementation("com.github.AltudePlatform.AndroidSDK:core:setup-maven-SNAPSHOT")
```

## ?? JitPack Links

- **Main Repository**: https://jitpack.io/#AltudePlatform/AndroidSDK
- **Version Badge**: [![](https://jitpack.io/v/AltudePlatform/AndroidSDK.svg)](https://jitpack.io/#AltudePlatform/AndroidSDK)
- **Core Module**: https://jitpack.io/#AltudePlatform/AndroidSDK/core/0.1.0-alpha
- **Gasstation Module**: https://jitpack.io/#AltudePlatform/AndroidSDK/gasstation/0.1.0-alpha
- **NFT Module**: https://jitpack.io/#AltudePlatform/AndroidSDK/nft/0.1.0-alpha
- **Smart Account Module**: https://jitpack.io/#AltudePlatform/AndroidSDK/smart-account/0.1.0-alpha

## ?? Next Steps

1. **Wait for JitPack Build**: JitPack will automatically build your libraries when someone first requests them
2. **Test Installation**: Try adding the dependencies to a test project
3. **Version Updates**: When ready to release a new version:
   - Update version in `build.gradle.kts` and `gradle.properties`
   - Create a new git tag: `git tag v0.1.1-alpha`
   - Push the tag: `git push origin v0.1.1-alpha`

## ?? Files Modified

- ? `build.gradle.kts` - Added publishing configuration
- ? `gradle.properties` - Added version and publishing properties
- ? `core/build.gradle.kts` - Added maven-publish plugin
- ? `gasstation/build.gradle.kts` - Added maven-publish plugin
- ? `nft/build.gradle.kts` - Added maven-publish plugin
- ? `smart-account/build.gradle.kts` - Added maven-publish plugin
- ? `settings.gradle.kts` - Added JitPack repository
- ? `jitpack.yml` - JitPack configuration file
- ? `README.md` - Updated with installation instructions
- ? `.gitignore` - Optimized for Android development

## ?? Notes

- This is an **alpha version** - APIs may change
- All modules are set to the **lowest possible version** (0.1.0-alpha) as requested
- JitPack builds are triggered on-demand when someone first requests a version
- Each module can be used independently or together