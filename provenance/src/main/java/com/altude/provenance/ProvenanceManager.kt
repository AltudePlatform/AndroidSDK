package com.altude.provenance

import android.util.Base64
import com.altude.core.Programs.AttestationProgram
import com.altude.core.config.SdkConfig
import com.altude.core.helper.Mnemonic
import com.altude.core.model.AltudeTransactionBuilder
import com.altude.core.model.HotSigner
import com.altude.core.network.AltudeRpc
import com.altude.core.service.StorageService
import com.altude.provenance.data.ImageHashPayload
import foundation.metaplex.solana.transactions.SerializeConfig
import foundation.metaplex.solanaeddsa.Keypair
import foundation.metaplex.solanaeddsa.SolanaEddsa
import foundation.metaplex.solanapublickeys.PublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Internal transaction builder for image-hash provenance operations.
 *
 * Builds and partially signs both the `createSchema` and `createAttestation`
 * transactions, then returns Base64-encoded serialized transactions to be
 * forwarded to the backend.
 */
internal object ProvenanceManager {

    /** Fixed schema name used for all image-hash attestations. */
    const val SCHEMA_NAME = "image_hash"

    private val rpc get() = AltudeRpc(SdkConfig.apiConfig.RpcUrl)
    private val feePayerPubKey get() = PublicKey(SdkConfig.apiConfig.FeePayer)

    // ── Keypair resolution ────────────────────────────────────────────────────

    suspend fun getKeyPair(account: String = ""): Keypair {
        val seedData = StorageService.getDecryptedSeed(account)
        if (seedData != null) {
            if (seedData.type == "mnemonic") return Mnemonic(seedData.mnemonic).getKeyPair()
            return if (seedData.privateKey != null)
                SolanaEddsa.createKeypairFromSecretKey(seedData.privateKey!!.copyOfRange(0, 32))
            else throw Error("No seed found in storage")
        } else throw Error("Please set seed first")
    }

    // ── PDA helpers ───────────────────────────────────────────────────────────

    /**
     * Derives the Schema PDA for the fixed [SCHEMA_NAME] and the given [account].
     * Keeps [AttestationProgram] fully hidden from callers outside this class.
     */
    internal suspend fun deriveSchemaAddress(account: String = ""): PublicKey {
        val keypair = getKeyPair(account)
        return AttestationProgram.deriveSchemaAddress(
            authority = keypair.publicKey,
            name      = SCHEMA_NAME
        )
    }

    // ── Schema ────────────────────────────────────────────────────────────────

    /**
     * Builds and signs a `createSchema` transaction for the fixed [SCHEMA_NAME] schema.
     * Returns the Base64-encoded serialized transaction.
     */
    suspend fun createSchema(payload: ImageHashPayload): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val keypair   = getKeyPair(payload.account)
                val authority = keypair.publicKey
                val hotSigner = HotSigner(keypair)

                val instruction = AttestationProgram.createSchema(
                    authority   = authority,
                    feePayer    = feePayerPubKey,
                    name        = SCHEMA_NAME,
                    description = "Stores SHA-256 hash of images",
                    fieldNames  = listOf("type", "hash", "mime", "name", "timestamp"),
                    isRevocable = true
                )

                val blockhash = rpc.getLatestBlockhash(
                    commitment = payload.commitment.name
                ).blockhash

                val tx = AltudeTransactionBuilder()
                    .setFeePayer(feePayerPubKey)
                    .setRecentBlockHash(blockhash)
                    .addInstruction(instruction)
                    .setSigners(listOf(hotSigner))
                    .build()

                val serialized = Base64.encodeToString(
                    tx.serialize(SerializeConfig(requireAllSignatures = false)),
                    Base64.NO_WRAP
                )
                Result.success(serialized)
            } catch (e: Throwable) {
                Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
            }
        }

    // ── Attestation ───────────────────────────────────────────────────────────

    /**
     * Builds and signs a `createAttestation` transaction for an [ImageHashPayload].
     * The [schemaPda] must be derived beforehand via [AttestationProgram.deriveSchemaAddress].
     * Returns the Base64-encoded serialized transaction.
     */
    suspend fun attest(
        payload: ImageHashPayload,
        schemaPda: PublicKey
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val keypair   = getKeyPair(payload.account)
            val attester  = keypair.publicKey
            val hotSigner = HotSigner(keypair)
            val recipient = if (payload.recipient.isBlank()) attester
                            else PublicKey(payload.recipient)

            // Structured JSON payload stored in the on-chain attestation
            val payloadJson = """
                {
                  "type": "${payload.type}",
                  "hash": "${payload.hash}",
                  "mime": "${payload.mime}",
                  "name": "${payload.name}",
                  "timestamp": ${payload.timestamp}
                }
            """.trimIndent()

            val instruction = AttestationProgram.createAttestation(
                attester        = attester,
                feePayer        = feePayerPubKey,
                schemaPda       = schemaPda,
                recipient       = recipient,
                attestationData = payloadJson.toByteArray(),
                expireAt        = payload.expireAt
            )

            val blockhash = rpc.getLatestBlockhash(
                commitment = payload.commitment.name
            ).blockhash

            val tx = AltudeTransactionBuilder()
                .setFeePayer(feePayerPubKey)
                .setRecentBlockHash(blockhash)
                .addInstruction(instruction)
                .setSigners(listOf(hotSigner))
                .build()

            val serialized = Base64.encodeToString(
                tx.serialize(SerializeConfig(requireAllSignatures = false)),
                Base64.NO_WRAP
            )
            Result.success(serialized)
        } catch (e: Throwable) {
            Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }
}
