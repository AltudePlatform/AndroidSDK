package com.altude.vault.crypto

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import com.altude.vault.model.BiometricAuthenticationFailedException
import com.altude.vault.model.BiometricInvalidatedException
import com.altude.vault.model.BiometricNotAvailableException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Handles biometric/device credential authentication for vault operations.
 * Wraps AndroidX BiometricPrompt with coroutine support.
 *
 * Authentication flow:
 * 1. Check availability (throws BiometricNotAvailableException if not set up)
 * 2. Show prompt to user
 * 3. User completes authentication
 * 4. Call callback with encrypted data to decrypt
 */
object BiometricHandler {

    /**
     * Prompt user for biometric/device credential authentication.
     * This is a suspending function that shows the system biometric UI.
     *
     * @param activity FragmentActivity hosting the prompt (required for BiometricPrompt)
     * @param onSuccess Callback to execute after successful authentication
     * @return Result of the callback (typically decrypted data)
     * @throws BiometricNotAvailableException if biometric not set up
     * @throws BiometricInvalidatedException if enrollment changed
     * @throws BiometricAuthenticationFailedException if user cancelled or auth failed
     */
    suspend fun <T> authenticate(
        activity: FragmentActivity,
        title: String = "Confirm Your Identity",
        subtitle: String = "",
        description: String = "Use your biometric or device credential to sign transaction",
        onSuccess: () -> T
    ): T {
        // Check availability — throws BiometricNotAvailableException only when
        // BOTH BIOMETRIC_STRONG|DEVICE_CREDENTIAL and DEVICE_CREDENTIAL alone fail.
        // On Android 9 (API 28), canAuthenticate(DEVICE_CREDENTIAL) returns
        // BIOMETRIC_ERROR_UNSUPPORTED, so we catch that case and let the system
        // prompt handle it (it shows a PIN/password dialog natively).
        try {
            checkBiometricAvailability(activity)
        } catch (e: BiometricNotAvailableException) {
            // Re-throw only if device genuinely has no screen lock at all.
            // BIOMETRIC_ERROR_UNSUPPORTED on older Android = API limitation, not
            // a real absence of screen lock — fall through to let the prompt show.
            val msg = e.message ?: ""
            if (!msg.contains("unsupported", ignoreCase = true) &&
                !msg.contains("code: 12", ignoreCase = true)   // BIOMETRIC_ERROR_UNSUPPORTED = 12
            ) {
                throw e
            }
            // else: fall through — the BiometricPrompt will handle it
        }

        return suspendCancellableCoroutine { continuation ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)

                    val exception = when (errorCode) {
                        BiometricPrompt.ERROR_NO_BIOMETRICS,
                        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                        BiometricPrompt.ERROR_HW_UNAVAILABLE -> {
                            BiometricNotAvailableException("Biometric hardware unavailable or not configured")
                        }

                        BiometricPrompt.ERROR_UNABLE_TO_PROCESS -> {
                            BiometricAuthenticationFailedException(failureReason = BiometricAuthenticationFailedException.FailureReason.Invalid, message = "Biometric data invalid or signal too noisy")
                        }

                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                            BiometricAuthenticationFailedException(failureReason = BiometricAuthenticationFailedException.FailureReason.UserCancelled, message = "Authentication cancelled by user")
                        }

                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
                            BiometricAuthenticationFailedException(failureReason = BiometricAuthenticationFailedException.FailureReason.TooManyAttempts, message = "Biometric locked out. Too many failed attempts. Retry after timeout or use device credential.")
                        }

                        else -> {
                            BiometricAuthenticationFailedException(failureReason = BiometricAuthenticationFailedException.FailureReason.Unknown, message = "Authentication error: $errString")
                        }
                    }

                    continuation.resumeWithException(exception)
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    try {
                        val successResult = onSuccess()
                        continuation.resume(successResult)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Note: This is called on every failed attempt, but user can retry.
                    // We don't fail here; only ERROR_LOCKOUT ends the session.
                }
            }

            val allowedAuthenticators =
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL

            val promptBuilder = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply {
                    if (subtitle.isNotEmpty()) setSubtitle(subtitle)
                }
                .setDescription(description)

            if (allowedAuthenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL == 0) {
                promptBuilder.setNegativeButtonText("Cancel")
            }

            val promptInfo = promptBuilder
                .setAllowedAuthenticators(allowedAuthenticators)
                .build()

            val biometricPrompt = BiometricPrompt(activity, callback)
            biometricPrompt.authenticate(promptInfo)
        }
    }

    /**
     * Check if biometric authentication is available and properly configured.
     *
     * @param context Application context
     * @throws BiometricNotAvailableException if not available
     * @throws BiometricInvalidatedException if previously set up but now invalidated
     */
    fun checkBiometricAvailability(context: Context) {
        val biometricManager = BiometricManager.from(context)

        // Strategy: accept PIN/pattern/password (DEVICE_CREDENTIAL) alone.
        // BIOMETRIC_STRONG (fingerprint/face) is preferred but not required.
        //
        // Why this matters:
        //   canAuthenticate(BIOMETRIC_STRONG | DEVICE_CREDENTIAL) returns
        //   BIOMETRIC_ERROR_NONE_ENROLLED on many devices when no fingerprint is
        //   enrolled — even if a PIN is set — because the combined check requires
        //   *both* to be enrollable, not just one.
        //
        //   We check DEVICE_CREDENTIAL first (sufficient for vault security),
        //   then try the combined check for best UX (fingerprint prompt).

        // 1. Try the combined authenticator (fingerprint/face + PIN fallback)
        val combinedResult = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        if (combinedResult == BiometricManager.BIOMETRIC_SUCCESS) return

        // 2. Fall back to DEVICE_CREDENTIAL only (PIN/pattern/password is enough)
        val credentialResult = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        if (credentialResult == BiometricManager.BIOMETRIC_SUCCESS) return

        // 3. Neither is available — use credentialResult as the definitive error code
        // (credentialResult tells us exactly why even PIN/pattern failed)
        val errorCode = credentialResult

        when (errorCode) {
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                throw BiometricNotAvailableException(
                    message = "No screen lock is set up on this device",
                    remediation = "A PIN, pattern, password, or fingerprint is required.\n\n" +
                            "1. Open Settings\n" +
                            "2. Navigate to Security > Screen Lock\n" +
                            "3. Set a PIN, pattern, password, or enroll a fingerprint\n" +
                            "4. Return to the app and tap Initialize again"
                )

            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                throw BiometricNotAvailableException(
                    "Biometric hardware is temporarily unavailable",
                    "Restart the device and try again"
                )

            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                throw BiometricNotAvailableException(
                    "No biometric hardware detected",
                    "This device does not support fingerprint/face authentication. " +
                            "Please set a PIN or pattern as screen lock."
                )

            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                throw BiometricNotAvailableException(
                    "A security update is required",
                    "Update your device software to use biometric authentication"
                )

            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED ->
                throw BiometricNotAvailableException(
                    "Biometric authentication is not supported on this device",
                    "Please set a PIN or pattern screen lock"
                )

            else ->
                throw BiometricNotAvailableException(
                    "Authentication unavailable (code: $errorCode)",
                    "Ensure a screen lock (PIN, pattern, password, or fingerprint) is set up in Settings > Security"
                )
        }
    }
}
