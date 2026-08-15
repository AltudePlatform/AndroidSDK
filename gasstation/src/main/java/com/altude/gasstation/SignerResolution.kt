package com.altude.gasstation

import com.altude.core.config.SdkConfig
import com.altude.core.model.TransactionSigner

/**
 * Resolve the [TransactionSigner] to use for a Gas Station operation.
 *
 * Resolution order is exactly:
 * 1. [overrideSigner] — an explicit per-call signer provided by the caller.
 * 2. [SdkConfig.currentSigner] — the app-provided signer registered via
 *    `AltudeGasStation.init()` / `Altude.setApiKey()` / `SdkConfig.setSigner()`.
 * 3. A clear error if neither is configured.
 *
 * There is no other fallback: the app-provided signer is never shadowed or
 * substituted with SDK-owned/derived key material (e.g. account-based storage lookups).
 * This function is `internal` so it can be exercised directly by unit tests.
 */
internal fun resolveSigner(overrideSigner: TransactionSigner? = null): TransactionSigner {
    if (overrideSigner != null) return overrideSigner

    val signer = SdkConfig.currentSigner
    requireNotNull(signer) {
        "No signer configured. Provide a TransactionSigner via AltudeGasStation.init(), " +
            "Altude.setApiKey(context, apiKey, signer), or SdkConfig.setSigner() before using SDK methods."
    }
    return signer
}
