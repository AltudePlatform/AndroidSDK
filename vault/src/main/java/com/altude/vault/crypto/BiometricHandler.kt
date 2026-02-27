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
        // First, check if biometric is available
        checkBiometricAvailability(activity)

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

        // Check if biometric is available
        val canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        when (canAuthenticate) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                // All good, biometric is available
                return
            }

            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                throw BiometricNotAvailableException(
                    "No biometric or device credential is set up",
                    "Go to Settings > Security > Biometric or Screen Lock to enable authentication"
                )
            }

            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                throw BiometricNotAvailableException(
                    "Biometric hardware is not available",
                    "This device does not support biometric authentication"
                )
            }

            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                throw BiometricNotAvailableException(
                    "No biometric hardware detected",
                    "This device does not support biometric authentication"
                )
            }

            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> {
                throw BiometricNotAvailableException(
                    "Biometric security update required",
                    "Update your device to use biometric authentication"
                )
            }

            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> {
                throw BiometricNotAvailableException(
                    "Biometric authentication is not supported",
                    "This device does not support biometric authentication"
                )
            }

            BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> {
                throw BiometricNotAvailableException(
                    "Biometric status unknown",
                    "Unable to determine biometric availability. Try again later."
                )
            }

            else -> {
                throw BiometricNotAvailableException(
                    "Biometric unavailable (code: $canAuthenticate)"
                )
            }
        }
    }
}
