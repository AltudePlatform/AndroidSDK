# Vault Testing Guide

Comprehensive testing suite for the Vault module and signer routing logic.

## Test Organization

### Unit Tests (JVM, No Android Runtime)

Located in `vault/src/test/java/` and `core/src/test/java/`:

| Test Class | File | Purpose |
|-----------|------|---------|
| **VaultCryptoTest** | `vault/src/test/java/.../crypto/VaultCryptoTest.kt` | HKDF key derivation, determinism, domain separation |
| **VaultSessionTest** | `vault/src/test/java/.../model/VaultSessionTest.kt` | Session TTL validation, expiration, remaining time |
| **VaultExceptionTest** | `vault/src/test/java/.../model/VaultExceptionTest.kt` | Error codes, exception hierarchy, messages |
| **SignerRoutingTest** | `core/src/test/java/.../config/SignerRoutingTest.kt` | Signer fallback chain, strategy selection, switching |
| **VaultStorageTest** | `vault/src/test/java/.../storage/VaultStorageTest.kt` | Encryption/decryption, file storage, permissions |

**Run all unit tests:**
```bash
./gradlew test
```

**Run specific test class:**
```bash
./gradlew vault:test --tests VaultCryptoTest
```

**Run specific test method:**
```bash
./gradlew vault:test --tests VaultCryptoTest.testHKDFDeterminism_SameSeedProducesSameKey
```

---

### Instrumentation Tests (Android Runtime, Real Device/Emulator)

Located in `vault/src/androidTest/java/`:

| Test Class | File | Purpose |
|-----------|------|---------|
| **VaultManagerIntegrationTest** | `vault/src/androidTest/.../manager/...` | Full vault init→unlock→sign flow |
| **BiometricHandlerTest** | `vault/src/androidTest/.../crypto/...` | BiometricPrompt integration, availability checks |
| **VaultSignerIntegrationTest** | `vault/src/androidTest/.../model/...` | Auth modes, session behavior, batch operations |
| **VaultSecurityBoundariesTest** | `vault/src/androidTest/.../security/...` | Security: no plaintext, key protection, permissions |

**Run all instrumentation tests:**
```bash
./gradlew connectedDebugAndroidTest
```

**Run specific test class:**
```bash
./gradlew vault:connectedDebugAndroidTest \
  --tests com.altude.vault.manager.VaultManagerIntegrationTest
```

**Run specific test method:**
```bash
./gradlew vault:connectedDebugAndroidTest \
  --tests com.altude.vault.manager.VaultManagerIntegrationTest.testCreateVault_SuccessfullyInitializesVault
```

---

## Test Setup & Requirements

### Unit Tests Setup

No special setup required. Unit tests use Mockito for mocking and JUnit4:

```bash
# Dependencies in vault/build.gradle.kts:
testImplementation("junit:junit:4.13.2")
testImplementation("org.mockito:mockito-core:4.8.1")
testImplementation("org.mockito.kotlin:mockito-kotlin:4.1.0")
```

### Instrumentation Tests Setup

**Required:**
- Android device (emulator or physical)
- API level 21+ (minSdkVersion)
- Biometric enrollment (for biometric tests) or device will skip them gracefully

**Recommended Setup:**
```bash
# Use Android Emulator with biometric support:
# API level 31+ (Pixel 5 image or higher)

# In Android emulator, enable biometric:
# 1. Settings > Biometrics > Fingerprint
# 2. Enroll test fingerprint

# Or use physical device with biometric
```

**Key Dependencies:**
```bash
# From vault/build.gradle.kts:
androidTestImplementation("androidx.test.ext:junit:1.1.5")
androidTestImplementation("androidx.test:runner:1.5.2")
androidTestImplementation("androidx.biometric:biometric:1.1.0")
```

---

## Test Coverage

### By Feature Area

**HKDF Key Derivation:**
- ✅ Deterministic key generation (same input → same output)
- ✅ Domain separation (different appId → different key)
- ✅ Wallet index isolation (index 0 vs 1 vs 2 differ)
- ✅ Large wallet indices (1M+)
- ✅ Different seed inputs produce different keys
- ✅ Key size validation (32 bytes for Ed25519)

**Session Management:**
- ✅ Valid session within TTL boundary
- ✅ Expired session after TTL
- ✅ Exact TTL boundary behavior
- ✅ Remaining time calculation accuracy
- ✅ Multiple sessions with different expiry times
- ✅ TTL configuration (45s default, configurable)

