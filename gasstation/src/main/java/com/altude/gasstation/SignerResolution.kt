package com.altude.gasstation

import com.altude.core.config.SdkConfig
import com.altude.core.model.TransactionSigner

/**
 * Resolve the [TransactionSigner] to use for a Gas Station operation.
 *
 * Resolution order is exactly:
 * 1. [overrideSigner] — an explicit per-call signer provided by the caller.
 * 2. [SdkConfig.currentSigner] — the Core-managed or custom signer registered via
 *    `AltudeGasStation.init()` / `Altude.setApiKey()` / `SdkConfig.setSigner()`.
 * 3. A clear error if neither is configured.
 *
 * There is no account-based lookup during an operation, so the configured signer
 * is deterministic and is never silently replaced based on request input.
 * This function is `internal` so it can be exercised directly by unit tests.
 */
internal fun resolveSigner(overrideSigner: TransactionSigner? = null): TransactionSigner {
    if (overrideSigner != null) return overrideSigner

    val signer = SdkConfig.currentSigner
    requireNotNull(signer) {
        "No signer configured. Call AltudeGasStation.init() or Altude.setApiKey() " +
            "before using SDK methods."
    }
    return signer
}
