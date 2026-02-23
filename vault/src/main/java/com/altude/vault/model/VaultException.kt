package com.altude.vault.model

/**
 * Error codes for Vault exceptions.
 * Format: VAULT-XXXX where first 2 digits = category, last 2 = specific error
 *
 * Categories:
 * - 01xx: Initialization errors
 * - 02xx: Biometric/authentication errors
 * - 03xx: Storage/encryption errors
 * - 04xx: Session/runtime errors
 * - 05xx: Configuration errors
 */
object VaultErrorCodes {
    // Initialization (01xx)
    const val INIT_FAILED = "VAULT-0101"
    const val INIT_PERMISSION_DENIED = "VAULT-0102"
    const val INIT_INSUFFICIENT_STORAGE = "VAULT-0103"
    const val ALREADY_INITIALIZED = "VAULT-0104"

    // Biometric (02xx)
    const val BIOMETRIC_UNAVAILABLE = "VAULT-0201"
    const val BIOMETRIC_INVALIDATED = "VAULT-0202"
    const val BIOMETRIC_AUTH_FAILED = "VAULT-0203"
    const val BIOMETRIC_LOCKOUT = "VAULT-0204"
    const val BIOMETRIC_CANCELLED = "VAULT-0205"

    // Storage/Encryption (03xx)
    const val DECRYPTION_FAILED = "VAULT-0301"
    const val ENCRYPTION_FAILED = "VAULT-0302"
    const val STORAGE_CORRUPTED = "VAULT-0303"
    const val KEYSTORE_ERROR = "VAULT-0304"

    // Session/Runtime (04xx)
    const val VAULT_LOCKED = "VAULT-0401"
    const val SESSION_EXPIRED = "VAULT-0402"
    const val INVALID_CONTEXT = "VAULT-0403"

    // Configuration (05xx)
    const val INVALID_CONFIG = "VAULT-0501"
    const val INCOMPATIBLE_VERSION = "VAULT-0502"
}

/**
 * Base exception for Vault-related errors.
 * All Vault exceptions include:
 * - Error code (VAULT-XXXX format)
 * - Clear error message (what went wrong)
 * - Remediation steps (how to fix it)
 * - Root cause (for debugging)
 *
 * @param errorCode Unique error code (VaultErrorCodes)
 * @param message Human-readable error message
 * @param remediation Developer-friendly remediation steps
 * @param cause Root cause exception (if applicable)
 */
open class VaultException(
    val errorCode: String,
    message: String,
    val remediation: String = "",
    cause: Throwable? = null
) : Exception("[$errorCode] $message", cause) {
    override fun toString(): String {
        return "VaultException(code=$errorCode, message=$message, remediation=$remediation)"
    }
}

/**
 * Thrown when biometric/device credential is unavailable or not set up on the device.
 * This is a critical failure - no fallback to insecure storage is provided.
 *
 * Error Code: VAULT-0201
 *
 * Root Causes:
 * - User hasn't enrolled any biometric (fingerprint, face)
 * - User hasn't set device PIN/pattern/password
 * - Device doesn't support biometric hardware
 *
 * User Actions:
 * - Enroll fingerprint or face in device Settings
 * - Set device PIN, pattern, or password
 * - (Admin) Enable biometric support in device settings
 *
 * Developer Remediation:
 * - Show user a dialog with action "Open Settings"
 * - Launch biometric enrollment flow
 */
class BiometricNotAvailableException(
    message: String = "Biometric authentication is not available on this device",
    remediation: String = "1. Open Settings\n2. Navigate to Security > Biometric (or Screen Lock)\n3. Enroll fingerprint, face, or set PIN\n4. Return to app and retry",
    cause: Throwable? = null
) : VaultException(
    errorCode = VaultErrorCodes.BIOMETRIC_UNAVAILABLE,
    message = message,
    remediation = remediation,
    cause = cause
)

