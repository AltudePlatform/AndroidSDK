package com.altude.gasstation

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.altude.core.api.GetAccountInfoRequest
import com.altude.core.api.GetBalanceRequest
import com.altude.core.config.InitOptions
import com.altude.core.config.SdkConfig
import com.altude.core.api.TransactionService
import com.altude.gasstation.data.GetBalanceOption
import com.altude.gasstation.data.CloseAccountOption
import com.altude.gasstation.data.CreateAccountOption
import com.altude.gasstation.data.GetAccountInfoOption
import com.altude.gasstation.data.GetHistoryData
import com.altude.gasstation.data.GetHistoryOption
import com.altude.gasstation.data.SendOptions
import com.altude.core.helper.Mnemonic
import com.altude.core.model.TransactionSigner
import com.altude.gasstation.data.KeyPair
import com.altude.gasstation.data.SolanaKeypair
import com.altude.core.service.StorageService
import com.altude.core.data.BatchTransactionRequest
import com.altude.core.data.QuoteResponse
import com.altude.core.data.SendTransactionRequest
import com.altude.core.data.SwapTransactionRequest
import com.altude.gasstation.data.GetAccountResponse
import com.altude.gasstation.data.GetBalanceResponse
import com.altude.gasstation.data.SwapOption
import com.altude.gasstation.data.Token
import com.altude.gasstation.data.TransactionResponse
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import retrofit2.await


object Altude {

    /**
     * Initialize the SDK with your API key and the default Vault signer.
     *
     * This is the only setup call SDK users need.
     * It initialises the network layer, sets up the Vault, and registers the
     * biometric-backed signer so every subsequent Altude.* call just works.
     *
     * Usage:
     * ```
     * // In onCreate() or Application.onCreate():
     * Altude.setApiKey(this, "AK_...")
     *
     * // Then anywhere in your app:
     * Altude.send(SendOptions(toAddress = "...", amount = 1.0))
     * ```
     *
     * @param activity FragmentActivity required for biometric prompts (use `this` in Activity)
     * @param apiKey   Your Altude API key
     * @param options  Optional: override signer strategy (default = Vault with biometric)
     * @return Result.success(Unit) or Result.failure(VaultException) with remediation info
     */
    suspend fun setApiKey(
        activity: FragmentActivity,
        apiKey: String,
        options: InitOptions = InitOptions()
    ): Result<Unit> {
        return AltudeGasStation.init(activity, apiKey, options)
    }

    /**
     * Legacy overload — kept for backward compatibility.
     * Prefer setApiKey(activity, apiKey) when using the default Vault.
     * Use this overload only when you have a custom signer that does not need a FragmentActivity.
     */
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
        options: SendOptions,
        signer: TransactionSigner? = null
    ): Result<TransactionResponse> = withContext(Dispatchers.IO) {
        try {
            val result = GaslessManager.transferToken(options, signer)
            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = SendTransactionRequest(signedTransaction)

            val res = service.sendTransaction(request).await()
            Result.success(deCodeJson<TransactionResponse>(res))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun sendBatch(
        options: List<SendOptions>,
        signer: TransactionSigner? = null
    ): Result<TransactionResponse> = withContext(Dispatchers.IO) {
        try {
            val signers = signer?.let { listOf(it) }
            val result = GaslessManager.batchTransferToken(options, signers)

            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = BatchTransactionRequest(signedTransaction)

            val res = service.sendBatchTransaction(request).await()
            Result.success(deCodeJson<TransactionResponse>(res))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun createAccount(
        options: CreateAccountOption = CreateAccountOption(),
        signer: TransactionSigner? = null
    ): Result<TransactionResponse> = withContext(Dispatchers.IO) {
        try {
            val result = GaslessManager.createAccount(options, signer)

            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = SendTransactionRequest(signedTransaction)

            val res = service.createAccount(request).await()
            Result.success(deCodeJson<TransactionResponse>(res))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun closeAccount(
        options: CloseAccountOption = CloseAccountOption(),
        signer: TransactionSigner? = null
    ): Result<TransactionResponse> = withContext(Dispatchers.IO) {
        try {
            val result = GaslessManager.closeAccount(options, signer)

            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = SendTransactionRequest(signedTransaction)

            val res = service.closeAccount(request).await()
            Result.success(deCodeJson<TransactionResponse>(res))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun swap(
        options: SwapOption,
        signer: TransactionSigner? = null
    ): Result<TransactionResponse> = withContext(Dispatchers.IO) {
        try {
            val result = GaslessManager.swapInstruction(options, signer)

            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow().serialize()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = SwapTransactionRequest(signedTransaction)

            val res = service.swapTransaction(request).await()

            //try to unwrap sol token after swap
            closeAccount(CloseAccountOption(options.account, listOf(Token.SOL.mint(), options.inputMint).distinct()), signer).getOrNull()

            Result.success(deCodeJson<TransactionResponse>(res))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun swapUsingServerTransaction(
        options: SwapOption,
        signer: TransactionSigner? = null
    ): Result<TransactionResponse> = withContext(Dispatchers.IO) {
        try {
            val result = GaslessManager.swap(options, signer)

            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = SwapTransactionRequest(signedTransaction)

            val res = service.swapTransaction(request).await()

            Result.success(deCodeJson<TransactionResponse>(res))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun getHistory(
        options: GetHistoryOption
    ): Result<GetHistoryData> = withContext(Dispatchers.IO) {
        try {
            val account = resolveAccount(options.account)
            val service = SdkConfig.createService(TransactionService::class.java)

            val res = service.getHistory(options.offset.toString(), options.limit.toString(), account).await()

            Result.success(deCodeJson<GetHistoryData>(res))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }

    private fun resolveAccount(account: String): String {
        if (account.isNotBlank()) return account
        val signer = SdkConfig.currentSigner
        requireNotNull(signer) { "Vault signer required. Call SdkConfig.setSigner(VaultSigner) before using SDK methods." }
        return signer.publicKey.toBase58()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun getBalance(
        option: GetBalanceOption
    ): Result<GetBalanceResponse> = withContext(Dispatchers.IO)  {
        try {
            val account = resolveAccount(option.account)
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = GetBalanceRequest(account, option.token)

            val res = service.getBalance(request).await()
            Result.success(deCodeJson<GetBalanceResponse>(res))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun getAccountInfo(
        option: GetAccountInfoOption = GetAccountInfoOption()
    ): Result<GetAccountResponse> = withContext(Dispatchers.IO)  {
        try {
            val account = resolveAccount(option.account)
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = GetAccountInfoRequest(account)

            val res = service.getAccountInfo(request).await()
            Result.success(deCodeJson<GetAccountResponse>(res))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
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
