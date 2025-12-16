package com.altude.gasstation

import android.util.Base64
import com.altude.core.Programs.AssociatedTokenAccountProgram
import com.altude.core.Programs.SwapHelper
import com.altude.core.Programs.TokenProgram
import com.altude.core.api.SwapService
import com.altude.core.config.SwapConfig
import com.altude.gasstation.helper.Utility
import com.altude.core.config.SdkConfig
import com.altude.core.data.SwapResponse
import com.altude.core.data.PrioritizationFeeLamports
import com.altude.core.data.PriorityLevelWithMaxLamports
import com.altude.core.data.QuoteResponse
import com.altude.core.data.SwapInstructionRequest
import com.altude.gasstation.data.CloseAccountOption
import com.altude.gasstation.data.CreateAccountOption
import com.altude.gasstation.data.ISendOption
import com.altude.gasstation.data.SendOptions
import com.altude.core.helper.Mnemonic
import com.altude.core.model.AltudeTransactionBuilder
import com.altude.core.model.HotSigner
import com.altude.gasstation.data.KeyPair
import com.altude.gasstation.data.SolanaKeypair
import com.altude.core.network.AltudeRpc
import com.altude.core.service.StorageService
import com.altude.core.data.SwapRequest
import com.altude.core.data.toQueryMap
import com.altude.core.model.MessageAddressTableLookup
import com.altude.core.model.TransactionVersion
import com.altude.gasstation.data.SwapOption
import com.altude.gasstation.data.parseLookupTableAccountBase64
import com.metaplex.signer.Signer
import foundation.metaplex.solana.transactions.SerializeConfig
import foundation.metaplex.solana.transactions.TransactionInstruction
import foundation.metaplex.solanaeddsa.Keypair
import foundation.metaplex.solanapublickeys.PublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import retrofit2.HttpException
import retrofit2.await
import java.lang.Error
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
    suspend fun transferToken(option: ISendOption): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val pubKeyMint = PublicKey(option.token)
            val pubKeyDestination = PublicKey(option.toAddress)
            val defaultWallet = getKeyPair(option.account)
            val sourceAta =
                AssociatedTokenAccountProgram.deriveAtaAddress(defaultWallet.publicKey, pubKeyMint)
            val destinationAta =
                AssociatedTokenAccountProgram.deriveAtaAddress(pubKeyDestination, pubKeyMint)
            val sourceAtaBase58 = sourceAta.toBase58()
            if (!Utility.ataExists(sourceAtaBase58))
                throw Error("Owner associated token account does not exist.")

            val destinationCreateAta: TransactionInstruction? =
                if (!Utility.ataExists(destinationAta.toBase58())) {
                    AssociatedTokenAccountProgram.createAssociatedTokenAccount(
                        ata = destinationAta,
                        feePayer = feePayerPubKey,
                        owner = pubKeyDestination, // ✅ Corrected
                        mint = pubKeyMint
                    )
                } else null

            val decimals = Utility.getTokenDecimals(option.token)
            val rawAmount = com.altude.core.Programs.Utility.getRawQuantity(option.amount, decimals)

            val transferInstruction = TokenProgram.transferToken(
                source = sourceAta,
                destination = destinationAta,
                owner = defaultWallet.publicKey,
                mint = pubKeyMint,
                amount = rawAmount,
                decimals = decimals.toUInt(),
                //signers = listOf(defaultWallet.publicKey, feePayerPubKey)
            )

            val blockhashInfo = rpc.getLatestBlockhash(
                commitment = option.commitment.name
            )

            val recentBlockhash = blockhashInfo.blockhash
            val authorizedSignature =
                HotSigner(SolanaKeypair(defaultWallet.publicKey, defaultWallet.secretKey))

            val builder = AltudeTransactionBuilder()
                .setFeePayer(feePayerPubKey)
                .setRecentBlockHash(recentBlockhash)
            destinationCreateAta?.let { builder.addInstruction(it) }
            builder.addInstruction(transferInstruction)
            builder.setSigners(listOf(authorizedSignature))

            val build = builder.build()
            val serialized = Base64.encodeToString(
                build.serialize(SerializeConfig(requireAllSignatures = false)),
                Base64.NO_WRAP
            )

            Result.success(serialized)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun batchTransferToken(options: List<SendOptions>): Result<String> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val transferInstructions = mutableListOf<TransactionInstruction>();
                val authorizedSignatures = mutableListOf<Signer>()
                options.forEach { option ->
                    val pubKeyMint = PublicKey(option.token)
                    val pubKeyDestination = PublicKey(option.toAddress)
                    val defaultWallet = getKeyPair(option.account)
                    val sourceAta = AssociatedTokenAccountProgram.deriveAtaAddress(
                        defaultWallet.publicKey,
                        pubKeyMint
                    )
                    val destinationAta = AssociatedTokenAccountProgram.deriveAtaAddress(
                        pubKeyDestination,
                        pubKeyMint
                    )

                    if (!Utility.ataExists(sourceAta.toBase58()))
                        throw Error("Owner associated token account does not exist.")

                    val destinationCreateAta: TransactionInstruction? =
                        if (!Utility.ataExists(destinationAta.toBase58())) {
                            AssociatedTokenAccountProgram.createAssociatedTokenAccount(
                                ata = destinationAta,
                                feePayer = feePayerPubKey,
                                owner = pubKeyDestination, // ✅ Corrected
                                mint = pubKeyMint
                            )
                        } else null
                    if (destinationCreateAta != null)
                        transferInstructions.add(destinationCreateAta)
                    val decimals = Utility.getTokenDecimals(option.token)
                    val rawAmount = com.altude.core.Programs.Utility.getRawQuantity(option.amount, decimals)

                    val transferInstruction = TokenProgram.transferToken(
                        source = sourceAta,
                        destination = destinationAta,
                        owner = defaultWallet.publicKey,
                        mint = pubKeyMint,
                        amount = rawAmount,
                        decimals = decimals.toUInt(),
                        //signers = listOf(defaultWallet.publicKey, feePayerPubKey)
                    )
                    transferInstructions.add(transferInstruction)
                    if (authorizedSignatures.find { it.publicKey == defaultWallet.publicKey } == null)
                        authorizedSignatures.add(
                            HotSigner(
                                SolanaKeypair(
                                    defaultWallet.publicKey,
                                    defaultWallet.secretKey
                                )
                            )
                        )
                }

                val blockhashInfo = rpc.getLatestBlockhash()

                val recentBlockhash = blockhashInfo.blockhash


                val builder = AltudeTransactionBuilder()
                    .setFeePayer(feePayerPubKey)
                    .setRecentBlockHash(recentBlockhash)
                //destinationCreateAta?.let { builder.addInstruction(it) }
                builder.addRangeInstruction(transferInstructions)
                builder.setSigners(authorizedSignatures)

                val build = builder.build()
                val serialized = Base64.encodeToString(
                    build.serialize(SerializeConfig(requireAllSignatures = false)),
                    Base64.NO_WRAP
                )

                Result.success(serialized)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun createAccount(option: CreateAccountOption): Result<String> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val defaultWallet = getKeyPair(option.account)
                val ownerKey = defaultWallet.publicKey//PublicKey(option.owner)

                val txInstructions = mutableListOf<TransactionInstruction>()
                option.tokens.forEach { token ->
                    val mintKey = PublicKey(token)
                    val ata = AssociatedTokenAccountProgram.deriveAtaAddress(ownerKey, mintKey)
                    if (!Utility.ataExists(ata.toBase58())) {
                        val ataInstruction =
                            AssociatedTokenAccountProgram.createAssociatedTokenAccount(
                                ata = ata,
                                feePayer = feePayerPubKey, // only pubkey
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
                if (txInstructions.count() == 0) {
                    if (option.tokens.count() > 1) {
                        return@withContext  Result.failure(Error("All Token accounts already created"))

                    }
                    return@withContext Result.failure( Error("Token account already created"))
                }
                val authorizedSigner = HotSigner(SolanaKeypair(ownerKey, defaultWallet.secretKey))


                val blockhashInfo = rpc.getLatestBlockhash(
                    commitment = option.commitment.name
                )

                val tx = AltudeTransactionBuilder().addRangeInstruction(txInstructions)
                    .setFeePayer(feePayerPubKey)
                    .setRecentBlockHash(blockhashInfo.blockhash)
                    .setSigners(listOf(authorizedSigner))
                    .build()

                val serialized = Base64.encodeToString(
                    tx.serialize(SerializeConfig(requireAllSignatures = false)),
                    Base64.NO_WRAP
                )
                Result.success(serialized)
            } catch (e: Exception) {
                Error(e)
                Result.failure(e)
            }
        }

    suspend fun closeAccount(
        option: CloseAccountOption
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            var defaultWallet: Keypair? = null
            try {
                defaultWallet = getKeyPair(option.account)
            } catch (e: Error) {
            }
            val ownerKeypubkey = defaultWallet?.publicKey ?: PublicKey(option.account)

            val txInstructions = mutableListOf<TransactionInstruction>()
            var authorized: PublicKey
            var isOwnerRequiredSignature = false
            option.tokens.forEach { token ->
                val mintKey = PublicKey(token)
                val ata = AssociatedTokenAccountProgram.deriveAtaAddress(ownerKeypubkey, mintKey)
                val ataInfo = Utility.getAccountInfo(ata.toBase58())
                if (ataInfo != null) {
                    val parsed = ataInfo.data?.parsed?.info
                    val balance = parsed?.tokenAmount?.uiAmount ?: 0.0
                    if(balance == 0.0){
                        if (parsed?.closeAuthority == feePayerPubKey.toBase58() || defaultWallet == null)
                            authorized = feePayerPubKey
                        else {
                            authorized = defaultWallet.publicKey
                            isOwnerRequiredSignature = true
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
            if (txInstructions.count() == 0) {
                if (option.tokens.count() > 1)
                    return@withContext Result.failure( Error("All token accounts have balance, or are already closed."))
                return@withContext Result.failure( Error("The token account has a balance or is already closed."))
            }

            val blockhashInfo = rpc.getLatestBlockhash(
                commitment = option.commitment.name
            )

            val tx = AltudeTransactionBuilder()
                .setFeePayer(feePayerPubKey)
                .addRangeInstruction(txInstructions)
                .setRecentBlockHash(blockhashInfo.blockhash)
                .build()
            if (isOwnerRequiredSignature && defaultWallet != null)
                tx.sign(HotSigner(defaultWallet))
            //val sign = Core.SignTransaction(privateKeyBytes,message)
            val serialized = Base64.encodeToString(
                tx.serialize(SerializeConfig(requireAllSignatures = false)),
                Base64.NO_WRAP
            )
            Result.success(serialized)
        } catch (e: Exception) {
            Result.failure(e)

        }
    }

    suspend fun swap(
        option: SwapOption
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val defaultWallet = getKeyPair(option.account)
            val decimals = Utility.getTokenDecimals(option.inputMint)
            val rawAmount = (option.amount * (10.0.pow(decimals))).toLong()
            val service = SwapConfig.createService(SwapService::class.java)
            val swapRequest = SwapRequest(
                inputMint = option.inputMint,
                outputMint = option.outputMint,
                amount = rawAmount,
                slippageBps = option.slippageBps,
                swapMode = option.swapMode,
                dexes =  option.dexes,
                excludeDexes = option.excludeDexes,
                restrictIntermediateTokens = option.restrictIntermediateTokens,
                onlyDirectRoutes = option.onlyDirectRoutes,
                asLegacyTransaction = option.asLegacyTransaction,
                platformFeeBps = option.platformFeeBps,
                maxAccounts = option.maxAccounts,
                instructionVersion = option.instructionVersion,
                dynamicSlippage = option.dynamicSlippage
            )

            val result = Altude.createAccount(
                CreateAccountOption(
                    account = option.account,
                    tokens = listOf(option.inputMint,option.outputMint)
                )
            )

            val quote =  try{ val quoteResponse = service.quote(swapRequest.toQueryMap()).await()
                Altude.json.decodeFromJsonElement<QuoteResponse>(quoteResponse)}catch (e: HttpException){
                val raw = e.response()?.errorBody()?.string()
                val errorJson = Altude.json.parseToJsonElement(raw ?: "{}")
                Altude.json.decodeFromJsonElement<QuoteResponse>(errorJson)
            }

            if(quote.isError)
                throw Exception(quote.error)
            val swapInstructionRequest = SwapInstructionRequest(
                quoteResponse = quote,
                userPublicKey = option.account,
                payer = feePayerPubKey.toBase58(),
                prioritizationFeeLamports = if (option.priorityLevelWithMaxLamports != null){
                    PrioritizationFeeLamports(
                        priorityLevelWithMaxLamports = PriorityLevelWithMaxLamports(
                            priorityLevel = option.priorityLevelWithMaxLamports .priorityLevel,
                            global = option.priorityLevelWithMaxLamports.global,
                            maxLamports = option.priorityLevelWithMaxLamports.maxLamports
                        )
                    )
                } else {
                    null
                }
            )

            val swapResponse = try{ val response = service.swap(swapInstructionRequest).await()
                Altude.json.decodeFromJsonElement<SwapResponse>(response)} catch (e: HttpException){
                val raw = e.response()?.errorBody()?.string()
                val errorJson = Altude.json.parseToJsonElement(raw ?: "{}")
                Altude.json.decodeFromJsonElement<SwapResponse>(errorJson)
            }
            if(swapResponse.isError)
                throw Exception(swapResponse.error)

            val txInstructions = SwapHelper.buildSwapTransaction( swapResponse)

            val mainKeys = swapResponse.swapInstruction?.accounts?.map { it.pubkey } ?: emptyList()

            val lookupTables = swapResponse.addressLookupTableAddresses?.map { tablePubkey ->

                val accountInfo = Utility .getLookUpTable(tablePubkey)
                val dataBase64 = accountInfo?.data?.get(0) as? String ?: ""

                val alt = parseLookupTableAccountBase64(dataBase64)
                val tableAddresses = alt.addresses.map { it.toBase58() }

                val writableIdx = mutableListOf<Int>()
                val readonlyIdx = mutableListOf<Int>()

                swapResponse.swapInstruction?.accounts?.forEach { meta ->
                    val idx = tableAddresses.indexOf(meta.pubkey)
                    val metaKey = meta.pubkey
                    // ✅ skip if already in mainKeys
                    if (idx >= 0 && !mainKeys.contains(meta.pubkey)) {
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

            val blockhashInfo = rpc.getLatestBlockhash(
                commitment = option.commitment.name
            )

            val tx = AltudeTransactionBuilder(TransactionVersion.V0)
                .setFeePayer(feePayerPubKey)
                .addRangeInstruction(txInstructions)
                .setRecentBlockHash(blockhashInfo.blockhash)
                .addLookUpTables(lookupTables)
                .setSigners(listOf(HotSigner(defaultWallet)))

                .build()


            //val sign = Core.SignTransaction(privateKeyBytes,message)
            val serialized = Base64.encodeToString(
                tx.serialize(SerializeConfig(requireAllSignatures = false)),
                Base64.NO_WRAP
            )
            Result.success(serialized)
        } catch (e: Exception) {
            Result.failure(e)
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
                outputMint =option.outputMint,
                amount = rawAmount,
                slippageBps = option.slippageBps
            )
            val quoteResponse = Altude.json.decodeFromJsonElement<QuoteResponse>(service.quote(swapRequest.toQueryMap()).await())
            if(quoteResponse.isError)
                Result.failure<String>(Exception(quoteResponse.error))
            Result.success(quoteResponse)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getKeyPair(account: String = ""): Keypair {
        val seedData = StorageService.getDecryptedSeed(account)
        if (seedData != null) {
            if (seedData.type == "mnemonic") return Mnemonic(seedData.mnemonic).getKeyPair()
            return if (seedData.privateKey != null) KeyPair.solanaKeyPairFromPrivateKey(seedData.privateKey!!)
            else throw Error("No seed found in storage")
        } else throw Error("Please set seed first")
    }


}