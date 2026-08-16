package com.altude.gasstation

import android.content.Context
import com.altude.core.config.SdkConfig
import com.altude.core.keys.LocalKeyManager
import com.altude.core.model.TransactionSigner
import kotlin.coroutines.cancellation.CancellationException

/**
 * AltudeGasStation provides the primary SDK initialization API.
 *
 * By default, Core creates or reuses an encrypted on-device signer. Applications
 * can optionally provide a custom [TransactionSigner] for hardware, KMS, or other
 * advanced signing requirements.
 *
 * Typical usage:
 * ```
 * AltudeGasStation.init(context, apiKey)
 * // Then use the Altude object to send transactions.
 * ```
 */
object AltudeGasStation {

    /**
     * Initialize AltudeGasStation with the given API key.
     *
     * Flow:
     * 1. Initialize Core SDK services (SdkConfig, StorageService).
     * 2. Register [signer], or create/reuse Core's encrypted local signer.
     *
     * @param context Application context
     * @param apiKey Altude Gas Station API key for authentication
     * @param signer Optional custom signer. When omitted, Core manages a local signer.
     * @return Result indicating success or failure
     */
    suspend fun init(
        context: Context,
        apiKey: String,
        signer: TransactionSigner? = null
    ): Result<Unit> {
        return try {
            SdkConfig.setApiKey(context, apiKey)
            SdkConfig.setSigner(signer ?: LocalKeyManager(context).getOrCreateDefaultSigner())
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
