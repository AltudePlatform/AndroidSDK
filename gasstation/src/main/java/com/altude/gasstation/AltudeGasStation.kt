package com.altude.gasstation

import android.content.Context
import com.altude.core.config.SdkConfig
import com.altude.core.model.TransactionSigner
import kotlin.coroutines.cancellation.CancellationException

/**
 * AltudeGasStation provides the primary SDK initialization API.
 *
 * Applications must provide a custom [TransactionSigner] for hardware, KMS, or
 * other signing requirements.
 *
 * Typical usage:
 * ```
 * AltudeGasStation.init(context, apiKey, signer)
 * // Then use the Altude object to send transactions.
 * ```
 */
object AltudeGasStation {

    /**
     * Initialize AltudeGasStation with the given API key.
     *
     * Flow:
     * 1. Initialize Core SDK services (SdkConfig, StorageService).
     * 2. Register [signer].
     *
     * @param context Application context
     * @param apiKey Altude Gas Station API key for authentication
     * @param signer Application-owned signer.
     * @return Result indicating success or failure
     */
    suspend fun init(
        context: Context,
        apiKey: String,
        signer: TransactionSigner? = null
    ): Result<Unit> {
        return try {
            val resolvedSigner = signer
                ?: return Result.failure(IllegalArgumentException("Signer is required"))
            SdkConfig.setApiKey(context, apiKey)
            SdkConfig.setSigner(resolvedSigner)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
