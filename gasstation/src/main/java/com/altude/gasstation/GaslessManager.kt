package com.altude.gasstation

import android.util.Base64
import com.altude.core.Programs.AssociatedTokenAccountProgram
import com.altude.core.Programs.SwapHelper
import com.altude.core.Programs.TokenProgram
import com.altude.core.api.SwapService
import com.altude.core.config.SdkConfig
import com.altude.core.config.SwapConfig
import com.altude.core.data.PrioritizationFeeLamports
import com.altude.core.data.PriorityLevelWithMaxLamports
import com.altude.core.data.QuoteResponse
import com.altude.core.data.SwapInstructionRequest
import com.altude.core.data.SwapRequest
import com.altude.core.data.SwapResponse
import com.altude.core.data.toQueryMap
import com.altude.core.model.AltudeTransaction
import com.altude.core.model.AltudeTransactionBuilder
import com.altude.core.model.EmptySignature
import com.altude.core.model.MessageAddressTableLookup
import com.altude.core.model.TransactionSigner
import com.altude.core.model.TransactionVersion
import com.altude.core.network.AltudeRpc
import com.altude.gasstation.data.CloseAccountOption
import com.altude.gasstation.data.CreateAccountOption
import com.altude.gasstation.data.ISendOption
import com.altude.gasstation.data.SendOptions
import com.altude.gasstation.data.SwapOption
import com.altude.gasstation.data.Token
import com.altude.gasstation.data.parseLookupTableAccountBase64
import com.altude.gasstation.helper.Utility
import foundation.metaplex.solana.transactions.SerializeConfig
import foundation.metaplex.solana.transactions.TransactionInstruction
import foundation.metaplex.solanapublickeys.PublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import retrofit2.HttpException
import retrofit2.await
import kotlin.math.pow

object GaslessManager {

