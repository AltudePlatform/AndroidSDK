package com.altude.gasstation

import android.content.Context
import com.altude.core.Programs.AssociatedTokenAccountProgram
import com.altude.core.Programs.Utility
import com.altude.core.TransactionManager
import com.altude.core.api.BatchTransactionRequest
import com.altude.core.api.SendTransactionRequest
import com.altude.core.api.TransactionResponse
import com.altude.core.config.SdkConfig
import com.altude.core.api.TransactionService
import com.altude.core.data.AccountData
import com.altude.core.data.AccountInfoValue
import com.altude.core.data.GetBalanceOption
import com.altude.core.data.CloseAccountOption
import com.altude.core.data.CreateAccountOption
import com.altude.core.data.GetAccountInfoOption
import com.altude.core.data.GetHistoryData
import com.altude.core.data.GetHistoryOption
import com.altude.core.data.TokenAmount
import com.altude.core.data.TransferOptions
import com.altude.core.service.StorageService
import foundation.metaplex.solanapublickeys.PublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Response
import retrofit2.Callback
import java.lang.Error


object Altude {

    fun setApiKey(context: Context, apiKey: String) {
        SdkConfig.setApiKey(context, apiKey)
    }

    suspend fun saveMnemonic(mnemonicWords: String) {
        StorageService.storeMnemonic(mnemonicWords)
    }
    suspend fun savePrivateKey(byteArraySecretKey: ByteArray ) {
        StorageService.storePrivateKeyByteArray(  byteArraySecretKey)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun transfer(
        options: TransferOptions
    ): Result<TransactionResponse> = withContext(Dispatchers.IO) {
        try {
            val result = TransactionManager.transferToken(options)

            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = SendTransactionRequest(signedTransaction)

            suspendCancellableCoroutine { cont ->
                service.sendTransaction(request)
                    .enqueue(object : Callback<TransactionResponse> {
                        override fun onResponse(
                            call: Call<TransactionResponse>,
                            response: Response<TransactionResponse>
                        ) {
                            val body = response.body()
                            if (response.isSuccessful && body != null) {
                                cont.resume(Result.success(body), onCancellation = null)
                            } else {
                                val error = Throwable("Error: ${response.code()} - ${response.message()}")
                                cont.resume(Result.failure(error), onCancellation = null)
                            }
                        }

                        override fun onFailure(call: Call<TransactionResponse>, t: Throwable) {
                            cont.resume(Result.failure(t), onCancellation = null)
                        }
                    })
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun batchtransfer(
        options: List<TransferOptions>
    ): Result<TransactionResponse> = withContext(Dispatchers.IO) {
        try {
            val result = TransactionManager.batchTransferToken(options)

            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = BatchTransactionRequest(signedTransaction)

            suspendCancellableCoroutine { cont ->
                service.sendBatchTransaction(request)
                    .enqueue(object : Callback<TransactionResponse> {
                        override fun onResponse(
                            call: Call<TransactionResponse>,
                            response: Response<TransactionResponse>
                        ) {
                            val body = response.body()
                            if (response.isSuccessful && body != null) {
                                cont.resume(Result.success(body), onCancellation = null)
                            } else {
                                val error = Throwable("Error: ${response.code()} - ${response.message()}")
                                cont.resume(Result.failure(error), onCancellation = null)
                            }
                        }

                        override fun onFailure(call: Call<TransactionResponse>, t: Throwable) {
                            cont.resume(Result.failure(t), onCancellation = null)
                        }
                    })
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun createAccount(
        options: CreateAccountOption
    ): Result<TransactionResponse> = withContext(Dispatchers.IO) {
        try {
            val result = TransactionManager.createAccount(options)

            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = SendTransactionRequest(signedTransaction)

            suspendCancellableCoroutine { cont ->
                service.createAccount(request)
                    .enqueue(object : Callback<TransactionResponse> {
                        override fun onResponse(
                            call: Call<TransactionResponse>,
                            response: Response<TransactionResponse>
                        ) {
                            val body = response.body()
                            if (response.isSuccessful && body != null) {
                                cont.resume(Result.success(body), onCancellation = null)
                            } else {
                                val error = Throwable("Error: ${response.code()} - ${response.message()}")
                                cont.resume(Result.failure(error), onCancellation = null)
                            }
                        }

                        override fun onFailure(call: Call<TransactionResponse>, t: Throwable) {
                            cont.resume(Result.failure(t), onCancellation = null)
                        }
                    })
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun closeAccount(
        options: CloseAccountOption
    ): Result<TransactionResponse> = withContext(Dispatchers.IO) {
        try {
            val result = TransactionManager.closeAccount(options)

            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            val request = SendTransactionRequest(signedTransaction)

            suspendCancellableCoroutine { cont ->
                service.closeAccount(request)
                    .enqueue(object : Callback<TransactionResponse> {
                        override fun onResponse(
                            call: Call<TransactionResponse>,
                            response: Response<TransactionResponse>
                        ) {
                            val body = response.body()
                            if (response.isSuccessful && body != null) {
                                cont.resume(Result.success(body), onCancellation = null)
                            } else {
                                val error =
                                    Throwable("Error: ${response.code()} - ${response.message()}")
                                cont.resume(Result.failure(error), onCancellation = null)
                            }
                        }

                        override fun onFailure(call: Call<TransactionResponse>, t: Throwable) {
                            cont.resume(Result.failure(t), onCancellation = null)
                        }
                    })
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun getHistory(
        options: GetHistoryOption
    ): Result<GetHistoryData> = withContext(Dispatchers.IO) {
        try {
            //val result = TransactionManager.closeAccount(options)

            //if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            //val signedTransaction = result.getOrThrow()
            val service = SdkConfig.createService(TransactionService::class.java)
            //val request = SendTransactionRequest(signedTransaction)

            suspendCancellableCoroutine { cont ->
                service.getHistory(options.offset.toString(),options.limit.toString(),options.account)
                    .enqueue(object : Callback<GetHistoryData> {
                        override fun onResponse(
                            call: Call<GetHistoryData>,
                            response: Response<GetHistoryData>
                        ) {
                            val body = response.body()
                            if (response.isSuccessful && body != null) {
                                cont.resume(Result.success(body), onCancellation = null)
                            } else {
                                val error =
                                    Throwable("Error: ${response.code()} - ${response.message()}")
                                cont.resume(Result.failure(error), onCancellation = null)
                            }
                        }

                        override fun onFailure(call: Call<GetHistoryData>, t: Throwable) {
                            cont.resume(Result.failure(t), onCancellation = null)
                        }
                    })
            }
        } catch (e: Exception) {
            println(e)
            return@withContext Result.failure(e)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun getBalance(
        option: GetBalanceOption
    ): TokenAmount? {
        val defaultWallet = TransactionManager.getKeyPair(option.account)
        val owner = defaultWallet.publicKey.toBase58().let { option.account }
        val ata = AssociatedTokenAccountProgram.deriveAtaAddress(PublicKey(owner), PublicKey(option.token))
        val result: AccountInfoValue? = Utility.getAccountInfo(ata.toBase58())
        if (result == null) throw Error("No data found")

        return   result.data?.parsed?.info?.tokenAmount
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun getAccountInfo(
        option: GetAccountInfoOption
    ): AccountInfoValue? {
//        val defaultWallet = TransactionManager.getKeyPair(option.account)
//        val owner = defaultWallet.publicKey.toBase58().let { option.account }

        val result = Utility.getAccountInfo(option.account, option.useBase64)
        //if (result == null) throw Error("No data found")

        return  result
    }
}