**Error Handling:**
- ✅ All 8 exception types defined
- ✅ Error codes unique and correct (VAULT-01xx through VAULT-05xx)
- ✅ Exception inheritance hierarchy
- ✅ Remediation messages present and helpful
- ✅ No secrets in error messages
- ✅ Exception cause preservation

**Signer Routing & Fallback:**
- ✅ Default signer is null before init
- ✅ Set/get current signer
- ✅ Multiple setSigner calls (last wins)
- ✅ SignerStrategy.VaultDefault and External variants
- ✅ InitOptions factory methods (.default(), .vault(), .custom(), .vaultNoBiometric())
- ✅ Fallback chain behavior
- ✅ No breaking changes to legacy API

**Biometric Flows (Integration):**
- ✅ Biometric availability detection
- ✅ BiometricPrompt display and message customization
- ✅ Successful authentication flow
- ✅ Failed authentication (retry allowed)
- ✅ User cancellation handling
- ✅ Lockout after 5 failed attempts (30 second timeout)
- ✅ Error mapping to VaultException codes
- ✅ Requires FragmentActivity (not base Context)

**Vault Initialization (Integration):**
- ✅ Successful vault creation
- ✅ Idempotent init (called twice → exception)
- ✅ Per-operation mode (always unlocks/prompts)
- ✅ Session mode with TTL caching
- ✅ Multi-wallet support (different indices)
- ✅ Crash recovery (vault persists across restarts)
- ✅ Thread-safe operations (Mutex lock)

**Transaction Signing (Integration):**
- ✅ Per-operation signing (each requires auth)
- ✅ Session-cached signing (no re-prompt within TTL)
- ✅ Batch operations with consistent session
- ✅ Session expiration and re-authentication
- ✅ Public key exposure (safe, not secret)
- ✅ Signature determinism (same message → same signature)
- ✅ Different messages produce different signatures

**Security Boundaries (Integration):**
- ✅ No plaintext seed storage
- ✅ Private storage location (/data/data)
- ✅ No private keys in logs
- ✅ No secrets in exception messages
- ✅ Keystore key not exportable
- ✅ Biometric required for decryption
- ✅ Vault excluded from automatic backup
- ✅ File permissions restrict access
- ✅ Session keys cleared on TTL expiry
- ✅ Error messages contain remediation guidance

---

## Running Tests in CI/CD

### GitHub Actions Example

```yaml
name: Vault Tests
on: [push]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
      
      - name: Run unit tests
        run: ./gradlew test

  instrumentation-tests:
    runs-on: ${{ matrix.os }}
    strategy:
      matrix:
        os: [ubuntu-latest]
        api-level: [31, 33, 34]
    
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
      
      - name: Setup Android emulator
        uses: ReactiveCircus/android-emulator-runner@v2
        with:
          api-level: ${{ matrix.api-level }}
          arch: x86_64
          script: |
            # Enable biometric in emulator
            adb shell settings put secure biometric_server_enabled true
            
            # Run tests
            ./gradlew connectedDebugAndroidTest
```

---

## Debugging Tests

### Unit Tests Debugging

```bash
# Run specific test with verbose output
./gradlew vault:test \
  --tests VaultCryptoTest.testHKDFDeterminism_SameSeedProducesSameKey \
  -i

# Debug with logging
./gradlew vault:test \
  --tests VaultCryptoTest \
  --debug
```

### Instrumentation Tests Debugging

```bash
# Connect device and run with logging
adb logcat > test_output.log &

./gradlew vault:connectedDebugAndroidTest \
  --tests com.altude.vault.manager.VaultManagerIntegrationTest

# View logs
grep -i vault test_output.log
```

---

## Test Execution Patterns

### Pattern 1: Happy Path (Success Case)

```kotlin
@Test
fun testSuccessCase() {
    // Given: Setup preconditions
    val vault = VaultManager.getInstance()
    
    // When: Call vault operation
    vault.createVault(context, appId)
    
    // Then: Verify result
    assertTrue("Should succeed", true)
}
```

### Pattern 2: Error Handling

```kotlin
@Test
fun testErrorCase() {
    // When: Operation fails
    try {
        vaultManager.unlockVault(context, "invalid_app")
        fail("Should throw exception")
    } catch (e: VaultLockedException) {
        // Then: Catch expected exception
        assertEquals(VaultErrorCodes.VAULT_LOCKED, e.errorCode)
    }
}
```

### Pattern 3: Device-Specific (Biometric-Conditional)

```kotlin
@Test
fun testBiometricFeature() {
    val available = BiometricHandler.isBiometricAvailable(context)
    if (available) {
        // Run test on devices with biometric
        // ... test biometric flow
    } else {
        // Skip on devices without biometric
        println("Test skipped: No biometric")
    }
}
```

