package com.altude.gasstation

import android.content.Context
import com.altude.core.api.GetAccountInfoRequest
import com.altude.core.api.GetBalanceRequest
import com.altude.core.config.SdkConfig
import com.altude.core.api.TransactionService
import com.altude.gasstation.data.GetBalanceOption
import com.altude.gasstation.data.CloseAccountOption
import com.altude.gasstation.data.CreateAccountOption
import com.altude.gasstation.data.GetAccountInfoOption
import com.altude.gasstation.data.GetHistoryData
import com.altude.gasstation.data.GetHistoryOption
import com.altude.gasstation.data.SendOptions
import com.altude.gasstation.data.AttestationOption
import com.altude.gasstation.data.AttestationResponse
import com.altude.gasstation.data.CreateSchemaOption
import com.altude.gasstation.data.RevokeAttestationOption
import com.altude.core.Programs.AttestationProgram
import com.altude.core.helper.Mnemonic
import com.altude.gasstation.data.KeyPair
import com.altude.gasstation.data.SolanaKeypair
import com.altude.core.service.StorageService
import com.altude.core.data.AttestRequest
import com.altude.core.data.BatchTransactionRequest
import com.altude.core.data.CreateSchemaRequest
import com.altude.core.data.QuoteResponse
import com.altude.core.data.RevokeAttestationRequest
import com.altude.core.data.SendTransactionRequest
import com.altude.core.data.SwapTransactionRequest
import com.altude.gasstation.data.GetAccountResponse
import com.altude.gasstation.data.GetBalanceResponse
import com.altude.gasstation.data.SwapOption
import com.altude.gasstation.data.Token
import com.altude.gasstation.data.TransactionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import retrofit2.await


object Altude {

    suspend fun setApiKey(context: Context, apiKey: String) {
        SdkConfig.setApiKey(context, apiKey)
        saveMnemonic(Mnemonic.generateMnemonic(12))
    }

    suspend fun saveMnemonic(mnemonicWords: String) {
        StorageService.storeMnemonic(mnemonicWords)
    }
    suspend fun savePrivateKey(byteArraySecretKey: ByteArray ) {
        StorageService.storePrivateKeyByteArray(  byteArraySecretKey)
    }