/**
 * Thrown when previously enrolled biometric is no longer valid.
 * Keys enrolled with old biometric are invalidated by Android for security.
 *
 * Error Code: VAULT-0202
 *
 * Root Causes:
 * - User added new fingerprints (old key invalidated)
 * - User changed face recognition (old key invalidated)
 * - User changed PIN/pattern/password (old key invalidated)
 * - Biometric sensor was physically replaced/updated
 * - Android security update affected key storage
 *
 * Why it happens:
 * Android invalidates keys for security when the authentication method changes.
 * This prevents unlimited key reuse if biometric is compromised.
 *
 * Recovery Options:
 * 1. Clear app data + re-initialize vault (loses existing keys)
 * 2. Uninstall and reinstall app (clean slate)
 * 3. Export private key if available, restore manually
 *
 * Developer Remediation:
 * - Show clear error message explaining the situation
 * - Provide buttons: "Clear App Data" or "Reinstall App"
 * - For sensitive apps, recommend exporting backup first
 */
class BiometricInvalidatedException(
    message: String = "Biometric credentials have been invalidated (likely due to new enrollment)",
    remediation: String = "Your stored encryption keys are no longer accessible for security.\n\n" +
            "Options:\n" +
            "1. Clear App Data (lose current vault)\n" +
            "2. Uninstall & Reinstall\n" +
            "3. (Advanced) Export/restore backup\n\n" +
            "This is a security feature to prevent unauthorized key access.",
    cause: Throwable? = null
) : VaultException(
    errorCode = VaultErrorCodes.BIOMETRIC_INVALIDATED,
    message = message,
    remediation = remediation,
    cause = cause
)

/**
 * Thrown when user cancels biometric prompt or authentication fails.
 * This is a transient error that can typically be retried.
 *
 * Error Code: VAULT-0203 or VAULT-0205
 *
 * Root Causes (Failed):
 * - Incorrect fingerprint/face match
 * - User held finger/face too briefly
 * - User had dirty/wet fingers
 * - Face not recognized due to poor lighting
 * - Multiple failed attempts (auto-rejected)
 *
 * Root Causes (Cancelled):
 * - User tapped "Cancel" button
 * - User pressed back button
 * - App backgrounded during prompt
 *
 * Retryability: YES - user can retry immediately
 *
 * Developer Remediation:
 * - Show "Try Again" button for failed auth
 * - Don't require app restart
 * - Allow user to try device credential as fallback
 * - Log attempt count for debugging
 */
class BiometricAuthenticationFailedException(
    val failureReason: FailureReason = FailureReason.Unknown,
    message: String = "Biometric authentication failed or was cancelled",
    remediation: String = "Try again or use another authentication method on your device",
    cause: Throwable? = null
) : VaultException(
    errorCode = if (failureReason == FailureReason.UserCancelled) {
        VaultErrorCodes.BIOMETRIC_CANCELLED
    } else {
        VaultErrorCodes.BIOMETRIC_AUTH_FAILED
    },
    message = message,
    remediation = remediation,
    cause = cause
) {
    enum class FailureReason {
        UserCancelled,
        AuthenticationFailed,
        TooManyAttempts,
        Invalid,
        Unknown
    }
}

/**
 * Thrown when vault operations are attempted but vault is not initialized or session expired.
 *
 * Error Code: VAULT-0401 or VAULT-0402
 *
 * Root Causes:
 * - Vault not initialized (AltudeGasStation.init not called)
 * - Session TTL expired
 * - App crashed/restarted (session cleared)
 * - User manually locked vault
 *
 * Developer Remediation:
 * - Call AltudeGasStation.init() if not done
 * - For expired session: retry transaction (will re-prompt biometric)
 * - For manual lock: call AltudeGasStation.lockVault() or app restart
 */
class VaultLockedException(
    message: String = "Vault is locked or not initialized",
    remediation: String = "Call AltudeGasStation.init(context, apiKey) to initialize the vault",
    cause: Throwable? = null
) : VaultException(
    errorCode = VaultErrorCodes.VAULT_LOCKED,
    message = message,
    remediation = remediation,
    cause = cause
)