### Pattern 4: Async/Coroutine Testing

```kotlin
@Test
fun testAsyncOperation() {
    runBlocking {
        // Given: Async setup
        val vault = VaultManager.getInstance()
        
        // When: Call async vault operation
        val session = vault.unlockVault(context, appId, 0, 45)
        
        // Then: Verify result
        assertNotNull(session)
    }
}
```

---

## Known Test Limitations

### Instrumentation Tests

1. **Biometric Testing**: Tests gracefully skip on devices without biometric enrollment
   - Cannot fully mock BiometricPrompt system component
   - Requires manual user interaction or biometric simulator app

2. **Keystore Access**: Tests use real Android Keystore
   - Cannot be fully isolated in unit tests
   - Requires instrumentation tests with real context

3. **File System**: Tests create real encrypted files
   - Cleanup in @Before and @After
   - May leave files if tests crash (manual cleanup: `adb shell pm clear com.example`)

4. **Device-Specific**: Test results vary by device
   - API level differences
   - Manufacturer customizations (Samsung biometric vs Pixel)
   - Keystore implementation variations

---

## Test Data & Fixtures

### Test Seeds

All tests use deterministic test seeds (32 bytes):

```kotlin
val testSeed = byteArrayOf(
    1, 2, 3, ..., 32  // Fixed for reproducibility
)
```

### Test AppIds

- Unit tests: Use any string (e.g., "com.example.app")
- Instrumentation tests: Use "com.altude.vault.test" to avoid conflicts

### Test Keypairs

Generated via `KeyPair.newInstance()` (random, throwaway)

---

## Coverage Goals

**Target Coverage:**
- Unit tests: 85%+ of utility code (crypto, sessions, exceptions)
- Instrumentation tests: 80%+ of integration logic (vault manager, signers)
- Security tests: 100% of security-critical paths

**How to Check Coverage:**

```bash
# Generate coverage report
./gradlew testDebugUnitTestCoverage

# View report
open app/build/reports/coverage/index.html
```

---

## Continuous Improvement

### Adding New Tests

1. Identify business requirement or bug
2. Write failing test first (TDD)
3. Implement code to pass test
4. Verify no regressions
5. Add instrumentation test for integration

### Updating Tests

1. If changing error codes → update VaultExceptionTest
2. If changing HKDF format → update VaultCryptoTest
3. If changing signer API → update SignerRoutingTest
4. If changing auth flow → update BiometricHandlerTest + VaultSignerIntegrationTest

---

## Troubleshooting

### Common Issues

**"BiometricNotAvailableException" in tests**
- Device doesn't have biometric enrolled
- Solution: Enroll fingerprint in Settings, or device will gracefully skip test

**"VaultAlreadyInitializedException" between tests**
- Previous test didn't clean up vault
- Solution: Verify @Before cleanupTestVault() is called

**"Context is not FragmentActivity"**
- Test passed wrong context type
- Solution: Use `context as FragmentActivity` or cast properly

**"Instrumentation tests won't run"**
- No device/emulator connected
- Solution: `adb devices` to verify, or start emulator first

---

## Best Practices

✅ **DO:**
- Test both happy path and error cases
- Use meaningful test names (testX_ConditionY_ExpectZ)
- Clean up resources in @Before/@After
- Run tests locally before pushing
- Add comments for complex test logic
- Handle device-specific behavior gracefully

❌ **DON'T:**
- Log sensitive data in tests
- Create hard dependencies between tests
- Mock framework classes (BiometricPrompt)
- Ignore exceptions without explanation
- Leave test files on device after test
- Test internal implementation (test behavior)

---

## Next Steps

After unit & instrumentation tests:

1. ✅ **Phase 5 Complete**: All tests written and passing
2. ⏳ **Sample App**: Create example activity using VaultSigner
3. ⏳ **E2E Tests**: Full app flow from init → transaction → sign
4. ⏳ **Performance Tests**: Benchmark signing speed, battery impact
5. ⏳ **Security Audit**: Third-party review of security implementation

---

## Resources

- [Android Testing Documentation](https://developer.android.com/training/testing)
- [JUnit4 Testing](https://junit.org/junit4/)
- [Mockito Documentation](https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html)
- [AndroidX Test Documentation](https://developer.android.com/training/testing/local-tests)
- [Kotlin Coroutines Testing](https://kotlinlang.org/docs/coroutine-context-and-dispatchers.html#testing)

