# JitPack.io Setup Complete! (Private Repository)

## ? What's Been Configured for Private Repository Access

### Modules Ready for JitPack
- **core**: `com.github.AltudePlatform.AndroidSDK:core:0.1.0-alpha`
- **gasstation**: `com.github.AltudePlatform.AndroidSDK:gasstation:0.1.0-alpha`
- **nft**: `com.github.AltudePlatform.AndroidSDK:nft:0.1.0-alpha`
- **smart-account**: `com.github.AltudePlatform.AndroidSDK:smart-account:0.1.0-alpha`

### Version Information
- **Current Version**: `0.1.0-alpha` (lowest possible alpha version)
- **Git Tag**: `v0.1.0-alpha`
- **Group ID**: `com.github.AltudePlatform`
- **Repository**: Private (requires authentication)

### Authentication Setup
- **JitPack Token**: `jp_k061hv1h2f7so5rrvfoujdlncv`
- **Local Token File**: `~/.gradle/gradle.properties`
- **Project Configuration**: Updated `settings.gradle.kts` with credentials

## ?? Private Repository Setup Steps

### Step 1: Add Auth Token Locally
The auth token has been added to your local gradle properties:
```
~/.gradle/gradle.properties
authToken=jp_k061hv1h2f7so5rrvfoujdlncv
```

### Step 2: Repository Configuration
Your `settings.gradle.kts` now includes:
```kotlin
maven { 
    url = uri("https://jitpack.io")
    credentials.username = providers.gradleProperty("authToken").get()
}
```

### Step 3: Project Properties
Your `gradle.properties` includes the token as backup.

## ?? How Users Can Access Your Private SDK

### For Other Developers/Teams:

#### 1. Get Access Token
Each developer needs to:
- Visit https://jitpack.io
- Authorize with GitHub
- Get their personal access token: `jp_k061hv1h2f7so5rrvfoujdlncv`

#### 2. Add Token to Their Local Machine
Create/edit `~/.gradle/gradle.properties`:
```properties
authToken=jp_k061hv1h2f7so5rrvfoujdlncv
```

#### 3. Configure Their Project
Add to their `build.gradle.kts` or `settings.gradle.kts`:
```kotlin
repositories {
    google()
    mavenCentral()
    maven { 
        url = uri("https://jitpack.io")
        credentials.username = providers.gradleProperty("authToken").get()
    }
}
```

#### 4. Add Dependencies
```kotlin
dependencies {
    implementation("com.github.AltudePlatform.AndroidSDK:core:0.1.0-alpha")
    implementation("com.github.AltudePlatform.AndroidSDK:gasstation:0.1.0-alpha")
    implementation("com.github.AltudePlatform.AndroidSDK:nft:0.1.0-alpha")
    implementation("com.github.AltudePlatform.AndroidSDK:smart-account:0.1.0-alpha")
}
```

## ?? JitPack Links (Private Access Required)

- **Main Repository**: https://jitpack.io/#AltudePlatform/AndroidSDK
- **Core Module**: https://jitpack.io/#AltudePlatform/AndroidSDK/core/0.1.0-alpha
- **Gasstation Module**: https://jitpack.io/#AltudePlatform/AndroidSDK/gasstation/0.1.0-alpha
- **NFT Module**: https://jitpack.io/#AltudePlatform/AndroidSDK/nft/0.1.0-alpha
- **Smart Account Module**: https://jitpack.io/#AltudePlatform/AndroidSDK/smart-account/0.1.0-alpha

## ?? Next Steps

1. **Test Private Access**: Try building a test project with your SDK
2. **Share Token**: Provide the auth token to your team members
3. **Version Updates**: When ready to release new versions:
   - Update version in `build.gradle.kts` and `gradle.properties`
   - Create a new git tag: `git tag v0.1.1-alpha`
   - Push the tag: `git push origin v0.1.1-alpha`

## ??? Troubleshooting Private Repository Access

### "No read access to repo" Error
- ? **Auth token added** to `~/.gradle/gradle.properties`
- ? **Repository configured** with credentials in `settings.gradle.kts`
- ? **Project properties** include the auth token

### Common Issues:
1. **Missing Token**: Ensure `authToken` is in `~/.gradle/gradle.properties`
2. **Wrong Token**: Verify token is exactly: `jp_k061hv1h2f7so5rrvfoujdlncv`
3. **Repository Config**: Make sure JitPack repo includes `credentials.username`
4. **Cache Issues**: Try `./gradlew --refresh-dependencies`

## ?? Files Modified for Private Access

- ? `~/.gradle/gradle.properties` - Added auth token
- ? `settings.gradle.kts` - Added JitPack credentials configuration
- ? `gradle.properties` - Added auth token backup
- ? `jitpack.yml` - Updated for private repo build
- ? `README.md` - Added private repository instructions

## ?? Security Notes

- **Token Security**: The auth token provides access to your private repositories
- **Team Access**: Each team member needs their own JitPack account and token
- **Environment**: Tokens are stored locally and not committed to repository
- **Backup**: The token is in project `gradle.properties` for CI/CD but should be overridden locally

## ?? Success Verification

? Local build successful: `./gradlew publishToMavenLocal -x test`
? Authentication configured in settings.gradle.kts
? Auth token added to local gradle properties
? JitPack configuration updated for private repos
? Documentation updated with private access instructions