/**
 * Thrown when vault initialization fails.
 *
 * Error Code: VAULT-0101
 *
 * Root Causes:
 * - Insufficient storage space
 * - No write permission to app files directory
 * - SD card ejected or unmounted
 * - Keystore unavailable
 * - Device disk temporarily full
 *
 * Developer Remediation:
 * - Check available device storage (clear cache if needed)
 * - Verify app has WRITE_EXTERNAL_STORAGE permission (if using external storage)
 * - Ensure SD card is mounted (if using)
 * - Retry after freeing space
 * - For persistent issues, uninstall and reinstall app
 */
class VaultInitFailedException(
    message: String = "Failed to initialize vault",
    remediation: String = "1. Check device storage space (Settings > Storage)\n" +
            "2. Free up space if needed\n" +
            "3. Verify app permissions (Settings > Apps > [App Name] > Permissions)\n" +
            "4. Restart the app\n" +
            "5. If problem persists, uninstall and reinstall",
    cause: Throwable? = null
) : VaultException(
    errorCode = VaultErrorCodes.INIT_FAILED,
    message = message,
    remediation = remediation,
    cause = cause
)

/**
 * Thrown when vault decryption fails.
 *
 * Error Code: VAULT-0301
 *
 * Root Causes:
 * - Vault data corrupted (filesystem error)
 * - App was downgraded (newer format not compatible)
 * - Keystore data corrupted
 * - Biometric data corrupted
 * - App data partially cleared
 *
 * Developer Remediation:
 * - Check device storage status
 * - Verify Android version compatibility
 * - For app downgrade: upgrade app to compatible version
 * - If unrecoverable: clear app data and reinitialize
 */
class VaultDecryptionFailedException(
    message: String = "Failed to decrypt vault data",
    remediation: String = "Vault data may be corrupted or incompatible.\n\n" +
            "Options:\n" +
            "1. Upgrade app to latest version\n" +
            "2. Clear App Data and reinitialize vault\n" +
            "3. Restart device\n" +
            "4. Uninstall and reinstall app\n\n" +
            "Note: Clearing app data will lose your vault keys.",
    cause: Throwable? = null
) : VaultException(
    errorCode = VaultErrorCodes.DECRYPTION_FAILED,
    message = message,
    remediation = remediation,
    cause = cause
)

/**
 * Thrown when trying to create a vault that already exists.
 *
 * Error Code: VAULT-0104
 *
 * Root Causes:
 * - AltudeGasStation.init() called multiple times
 * - Race condition (concurrent init attempts)
 *
 * Developer Remediation:
 * - Ensure AltudeGasStation.init() is called only once during app initialization
 * - Use dependency injection to ensure singleton initialization
 * - If initializing in multiple places, use idempotent pattern:
 *   if (!VaultManager.vaultExists(context, appId)) { init... }
 */
class VaultAlreadyInitializedException(
    message: String = "Vault is already initialized",
    remediation: String = "Vault was already created. To use it:\n" +
            "1. Use existing vault - don't call init again\n" +
            "2. To start fresh: clear app data first\n\n" +
            "Best practice: Call AltudeGasStation.init() only once, in onCreate() of Application class or MainActivity",
    cause: Throwable? = null
) : VaultException(
    errorCode = VaultErrorCodes.ALREADY_INITIALIZED,
    message = message,
    remediation = remediation,
    cause = cause
)

/**
 * Thrown when signer/vault configuration is invalid.
 *
 * Error Code: VAULT-0501
 *
 * Root Causes:
 * - Invalid session TTL (not positive)
 * - Invalid wallet index (negative)
 * - Empty or invalid appId
 * - Context is not FragmentActivity
 *
 * Developer Remediation:
 * - Verify configuration values
 * - Ensure sessionTTLSeconds > 0
 * - Ensure walletIndex >= 0
 * - Pass Context as FragmentActivity (not base Context)
 */
class VaultConfigurationException(
    message: String = "Invalid Vault configuration",
    remediation: String = "Check configuration values:\n" +
            "- sessionTTLSeconds must be > 0\n" +
            "- walletIndex must be >= 0\n" +
            "- appId must not be empty\n" +
            "- context must be FragmentActivity (not base Context)",
    cause: Throwable? = null
) : VaultException(
    errorCode = VaultErrorCodes.INVALID_CONFIG,
    message = message,
    remediation = remediation,
    cause = cause
)
