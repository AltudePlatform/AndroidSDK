package com.altude.gasstation

import android.content.Context
import com.altude.core.config.SdkConfig
import com.altude.core.model.TransactionSigner
import kotlin.coroutines.cancellation.CancellationException

/**
 * AltudeGasStation provides the primary SDK initialization API.
 *
 * The application owns key custody: the SDK never generates, stores, or defaults to a
 * signer on your behalf. You must construct and provide a [TransactionSigner] implementation
 * (for example [com.altude.core.model.HotSigner], a hardware-backed signer, or your own
 * wallet/KMS integration) before Gas Station can authorize transactions.
 *
 * Typical usage:
 * ```
 * val signer: TransactionSigner = MyAppOwnedSigner(...)
 * AltudeGasStation.init(context, apiKey, signer)
 * // Then use the Altude object to send transactions.
 * ```
 */
object AltudeGasStation {

    /**
     * Initialize AltudeGasStation with the given API key and an application-provided signer.
     *
     * Flow:
     * 1. Initialize Core SDK services (SdkConfig, StorageService).
     * 2. Register the provided [signer] as the default signer for subsequent operations.
     *
     * The SDK does not generate or persist any key material as part of initialization —
     * [signer] must already be fully configured and ready to sign when this is called.
     *
     * @param context Application context
     * @param apiKey Altude Gas Station API key for authentication
     * @param signer Application-provided [TransactionSigner] used as the default signer
     * @return Result indicating success or failure
     */
    suspend fun init(
        context: Context,
        apiKey: String,
        signer: TransactionSigner
    ): Result<Unit> {
        return try {
            SdkConfig.setApiKey(context, apiKey)
            SdkConfig.setSigner(signer)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