    private val rpc = AltudeRpc(SdkConfig.apiConfig.RpcUrl)
    val feePayerPubKey =
        PublicKey(SdkConfig.apiConfig.FeePayer) // PublicKey("Hwdo4thQCFKB3yuohhmmnb1gbUBXySaVJwBnkmRgN8cK") //ALZ8NJcf8JDL7j7iVfoyXM8u3fT3DoBXsnAU6ML7Sb5W BjLvdmqDjnyFsewJkzqPSfpZThE8dGPqCAZzVbJtQFSr
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true   // don’t crash if backend adds new fields
        isLenient = true           // accept non-strict JSON (unquoted keys, etc.)
        encodeDefaults = true      // include default values in request bodies
        explicitNulls = false      // don’t send nulls unless explicitly set
        decodeEnumsCaseInsensitive = true
            }
    suspend fun transferToken(option: ISendOption, signer: TransactionSigner? = null): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val signerToUse = resolveSigner(option.account, signer)
            ensureBiometricAuth(signerToUse, "transfer")
            // After biometric unlock the public key is always available - use it as account
            val ownerKey = signerToUse.publicKey
            val pubKeyMint = PublicKey(option.token)
            val pubKeyDestination = PublicKey(option.toAddress)
            val sourceAta = AssociatedTokenAccountProgram.deriveAtaAddress(ownerKey, pubKeyMint)
            val destinationAta = AssociatedTokenAccountProgram.deriveAtaAddress(pubKeyDestination, pubKeyMint)
            if (!Utility.ataExists(sourceAta.toBase58()))
                throw Exception("Owner associated token account does not exist.")

            val destinationCreateAta: TransactionInstruction? =
                if (!Utility.ataExists(destinationAta.toBase58())) {
                    AssociatedTokenAccountProgram.createAssociatedTokenAccount(
                        ata = destinationAta,
                        feePayer = feePayerPubKey,
                        owner = pubKeyDestination,
                        mint = pubKeyMint
                    )
                } else null

            val decimals = Utility.getTokenDecimals(option.token)
            val rawAmount = com.altude.core.Programs.Utility.getRawQuantity(option.amount, decimals)

            val transferInstruction = TokenProgram.transferToken(
                source = sourceAta,
                destination = destinationAta,
                owner = ownerKey,
                mint = pubKeyMint,
                amount = rawAmount,
                decimals = decimals.toUInt()
            )

            val blockhashInfo = rpc.getLatestBlockhash(commitment = option.commitment.name)

            val builder = AltudeTransactionBuilder()
                .setFeePayer(feePayerPubKey)
                .setRecentBlockHash(blockhashInfo.blockhash)
            destinationCreateAta?.let { builder.addInstruction(it) }
            builder.addInstruction(transferInstruction)
            builder.setSigners(listOf(signerToUse))

            val serialized = Base64.encodeToString(
                builder.build().serialize(SerializeConfig(requireAllSignatures = false)),
                Base64.NO_WRAP
            )

            Result.success(serialized)
        } catch (e: Throwable) {
            Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }

    suspend fun batchTransferToken(options: List<SendOptions>, signers: List<TransactionSigner>? = null): Result<String> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val finalSigners = resolveSignersForBatch(options, signers)
                ensureBiometricAuth(finalSigners.first(), "batch-transfer")
                val transferInstructions = mutableListOf<TransactionInstruction>()

                options.forEach { option ->
                    val signerForOption = resolveSignerForAccount(finalSigners, option.account)
                    val ownerKey = signerForOption.publicKey
                    val pubKeyMint = PublicKey(option.token)
                    val pubKeyDestination = PublicKey(option.toAddress)
                    val sourceAta = AssociatedTokenAccountProgram.deriveAtaAddress(ownerKey, pubKeyMint)
                    val destinationAta = AssociatedTokenAccountProgram.deriveAtaAddress(pubKeyDestination, pubKeyMint)

                    if (!Utility.ataExists(sourceAta.toBase58()))
                        throw Exception("Owner associated token account does not exist.")

                    val destinationCreateAta: TransactionInstruction? =
                        if (!Utility.ataExists(destinationAta.toBase58())) {
                            AssociatedTokenAccountProgram.createAssociatedTokenAccount(
                                ata = destinationAta,
                                feePayer = feePayerPubKey,
                                owner = pubKeyDestination,
                                mint = pubKeyMint
                            )
                        } else null
                    destinationCreateAta?.let { transferInstructions.add(it) }

                    val decimals = Utility.getTokenDecimals(option.token)
                    val rawAmount = com.altude.core.Programs.Utility.getRawQuantity(option.amount, decimals)

                    val transferInstruction = TokenProgram.transferToken(
                        source = sourceAta,
                        destination = destinationAta,
                        owner = ownerKey,
                        mint = pubKeyMint,
                        amount = rawAmount,
                        decimals = decimals.toUInt()
                    )
                    transferInstructions.add(transferInstruction)
                }

                val blockhashInfo = rpc.getLatestBlockhash()

                val builder = AltudeTransactionBuilder()
                    .setFeePayer(feePayerPubKey)
                    .setRecentBlockHash(blockhashInfo.blockhash)
                    .addRangeInstruction(transferInstructions)
                    .setSigners(finalSigners.distinctBy { it.publicKey.toBase58() })

                val serialized = Base64.encodeToString(
                    builder.build().serialize(SerializeConfig(requireAllSignatures = false)),
                    Base64.NO_WRAP
                )

                Result.success(serialized)
            } catch (e: Throwable) {
                Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
            }
        }

    suspend fun createAccount(option: CreateAccountOption, signer: TransactionSigner? = null): Result<String> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val signerToUse = resolveSigner(option.account, signer)
                ensureBiometricAuth(signerToUse, "create-account")
                val ownerKey = signerToUse.publicKey

                val txInstructions = mutableListOf<TransactionInstruction>()
                option.tokens.forEach { token ->
                    val mintKey = PublicKey(token)
                    val ata = AssociatedTokenAccountProgram.deriveAtaAddress(ownerKey, mintKey)
                    if (!Utility.ataExists(ata.toBase58())) {
                        val ataInstruction =
                            AssociatedTokenAccountProgram.createAssociatedTokenAccount(
                                ata = ata,
                                feePayer = feePayerPubKey,
                                owner = ownerKey,
                                mint = mintKey
                            )
                        txInstructions.add(ataInstruction)
                        val authInstruction = TokenProgram.setAuthority(
                            ata = ata,
                            currentAuthority = ownerKey,
                            newOwner = feePayerPubKey
                        )
                        txInstructions.add(authInstruction)
                    }
                }
                if (txInstructions.isEmpty()) {
                    if (option.tokens.count() > 1) {
                        return@withContext Result.failure(Error("All Token accounts already created"))
                    }
                    return@withContext Result.failure(Error("Token account already created"))
                }

                val blockhashInfo = rpc.getLatestBlockhash(commitment = option.commitment.name)

                val tx = AltudeTransactionBuilder().addRangeInstruction(txInstructions)
                    .setFeePayer(feePayerPubKey)
                    .setRecentBlockHash(blockhashInfo.blockhash)
                    .setSigners(listOf(signerToUse))
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

    suspend fun closeAccount(
        option: CloseAccountOption,
        signer: TransactionSigner? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val signerToUse = resolveSigner(option.account, signer)
            ensureBiometricAuth(signerToUse, "close-account")
            val ownerKey = signerToUse.publicKey
                ?: option.account.takeIf { it.isNotBlank() }?.let { PublicKey(it) }
                ?: throw IllegalArgumentException("Account public key required to close accounts")

            val txInstructions = mutableListOf<TransactionInstruction>()
            var requiresOwnerSignature = false
            option.tokens.forEach { token ->
                val mintKey = PublicKey(token)
                val ata = AssociatedTokenAccountProgram.deriveAtaAddress(ownerKey, mintKey)
                val ataInfo = Utility.getAccountInfo(ata.toBase58())
                if (ataInfo != null) {
                    val parsed = ataInfo.data?.parsed?.info
                    val balance = parsed?.tokenAmount?.uiAmount ?: 0.0
                    if (balance == 0.0 || token == Token.SOL.mint()) {
                        val closeAuthority = parsed?.closeAuthority
                        val authorized = if (closeAuthority == feePayerPubKey.toBase58()) {
                            feePayerPubKey
                        } else {
                            requiresOwnerSignature = true
                            ownerKey
                        }
                        val instruction = TokenProgram.closeAtaAccount(
                            ata = ata,
                            destination = authorized,
                            authority = authorized
                        )
                        txInstructions.add(instruction)
                    }
                }
            }
            if (txInstructions.isEmpty()) {
                if (option.tokens.count() > 1)
                    return@withContext Result.failure(Error("All selected token accounts either have a balance or are already closed."))
                return@withContext Result.failure(Error("The selected token account either has a balance or is already closed."))
            }

            val blockhashInfo = rpc.getLatestBlockhash(commitment = option.commitment.name)

            val tx = AltudeTransactionBuilder()
                .setFeePayer(feePayerPubKey)
                .addRangeInstruction(txInstructions)
                .setRecentBlockHash(blockhashInfo.blockhash)
                .apply {
                    if (requiresOwnerSignature) {
                        val signerForClose = signerToUse
                            ?: throw IllegalStateException("Vault signer required to close account with owner authority")
                        setSigners(listOf(signerForClose))
                    }
                }
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

    suspend fun swapInstruction(
        option: SwapOption,
        signer: TransactionSigner? = null,
    ): Result<AltudeTransaction> = withContext(Dispatchers.IO) {
        try {
            val signerToUse = resolveSigner(option.account, signer)
            ensureBiometricAuth(signerToUse, "swap")
            val ownerKey = signerToUse.publicKey
            val decimals = Utility.getTokenDecimals(option.inputMint)
            val rawAmount = (option.amount * (10.0.pow(decimals))).toLong()
            val service = SwapConfig.createService(SwapService::class.java)
            val swapRequest = SwapRequest(
                inputMint = option.inputMint,
                outputMint = option.outputMint,
                amount = rawAmount,
                slippageBps = option.slippageBps,
                swapMode = option.swapMode,
                dexes = option.dexes,
                excludeDexes = option.excludeDexes,
                restrictIntermediateTokens = option.restrictIntermediateTokens,
                onlyDirectRoutes = option.onlyDirectRoutes,
                asLegacyTransaction = option.asLegacyTransaction,
                platformFeeBps = option.platformFeeBps,
                maxAccounts = option.maxAccounts,
                instructionVersion = option.instructionVersion,
                dynamicSlippage = option.dynamicSlippage
            )

            val quote = try {
                val quoteResponse = service.quote(swapRequest.toQueryMap()).await()
                Altude.json.decodeFromJsonElement<QuoteResponse>(quoteResponse)
            } catch (e: HttpException) {
                val raw = e.response()?.errorBody()?.string()
                val errorJson = Altude.json.parseToJsonElement(raw ?: "{}")
                Altude.json.decodeFromJsonElement<QuoteResponse>(errorJson)
            }

            if (quote.isError)
                throw Exception(quote.error)
            val swapInstructionRequest = SwapInstructionRequest(
                quoteResponse = quote,
                userPublicKey = ownerKey.toBase58(),
                payer = feePayerPubKey.toBase58(),
                prioritizationFeeLamports = option.priorityLevelWithMaxLamports?.let {
                    PrioritizationFeeLamports(
                        priorityLevelWithMaxLamports = PriorityLevelWithMaxLamports(
                            priorityLevel = it.priorityLevel,
                            global = it.global,
                            maxLamports = it.maxLamports
                        )
                    )
                }
            )

            val swapResponse = try {
                val response = service.swapInstruction(swapInstructionRequest).await()
                Altude.json.decodeFromJsonElement<SwapResponse>(response)
            } catch (e: HttpException) {
                val raw = e.response()?.errorBody()?.string()
                val errorJson = Altude.json.parseToJsonElement(raw ?: "{}")
                Altude.json.decodeFromJsonElement<SwapResponse>(errorJson)
            }
            if (swapResponse.isError)
                throw Exception(swapResponse.error)

            val txInstructions = SwapHelper.buildSwapTransaction(swapResponse).toMutableList()

            txInstructions.addAll(
                txInstructions
                    .filter { it.programId == PublicKey("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL") }
                    .map {
                        TokenProgram.setAuthority(
                            ata = it.keys[1].publicKey,
                            currentAuthority = ownerKey,
                            newOwner = feePayerPubKey
                        )
                    }
            )

            val mainKeys = swapResponse.swapInstruction?.accounts?.map { it.pubkey } ?: emptyList()

            val lookupTables = swapResponse.addressLookupTableAddresses?.map { tablePubkey ->

                val accountInfo = Utility.getLookUpTable(tablePubkey)
                val dataBase64 = accountInfo?.data?.get(0) as? String ?: ""

                val alt = parseLookupTableAccountBase64(dataBase64)
                val tableAddresses = alt.addresses.map { it.toBase58() }

                val writableIdx = mutableListOf<Int>()
                val readonlyIdx = mutableListOf<Int>()

                swapResponse.swapInstruction?.accounts?.forEach { meta ->
                    val idx = tableAddresses.indexOf(meta.pubkey)
                    val metaKey = meta.pubkey
                    // ✅ skip if already in mainKeys
                    if (idx >= 0 && !mainKeys.contains(metaKey)) {
                        if (meta.isWritable) writableIdx += idx
                        else readonlyIdx += idx
                    }
                }

                MessageAddressTableLookup(
                    accountKey = PublicKey(tablePubkey),
                    writableIndexes = writableIdx.distinct(),
                    readonlyIndexes = readonlyIdx.distinct()
                )
            }?.filter { it.writableIndexes.isNotEmpty() || it.readonlyIndexes.isNotEmpty() } ?: emptyList()

            val blockhashInfo = rpc.getLatestBlockhash(commitment = option.commitment.name)

            val tx = AltudeTransactionBuilder(TransactionVersion.V0)
                .setFeePayer(feePayerPubKey)
                .addRangeInstruction(txInstructions)
                .setRecentBlockHash(blockhashInfo.blockhash)
                .addLookUpTables(lookupTables)
                .setSigners(listOf(signerToUse))
                .build()

            // Wrap serialized transaction into AltudeTransaction (expected return type)
            val serializedTx = Base64.encodeToString(
                tx.serialize(SerializeConfig(requireAllSignatures = false)),
                Base64.NO_WRAP
            )
            Result.success(AltudeTransaction(serializedTx))
        } catch (e: Throwable) {
            Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }

    suspend fun swap(
        option: SwapOption,
        signer: TransactionSigner? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val signerToUse = resolveSigner(option.account, signer)
            ensureBiometricAuth(signerToUse, "swap")
            val decimals = Utility.getTokenDecimals(option.inputMint)
            val rawAmount = (option.amount * (10.0.pow(decimals))).toLong()
            val service = SwapConfig.createService(SwapService::class.java)
            val swapRequest = SwapRequest(
                inputMint = option.inputMint,
                outputMint = option.outputMint,
                amount = rawAmount,
                slippageBps = option.slippageBps,
                swapMode = option.swapMode,
                dexes = option.dexes,
                excludeDexes = option.excludeDexes,
                restrictIntermediateTokens = option.restrictIntermediateTokens,
                onlyDirectRoutes = option.onlyDirectRoutes,
                asLegacyTransaction = option.asLegacyTransaction,
                platformFeeBps = option.platformFeeBps,
                maxAccounts = option.maxAccounts,
                instructionVersion = option.instructionVersion,
                dynamicSlippage = option.dynamicSlippage
            )

            val quote = try {
                val quoteResponse = service.quote(swapRequest.toQueryMap()).await()
                Altude.json.decodeFromJsonElement<QuoteResponse>(quoteResponse)
            } catch (e: HttpException) {
                val raw = e.response()?.errorBody()?.string()
                val errorJson = Altude.json.parseToJsonElement(raw ?: "{}")
                Altude.json.decodeFromJsonElement<QuoteResponse>(errorJson)
            }

            if (quote.isError)
                throw Exception(quote.error)
            val swapInstructionRequest = SwapInstructionRequest(
                quoteResponse = quote,
                userPublicKey = signerToUse.publicKey.toBase58(),
                payer = feePayerPubKey.toBase58(),
                prioritizationFeeLamports = option.priorityLevelWithMaxLamports?.let {
                    PrioritizationFeeLamports(
                        priorityLevelWithMaxLamports = PriorityLevelWithMaxLamports(
                            priorityLevel = it.priorityLevel,
                            global = it.global,
                            maxLamports = it.maxLamports
                        )
                    )
                }
            )

            val swapResponse = try {
                val response = service.swap(swapInstructionRequest).await()
                Altude.json.decodeFromJsonElement<SwapResponse>(response)
            } catch (e: HttpException) {
                val raw = e.response()?.errorBody()?.string()
                val errorJson = Altude.json.parseToJsonElement(raw ?: "{}")
                Altude.json.decodeFromJsonElement<SwapResponse>(errorJson)
            }
            if (swapResponse.isError)
                throw Exception(swapResponse.error)

            val tx = AltudeTransaction(swapResponse.swapTransaction ?: "")
                .partialSign(listOf(signerToUse, EmptySignature(feePayerPubKey)))

            val serialized = tx.serialize()

            Result.success(serialized)
        } catch (e: Throwable) {
            Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }

    suspend fun quote(
        option: SwapOption
    ): Result<QuoteResponse> = withContext(Dispatchers.IO) {
        return@withContext try {
            val decimals = Utility.getTokenDecimals(option.inputMint)
            val rawAmount = (option.amount * (10.0.pow(decimals))).toLong()
            val service = SwapConfig.createService(SwapService::class.java)
            val swapRequest = SwapRequest(
                inputMint = option.inputMint,
                outputMint = option.outputMint,
                amount = rawAmount,
                slippageBps = option.slippageBps
            )
            val quoteResponse = Altude.json.decodeFromJsonElement<QuoteResponse>(service.quote(swapRequest.toQueryMap()).await())
            if (quoteResponse.isError)
                Result.failure<String>(Exception(quoteResponse.error))
            Result.success(quoteResponse)
        } catch (e: Throwable) {
            Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
        }
    }

    private suspend fun ensureBiometricAuth(signer: TransactionSigner, purpose: String) {
        withContext(Dispatchers.Main) {
            signer.signMessage("auth:$purpose".toByteArray())
        }
    }

    private fun resolveSigner(account: String = "", overrideSigner: TransactionSigner? = null): TransactionSigner {
        val signer = overrideSigner ?: SdkConfig.currentSigner
        requireNotNull(signer) {
            "Vault signer required. Call AltudeGasStation.init() before using SDK methods."
        }
        // If account is blank, use the signer's public key (resolved after biometric unlock)
        if (account.isNotBlank() && signer.publicKey.toBase58() != account) {
            throw IllegalArgumentException("Signer public key ${signer.publicKey.toBase58()} does not match requested account $account")
        }
        return signer
    }

    private fun resolveSignerForAccount(signers: List<TransactionSigner>, account: String): TransactionSigner {
        if (signers.isEmpty()) throw IllegalArgumentException("No signer available")
        if (account.isBlank()) return signers.first()
        return signers.firstOrNull { it.publicKey.toBase58() == account }
            ?: throw IllegalArgumentException("No signer matches requested account $account")
    }

    private fun resolveSignersForBatch(options: List<SendOptions>, provided: List<TransactionSigner>?): List<TransactionSigner> {
        provided?.let { return it }
        val defaultSigner = resolveSigner()
        options.firstOrNull { it.account.isNotBlank() }?.let { option ->
            if (defaultSigner.publicKey.toBase58() != option.account) {
                throw IllegalArgumentException("Default signer does not match batch account ${option.account}")
            }
        }
        return listOf(defaultSigner)
    }

}