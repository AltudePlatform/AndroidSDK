package com.altude.vault.model

/**
 * Thrown when the vault's encrypted keyset (Tink SharedPreferences) is stale or
 * mismatched with the encrypted vault file — typically caused by:
 * - App reinstall (SharedPreferences cleared but filesDir file remained, or vice versa)
 * - Partial "Clear App Data" operation
 * - Android Keystore master key rotation
 *
 * Error Code: VAULT-0305
 *
 * Recovery:
 * The stale encrypted file and SharedPreferences keyset have already been purged
 * by VaultStorage.  The caller should call init() again to create a fresh vault.
 * AltudeGasStation.init() handles this automatically with a single retry.
 */
class VaultStorageCorruptedException(
    message: String = "Vault storage keyset is stale or corrupted (AEADBadTagException). " +
            "Stale files have been purged — please re-initialize the vault.",
    remediation: String = "The vault encryption keyset no longer matches the stored data.\n\n" +
            "This typically happens after:\n" +
            "  • App reinstall (data partially cleared)\n" +
            "  • Partial 'Clear App Data'\n" +
            "  • Keystore master key rotation\n\n" +
            "The corrupted files have been deleted automatically.\n" +
            "Call AltudeGasStation.init() again to create a fresh vault.",
    cause: Throwable? = null
) : VaultException(
    errorCode = VaultErrorCodes.STALE_KEYSET,
    message = message,
    remediation = remediation,
    cause = cause
)