    val json = Json {
        ignoreUnknownKeys = true   // don’t crash if backend adds new fields
        isLenient = true           // accept non-strict JSON (unquoted keys, etc.)
        encodeDefaults = true      // include default values in request bodies

    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun send(
        options: SendOptions
    ): Result<TransactionResponse> = withContext(Dispatchers.IO) {
        try {
            val result = GaslessManager.transferToken(options)
            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = SendTransactionRequest(signedTransaction)

            val res = service.sendTransaction(request).await()
            Result.success(deCodeJson<TransactionResponse>(res))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun sendBatch(
        options: List<SendOptions>
    ): Result<TransactionResponse> = withContext(Dispatchers.IO) {
        try {
            val result = GaslessManager.batchTransferToken(options)

            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = BatchTransactionRequest(signedTransaction)

            val res = service.sendBatchTransaction(request).await()
            Result.success(deCodeJson<TransactionResponse>(res))
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun createAccount(
        options: CreateAccountOption = CreateAccountOption()
    ): Result<TransactionResponse> = withContext(Dispatchers.IO) {
        try {
            val result = GaslessManager.createAccount(options)

            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = SendTransactionRequest(signedTransaction)

            val res = service.createAccount(request).await()
            Result.success(deCodeJson<TransactionResponse>(res))
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun closeAccount(
        options: CloseAccountOption = CloseAccountOption()
    ): Result<TransactionResponse> = withContext(Dispatchers.IO) {
        try {
            val result = GaslessManager.closeAccount(options)

            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = SendTransactionRequest(signedTransaction)

            val res = service.closeAccount(request).await()
            Result.success(deCodeJson<TransactionResponse>(res))
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun swap(
        options: SwapOption
    ): Result<TransactionResponse> = withContext(Dispatchers.IO) {
        try {
            val result = GaslessManager.swapInstruction(options)

            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = SwapTransactionRequest(signedTransaction)

            val res = service.swapTransaction(request).await()

            //try to unwrap sol token after swap
            closeAccount(CloseAccountOption(options.account, listOf(Token.SOL.mint(), options.inputMint).distinct())).getOrNull()

            Result.success(deCodeJson<TransactionResponse>(res))
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun swapUsingServerTransaction(
        options: SwapOption
    ): Result<TransactionResponse> = withContext(Dispatchers.IO) {
        try {
            val result = GaslessManager.swap(options)

            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = SwapTransactionRequest(signedTransaction)

            val res = service.swapTransaction(request).await()

            Result.success(deCodeJson<TransactionResponse>(res))
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun quote(
        options: SwapOption
    ): Result<QuoteResponse> = withContext(Dispatchers.IO) {
        try {
            val result = GaslessManager.quote(options)

            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            Result.success(result.getOrThrow())
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun getHistory(
        options: GetHistoryOption
    ): Result<GetHistoryData> = withContext(Dispatchers.IO) {
        try {
            val service = SdkConfig.createService(TransactionService::class.java)

            val res = service.getHistory(options.offset.toString(),options.limit.toString(),options.account).await()

            Result.success(deCodeJson<GetHistoryData>(res))
        } catch (e: Exception) {
            println("Error: $e")
            return@withContext Result.failure(e)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun getBalance(
        option: GetBalanceOption
    ): Result<GetBalanceResponse> = withContext(Dispatchers.IO)  {
        try {
            val defaultWallet = GaslessManager.getKeyPair(option.account)

            val account = if (option.account == "") defaultWallet.publicKey.toBase58() else option.account
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = GetBalanceRequest(account, option.token)

            val res = service.getBalance(request).await()
            Result.success(deCodeJson<GetBalanceResponse>(res))
            //return   result.data?.parsed?.info?.tokenAmount
        } catch (e: Exception) {
            println("Error: $e")
            return@withContext Result.failure(e)
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun getAccountInfo(
        option: GetAccountInfoOption = GetAccountInfoOption()
    ): Result<GetAccountResponse> = withContext(Dispatchers.IO)  {
        try {
            val defaultWallet = GaslessManager.getKeyPair(option.account)

            val account = if (option.account == "") defaultWallet.publicKey.toBase58() else option.account
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = GetAccountInfoRequest(account)

            val res = service.getAccountInfo(request).await()
            Result.success(deCodeJson<GetAccountResponse>(res))
        } catch (e: Exception) {
            println("Error: $e")
            return@withContext Result.failure(e)
        }
    }

    // ── Solana Attestation Service (SAS) ─────────────────────────────────────

    /**
     * Creates a new Schema on-chain using the Solana Attestation Service.
     *
     * A Schema must exist before you can create attestations.
     * The schema PDA is derived from (authority wallet, name) so the same
     * schema is always at the same address for a given authority + name pair.
     *
     * Usage:
     * ```kotlin
     * Altude.createSchema(
     *     CreateSchemaOption(
     *         name        = "kyc-v1",
     *         description = "KYC level attestation",
     *         fieldNames  = listOf("level", "country", "verifiedAt"),
     *         isRevocable = true
     *     )
     * ).onSuccess { response ->
     *     val schemaId = response.attestationId  // store this for attest() calls
     * }
     * ```
     *
     * @return [AttestationResponse] containing the Schema PDA in [AttestationResponse.attestationId]
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun createSchema(
        option: CreateSchemaOption,
        signer: TransactionSigner? = null
    ): Result<AttestationResponse> = withContext(Dispatchers.IO) {
        try {
            val result = GaslessManager.createSchema(option, signer)
            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = CreateSchemaRequest(signedTransaction)

            val res = service.createSchema(request).await()
            val txResponse = deCodeJson<TransactionResponse>(res)
            val attester = SdkConfig.currentSigner?.publicKey
            val schemaId = attester?.let {
                AttestationProgram.deriveSchemaAddress(it, option.name).toBase58()
            } ?: ""

            Result.success(
                AttestationResponse(
                    Status        = txResponse.Status,
                    Message       = txResponse.Message,
                    Signature     = txResponse.Signature,
                    attestationId = schemaId
                )
            )
        } catch (e: Throwable) {
            Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }

    /**
     * Creates an on-chain attestation using the Solana Attestation Service.
     *
     * Requires a Schema to already exist (see [createSchema]).
     * Biometric authentication is triggered internally.
     *
     * Usage:
     * ```kotlin
     * Altude.attest(
     *     AttestationOption(
     *         schemaId  = "schema_pubkey_base58",
     *         recipient = "recipient_wallet_base58",
     *         data      = """{"level":2,"country":"US"}""".encodeToByteArray(),
     *         expireAt  = 0L   // 0 = no expiry
     *     )
     * ).onSuccess { response ->
     *     val attestationId = response.attestationId // on-chain PDA address
     *     val signature     = response.Signature
     * }.onFailure { error ->
     *     // handle vault / network error
     * }
     * ```
     *
     * @return [AttestationResponse] containing the Attestation PDA in [AttestationResponse.attestationId]
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun attest(
        option: AttestationOption,
        signer: TransactionSigner? = null
    ): Result<AttestationResponse> = withContext(Dispatchers.IO) {
        try {
            val result = GaslessManager.attest(option, signer)
            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = AttestRequest(signedTransaction)

            val res = service.attest(request).await()
            val txResponse = deCodeJson<TransactionResponse>(res)
            val attester   = SdkConfig.currentSigner?.publicKey
            val schemaPda  = foundation.metaplex.solanapublickeys.PublicKey(option.schemaId)
            val recipientKey = if (option.recipient.isBlank()) attester
                               else foundation.metaplex.solanapublickeys.PublicKey(option.recipient)
            val attestationId = if (attester != null && recipientKey != null) {
                AttestationProgram.deriveAttestationAddress(
                    schema    = schemaPda,
                    attester  = attester,
                    recipient = recipientKey,
                    nonce     = option.nonce
                ).toBase58()
            } else ""

            Result.success(
                AttestationResponse(
                    Status        = txResponse.Status,
                    Message       = txResponse.Message,
                    Signature     = txResponse.Signature,
                    attestationId = attestationId
                )
            )
        } catch (e: Throwable) {
            Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }

    /**
     * Revokes an existing on-chain attestation.
     *
     * Only the original attester can revoke, and the schema must have [isRevocable = true].
     * Biometric authentication is triggered internally.
     *
     * Usage:
     * ```kotlin
     * Altude.revokeAttestation(
     *     RevokeAttestationOption(attestationId = "attestation_pda_base58")
     * ).onSuccess { response ->
     *     // attestation is now marked revoked on-chain
     * }
     * ```
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun revokeAttestation(
        option: RevokeAttestationOption,
        signer: TransactionSigner? = null
    ): Result<AttestationResponse> = withContext(Dispatchers.IO) {
        try {
            val result = GaslessManager.revokeAttestation(option, signer)
            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = RevokeAttestationRequest(signedTransaction)

            val res = service.revokeAttestation(request).await()
            val txResponse = deCodeJson<TransactionResponse>(res)

            Result.success(
                AttestationResponse(
                    Status    = txResponse.Status,
                    Message   = txResponse.Message,
                    Signature = txResponse.Signature
                )
            )
        } catch (e: Throwable) {
            Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }

    suspend fun generateKeyPair(): SolanaKeypair {
        val keypair = KeyPair.generate()
        return SolanaKeypair(keypair.publicKey,keypair.secretKey)
    }
    fun storedWallet():List<String>{
        return StorageService.listStoredWalletAddresses()
    }

    private inline fun <reified T> deCodeJson(jsonElement: JsonElement): T{
        return json.decodeFromJsonElement<T>(jsonElement)
    }
}
