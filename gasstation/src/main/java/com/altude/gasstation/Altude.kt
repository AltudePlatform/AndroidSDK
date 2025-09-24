package com.altude.gasstation

import android.content.Context
import com.altude.core.Programs.AssociatedTokenAccountProgram
import com.altude.gasstation.helper.Utility
import com.altude.core.config.SdkConfig
import com.altude.core.api.TransactionService
import com.altude.core.data.AccountInfoValue
import com.altude.core.data.GetBalanceOption
import com.altude.gasstation.data.CloseAccountOption
import com.altude.gasstation.data.CreateAccountOption
import com.altude.core.data.GetAccountInfoOption
import com.altude.gasstation.data.GetHistoryData
import com.altude.gasstation.data.GetHistoryOption
import com.altude.core.data.TokenAmount
import com.altude.gasstation.data.SendOptions
import com.altude.core.helper.Mnemonic
import com.altude.gasstation.data.KeyPair
import com.altude.gasstation.data.SolanaKeypair
import com.altude.core.service.StorageService
import com.altude.core.data.BatchTransactionRequest
import com.altude.core.data.SendTransactionRequest
import com.altude.gasstation.data.TransactionResponse
import foundation.metaplex.solanapublickeys.PublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import retrofit2.Call
import retrofit2.Response
import retrofit2.Callback
import retrofit2.await
import java.lang.Error
import kotlin.coroutines.resume


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
        explicitNulls = false      // don’t send nulls unless explicitly set
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
        options: CreateAccountOption
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
        options: CloseAccountOption
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
    ): TokenAmount? {
        val defaultWallet = GaslessManager.getKeyPair(option.account)
        val owner = defaultWallet.publicKey.toBase58().let { option.account }
        val ata = AssociatedTokenAccountProgram.deriveAtaAddress(PublicKey(owner), PublicKey(option.token))
        val result: AccountInfoValue? = Utility.getAccountInfo(ata.toBase58())
        if (result == null) throw Error("No data found")

        return   result.data?.parsed?.info?.tokenAmount
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun getAccountInfo(
        option: GetAccountInfoOption = GetAccountInfoOption()
    ): AccountInfoValue? {
//        val defaultWallet = TransactionManager.getKeyPair(option.account)
//        val owner = defaultWallet.publicKey.toBase58().let { option.account }

        val result = Utility.getAccountInfo(option.account, option.useBase64)
        //if (result == null) throw Error("No data found")

        return  result
    }

    suspend fun generateKeyPair(): SolanaKeypair {
        val keypair = KeyPair.generate()
        return SolanaKeypair(keypair.publicKey,keypair.secretKey)
    }
    fun storedWallet():List<String>{
        return StorageService.listStoredWalletAddresses()
    }

    private inline fun <reified T> deCodeJson(jsonElement: JsonElement): T{
        return Json.decodeFromJsonElement<T>(jsonElement)
    }
}
