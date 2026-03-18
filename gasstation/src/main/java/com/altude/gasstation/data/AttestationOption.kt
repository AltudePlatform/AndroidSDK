package com.altude.gasstation.data

/**
 * Options for creating an on-chain attestation using the
 * Solana Attestation Service (SAS) program.
 *
 * Usage:
 * ```kotlin
 * Altude.attest(
 *     AttestationOption(
 *         schemaId  = "schema_pubkey_base58",
 *         recipient = "recipient_wallet_base58",
 *         data      = """{"kyc": true, "level": 2}""".encodeToByteArray()
 *     )
 * )
 * ```
 *
 * @param account      Attester wallet. Leave blank to use the current signer.
 * @param schemaId     The SAS Schema account pubkey (Base58) that describes this attestation.
 * @param recipient    The account being attested (Base58 pubkey). Defaults to attester if blank.
 * @param data         Arbitrary bytes to store in the attestation (e.g. JSON-encoded claim data).
 *                     Must not exceed the schema's declared max size.
 * @param expireAt     Unix timestamp (seconds) when the attestation expires. 0 = no expiry.
 * @param nonce        Optional unique nonce to allow multiple attestations per (attester, schema, recipient).
 * @param commitment   Finality commitment to wait for.
 */
data class AttestationOption(
    val account: String = "",
    val schemaId: String,
    val recipient: String = "",
    val data: ByteArray = ByteArray(0),
    val expireAt: Long = 0L,
    val nonce: String = "",
    val commitment: Commitment = Commitment.finalized
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AttestationOption) return false
        return account == other.account &&
            schemaId == other.schemaId &&
            recipient == other.recipient &&
            data.contentEquals(other.data) &&
            expireAt == other.expireAt &&
            nonce == other.nonce &&
            commitment == other.commitment
    }

    override fun hashCode(): Int {
        var result = account.hashCode()
        result = 31 * result + schemaId.hashCode()
        result = 31 * result + recipient.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + expireAt.hashCode()
        result = 31 * result + nonce.hashCode()
        result = 31 * result + commitment.hashCode()
        return result
    }
}

/**
 * Options for revoking a previously created on-chain attestation.
 *
 * @param account       Attester wallet that originally created the attestation. Blank = current signer.
 * @param attestationId The on-chain Attestation account pubkey (Base58) to revoke.
 * @param commitment    Finality commitment to wait for.
 */
data class RevokeAttestationOption(
    val account: String = "",
    val attestationId: String,
    val commitment: Commitment = Commitment.finalized
)

/**
 * Options for creating a new SAS Schema.
 *
 * A Schema defines the structure (and optional validation rules) of attestation data.
 * Create a schema once; reuse its pubkey in every [AttestationOption].
 *
 * @param account      Schema authority wallet. Blank = current signer.
 * @param name         Short human-readable schema name (max 32 chars).
 * @param description  Description of what this schema attests.
 * @param fieldNames   Ordered list of field names matching the encoded data fields.
 * @param isRevocable  Whether attestations using this schema can be revoked.
 * @param commitment   Finality commitment.
 */
data class CreateSchemaOption(
    val account: String = "",
    val name: String,
    val description: String = "",
    val fieldNames: List<String> = emptyList(),
    val isRevocable: Boolean = true,
    val commitment: Commitment = Commitment.finalized
)

