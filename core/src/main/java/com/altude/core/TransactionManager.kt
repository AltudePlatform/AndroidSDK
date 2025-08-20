package com.altude.core

import android.util.Base64
import com.altude.core.data.CreateAccountOption
import com.altude.core.data.SendOption
import com.altude.core.Programs.AssociatedTokenAccountProgram
import com.altude.core.Programs.AssociatedTokenAccountProgram.deriveAtaAddress
import com.altude.core.Programs.MPLCore
import com.altude.core.Programs.Utility
import com.altude.core.Programs.TokenProgram
import com.altude.core.data.AccountData
import com.altude.core.data.CloseAccountOption
import com.altude.core.data.CreateNFTCollectionOption
import com.altude.core.data.MintOption
import com.altude.core.data.TransferOptions
import com.altude.core.helper.Mnemonic
import com.altude.core.model.EmptySignature
import com.altude.core.model.HotSigner
import com.altude.core.model.SolanaKeypair
import com.altude.core.model.AltudeTransactionBuilder
import com.altude.core.model.KeyPair
import com.altude.core.service.StorageService
import com.metaplex.signer.Signer
import foundation.metaplex.mplbubblegum.generated.bubblegum.BubblegumInstructions
import foundation.metaplex.mplbubblegum.generated.bubblegum.Collection
import foundation.metaplex.mplbubblegum.generated.bubblegum.Creator
import foundation.metaplex.mplbubblegum.generated.bubblegum.MetadataArgs
import foundation.metaplex.mplbubblegum.generated.bubblegum.TokenProgramVersion
import foundation.metaplex.mplbubblegum.generated.bubblegum.TokenStandard
import foundation.metaplex.rpc.Commitment
import foundation.metaplex.rpc.RPC
import foundation.metaplex.rpc.RpcGetLatestBlockhashConfiguration
import foundation.metaplex.solana.programs.SystemProgram
import foundation.metaplex.solana.transactions.AccountMeta
import foundation.metaplex.solana.transactions.SerializeConfig
import foundation.metaplex.solana.transactions.TransactionInstruction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import foundation.metaplex.solanapublickeys.PublicKey
import java.lang.Error
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.collections.listOf


object TransactionManager {

    private val quickNodeUrl =
        "https://multi-ultra-frost.solana-devnet.quiknode.pro/417151c175bae42230bf09c1f87acda90dc21968/"
    private val rpc = RPC(quickNodeUrl)
    val feePayerPubKey = PublicKey("BjLvdmqDjnyFsewJkzqPSfpZThE8dGPqCAZzVbJtQFSr") //ALZ8NJcf8JDL7j7iVfoyXM8u3fT3DoBXsnAU6ML7Sb5W BjLvdmqDjnyFsewJkzqPSfpZThE8dGPqCAZzVbJtQFSr

    suspend fun transferToken(option: SendOption): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val pubKeyMint = PublicKey(option.token)
            val pubKeyDestination = PublicKey(option.toAddress)
            val defaultWallet = getKeyPair(option.account)
            val sourceAta = deriveAtaAddress(defaultWallet.publicKey, pubKeyMint)
            val destinationAta = deriveAtaAddress(pubKeyDestination, pubKeyMint)

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

            val decimals = Utility.getTokenDecimals(option.token)
            val rawAmount = Utility.getRawQuantity(option.amount, decimals)

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
                RpcGetLatestBlockhashConfiguration(commitment = Commitment.finalized)
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

    suspend fun batchTransferToken(options: List<TransferOptions>): Result<String> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val transferInstructions = mutableListOf<TransactionInstruction>();
                val authorizedSignatures = mutableListOf<Signer>()
                options.forEach { option ->
                    val pubKeyMint = PublicKey(option.token)
                    val pubKeyDestination = PublicKey(option.toAddress)
                    val defaultWallet = getKeyPair(option.account)
                    val sourceAta = deriveAtaAddress(defaultWallet.publicKey, pubKeyMint)
                    val destinationAta = deriveAtaAddress(pubKeyDestination, pubKeyMint)

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
                    val rawAmount = Utility.getRawQuantity(option.amount, decimals)

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

                val blockhashInfo = rpc.getLatestBlockhash(
                    RpcGetLatestBlockhashConfiguration(commitment = Commitment.finalized)
                )

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
                    val ata = deriveAtaAddress(ownerKey, mintKey)
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
                    if (option.tokens.count() > 1)
                        throw Error("All Token accounts already created")
                    throw Error("Token account already created")
                }
                val authorizedSigner = HotSigner(SolanaKeypair(ownerKey, defaultWallet.secretKey))

                val payerSigner = EmptySignature(feePayerPubKey)
                val blockhashInfo = rpc.getLatestBlockhash(
                    RpcGetLatestBlockhashConfiguration(commitment = Commitment.finalized)
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
                Result.failure(e)
            }
        }

    suspend fun closeAccount(
        option: CloseAccountOption
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val defaultWallet = getKeyPair(option.account)
            val ownerKey = defaultWallet

            val txInstructions = mutableListOf<TransactionInstruction>()
            var authorized: PublicKey
            var isOwnerRequiredSignature = false
            option.tokens.forEach { token ->
                val mintKey = PublicKey(token)
                val ata = deriveAtaAddress(ownerKey.publicKey, mintKey)
                val ataInfo = Utility.getAccountInfo(ata.toBase58())
                if (ataInfo != null) {
                    val parsed = when (val data = ataInfo.data) {
                        is AccountData.Parsed -> data.parsed
                        is AccountData.Raw -> null
                        null -> null
                        else -> {null}
                    }
                    if (parsed?.info?.closeAuthority == feePayerPubKey.toBase58())
                        authorized = feePayerPubKey
                    else {
                        authorized = defaultWallet.publicKey
                        isOwnerRequiredSignature = true
                    }
                    val instruction = TokenProgram.closeAtaAccount(
                        ata = ata,
                        destination = feePayerPubKey,
                        authority = authorized
                    )
                    txInstructions.add(instruction)

                }
            }
            if (txInstructions.count() == 0) {
                if (option.tokens.count() > 1)
                    throw Error("All Token accounts already closed")
                throw Error("Token account already closed")
            }

            val blockhashInfo = rpc.getLatestBlockhash(
                RpcGetLatestBlockhashConfiguration(commitment = Commitment.finalized)
            )

            val tx = AltudeTransactionBuilder()
                .setFeePayer(feePayerPubKey)
                .addRangeInstruction(txInstructions)
                .setRecentBlockHash(blockhashInfo.blockhash)
                .build()
            if (isOwnerRequiredSignature)
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

    suspend fun getKeyPair(account: String = ""): SolanaKeypair {
        val seedData = StorageService.getDecryptedSeed(account)
        if (seedData != null) {
            if (seedData.type == "mnemonic") return Mnemonic(seedData.mnemonic).getKeyPair()
            return if (seedData.privateKey != null) KeyPair.solanaKeyPairFromPrivateKey(seedData.privateKey)
            else throw Error("No seed found in storage")
        } else throw Error("Please set seed first")
    }

    suspend fun createCollectionNft(
        option: CreateNFTCollectionOption
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            // --- Setup / accounts ----------------------------------------------------------------
            //val mintAccount = KeyPair.generate()                     // new mint keypair
            //val mintLayout: Long = 82
            val defaultWallet =
                getKeyPair("")                       // your wallet (payer / update authority)
            //val rent = rpc.getMinimumBalanceForRentExemption(usize = mintLayout.toULong()).toLong()

            // Program IDs (ensure these constants match your Utility)

            // Fresh keypair for the collection asset
            val collectionKeypair = KeyPair.generate()
            println(collectionKeypair)
            val transaction = MPLCore.createCollectionv2(
                payer = feePayerPubKey,
                updateAuthority = defaultWallet.publicKey,
                collectionMint = collectionKeypair.publicKey,
                name = option.name,
                uri = option.metadataUri
            )

            val blockhashInfo =
                rpc.getLatestBlockhash(RpcGetLatestBlockhashConfiguration(commitment = Commitment.finalized))

            val tx = AltudeTransactionBuilder()
                .setFeePayer(feePayerPubKey)
                .addInstruction(transaction)
                .setRecentBlockHash(blockhashInfo.blockhash)
                .setSigners(listOf(HotSigner(defaultWallet), HotSigner(collectionKeypair)))
                .build()

            // NOTE: tx.serialize(requireAllSignatures=false) means you still need to sign
            // required signers: fee payer (if signing), mintAccount (new account), defaultWallet (update authority) as needed
            val serialized = Base64.encodeToString(
                tx.serialize(SerializeConfig(requireAllSignatures = false)),
                Base64.NO_WRAP
            )

            // You must sign & send the tx (server-side or client-side):
            // rpc.sendTransaction(tx, listOf(feePayerKeypair, mintAccount /*, defaultWallet if needed */)).getOrThrow()

            Result.success(serialized)
        } catch (e: Exception) {
            Result.failure(e)

        }
    }

    suspend fun mintInstruction(
        uri: String,
        symbol: String,
        name: String,
        payer: PublicKey,
        owner: PublicKey,
        merkleTree: PublicKey,
        treeCreatorOrDelegate: PublicKey? = null,
        sellerFeeBasisPoints: Int,
        collection: PublicKey,
        collectionAuthority: PublicKey
    ): TransactionInstruction {

        val mintIx = MPLCore.mintV2(
            //treeAuthority = treeAuthority,
            leafOwner = owner,
            leafDelegate = owner, // often leaf owner themselves
            merkleTree = merkleTree,
            payer = payer,
            collectionAuthority = collectionAuthority,
            uri = uri,
            symbol = symbol,
            name = name,
            sellerFeeBasisPoints = sellerFeeBasisPoints,
            coreCollection = collection,
            treeCreatorOrDelegate = treeCreatorOrDelegate, // often leaf owner themselves? : owner ,
            assetData = null,

        )
        return mintIx
    }

    suspend fun mint(
        option: MintOption
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            // --- Setup / accounts ----------------------------------------------------------------
            //val mintAccount = KeyPair.generate()                     // new mint keypair
            //val mintLayout: Long = 82
            val defaultWallet =
                getKeyPair(option.owner)                       // your wallet (payer / update authority)
            val merkleTree = KeyPair.generate()
//            println("tree: ${merkleTree.publicKey}")
//            println("merkleRaw length = ${merkleTree.publicKey.publicKeyBytes.size}")
//            val createTreeTx = createTreeInstruction(
//                rpc = rpc,
//                payer = feePayerPubKey,
//                owner = defaultWallet.publicKey,
//                merkleTree = merkleTree.publicKey,
//                maxDepth = 32,
//                maxBufferSize = 64
//            )
            val ixs = MPLCore. createTreeV2(
                rpc = rpc,
                payer = feePayerPubKey,
                merkleTree = merkleTree.publicKey,
                treeCreator = feePayerPubKey,
                maxDepth = 14,
                maxBufferSize = 64,
                //can = 0,
                isPublic = true
            )
            val mintIx = mintInstruction(
                uri = option.uri,
                symbol = option.symbol,
                name = option.name,
                payer = feePayerPubKey,
                owner = defaultWallet.publicKey,
                merkleTree = merkleTree.publicKey, //PublicKey("7GzoPkZRSCaHvH3yYFpFTfm2pQGhdXZ8Tp1rTB3ughBb"), // 14QSPv5BtZCh8itGrUCu2j7e7A88fwZo3cAjxi4R5Fgj 7GzoPkZRSCaHvH3yYFpFTfm2pQGhdXZ8Tp1rTB3ughBb
                sellerFeeBasisPoints = option.sellerFeeBasisPoints,
                collection = PublicKey(option.collection),
                treeCreatorOrDelegate = feePayerPubKey,
                collectionAuthority = defaultWallet.publicKey


            )

            val blockhashInfo =
                rpc.getLatestBlockhash(RpcGetLatestBlockhashConfiguration(commitment = Commitment.finalized))

            val tx = AltudeTransactionBuilder()
                .setFeePayer(feePayerPubKey)
                //.addRangeInstruction(ixs)
                .addInstruction(mintIx)
                .setRecentBlockHash(blockhashInfo.blockhash)
                .setSigners(listOf(HotSigner(defaultWallet)))//HotSigner(merkleTree),
                .build()
            //tx.sign(listOf(HotSigner(defaultWallet), HotSigner(merkleTree)))
            // NOTE: tx.serialize(requireAllSignatures=false) means you still need to sign
            // required signers: fee payer (if signing), mintAccount (new account), defaultWallet (update authority) as needed
            val serialized = Base64.encodeToString(
                tx.serialize(SerializeConfig(requireAllSignatures = false)),
                Base64.NO_WRAP
            )

            // You must sign & send the tx (server-side or client-side):
            // rpc.sendTransaction(tx, listOf(feePayerKeypair, mintAccount /*, defaultWallet if needed */)).getOrThrow()

            Result.success(serialized)
        } catch (e: Exception) {
            Result.failure(e)

        }
    }
    /** Valid canopy lengths are: 0 or (2^k - 2), with k >= 2. */
    fun isValidCanopyLen(len: Int): Boolean {
        if (len == 0) return true
        val x = len + 2
        return x >= 4 && (x and (x - 1)) == 0
    }

    /** Choose a valid canopy LENGTH for this maxDepth. */
    fun normalizeCanopyLen(maxDepth: Int, requestedLen: Int): Int {
        if (requestedLen == 0 && maxDepth <= 14) return 0
        if (requestedLen > 0 && isValidCanopyLen(requestedLen) && requestedLen < maxDepth) return requestedLen

        var k = 2
        var best = 2
        while (true) {
            val cand = (1 shl k) - 2
            if (cand >= maxDepth) break
            best = cand
            k++
        }
        return best
    }

// ---- canonical serializer for CreateTreeConfigV2 args ----
    /**
     * Serializes CreateTreeConfigV2 args in the order Metaplex/Bubblegum expects:
     *   u32 max_depth
     *   u32 max_buffer_size
     *   u32 canopy_length      (LENGTH in nodes)
     *   Option<bool> public    (encoded as: 0u8 => None, 1u8 + bool => Some(value))
     *
     * Returns full instruction data = discriminator + borsh-encoded args.
     */
    fun serializeCreateTreeConfigV2DataDebug(
        maxDepth: Int,
        maxBufferSize: Int,
        canopyLength: Int,
        isPublic: Boolean? = null
    ): ByteArray {
        // discriminator from mpl-bubblegum
        val discriminator = byteArrayOf(55, 99, 95, -41, -114, -53, -29, -51)

        // build arg bytes using little-endian u32 and Option<bool>
        val argBuf = ByteBuffer.allocate(4 + 4 + 4 + 1 + 0) // 13 bytes for args; Option may be 1 + 1
        argBuf.order(ByteOrder.LITTLE_ENDIAN)
        argBuf.putInt(maxDepth)
        argBuf.putInt(maxBufferSize)
        argBuf.putInt(canopyLength)
        when (isPublic) {
            null -> argBuf.put(0.toByte()) // None
            false -> { argBuf.put(1.toByte()); argBuf.put(0.toByte()) } // Some(false)
            true ->  { argBuf.put(1.toByte()); argBuf.put(1.toByte()) } // Some(true)
        }
        val args = argBuf.array().copyOf(argBuf.position()) // trim to actual length

        // return discriminator + args
        return discriminator + args
    }

    // ---- debug + create function to use in your flow ----
    suspend fun createTreeV2withDebug(
        rpc: RPC,
        payer: PublicKey,
        merkleTree: PublicKey,
        treeCreator: PublicKey,
        maxDepth: Int,
        maxBufferSize: Int,
        requestedCanopyLen: Int = 0,
        isPublic: Boolean? = null,
        logWrapper: PublicKey = Utility.MPL_NOOP,
        compressionProgram: PublicKey = Utility.MPL_ACCOUNT_COMPRESSION,
        systemProgram: PublicKey = Utility.SYSTEM_PROGRAM_ID
    ): List<TransactionInstruction> {

        // 1) Normalize canopy LENGTH (nodes)
        val canopyLen = normalizeCanopyLen(maxDepth, requestedCanopyLen)

        // 2) Use canonical helper to compute exact account size expected on-chain
        val totalAccountSize = when {
            maxDepth == 14 && maxBufferSize == 64 -> 32_896
            maxDepth == 14 && maxBufferSize == 256 -> 65_792
            else -> throw IllegalArgumentException("Unsupported depth/buffer size combo")
        }

        // Diagnostics: sizes and canopy info
        println("DEBUG: createTreeV2 params -> maxDepth=$maxDepth, maxBufferSize=$maxBufferSize, requestedCanopy=$requestedCanopyLen, normalizedCanopy=$canopyLen")
        println("DEBUG: totalAccountSize (bytes) = $totalAccountSize")

        // 3) Serialize instruction data (discriminator + args)
        val data = serializeCreateTreeConfigV2DataDebug(
            maxDepth = maxDepth,
            maxBufferSize = maxBufferSize,
            canopyLength = canopyLen,
            isPublic = isPublic
        )

        fun hex(b: ByteArray) = b.joinToString("") { String.format("%02x", it) }
        println("DEBUG: createTreeConfigV2 data length = ${data.size} bytes")
        println("DEBUG: createTreeConfigV2 data hex = ${hex(data)}")

        // 4) Make createAccount instruction using the exact size
        val lamports = rpc.getMinimumBalanceForRentExemption(totalAccountSize.toULong())
        println("DEBUG: rent-exempt lamports = $lamports")

        val createTreeAccountIx = SystemProgram.createAccount(
            fromPublicKey = payer,
            newAccountPublickey = merkleTree,
            lamports = lamports.toLong(),
            space = totalAccountSize.toLong(),
            programId = compressionProgram
        )

        // 5) Build the config instruction
        val treeConfigPda = MPLCore.findTreeConfigPda(merkleTree)
        val keys = listOf(
            AccountMeta(treeConfigPda, isSigner = false, isWritable = true),
            AccountMeta(merkleTree, isSigner = false, isWritable = true),
            AccountMeta(payer, isSigner = true, isWritable = true),
            AccountMeta(treeCreator, isSigner = true, isWritable = false),
            AccountMeta(logWrapper, isSigner = false, isWritable = false),
            AccountMeta(compressionProgram, isSigner = false, isWritable = false),
            AccountMeta(systemProgram, isSigner = false, isWritable = false),
        )

        val createTreeConfigV2Ix = TransactionInstruction(
            programId = Utility.MPL_BUBBLEGUM_PROGRAM_ID,
            keys = keys,
            data = data
        )

        // 6) Print one more check: what canopy the on-chain program would infer from allocated size:
        //    canopy_len_inferred = (allocated_space - headerSize - treeSize) / 32
        // We can't compute headerSize/treeSize reliably here without the exact serializers, but printing allows you to compare.
        println("DEBUG: created instructions; now return them (send & inspect logs).")

        return listOf(createTreeAccountIx, createTreeConfigV2Ix)
    }
//    suspend fun createTreeInstruction(
//        rpc: RPC,
//        payer: PublicKey,
//        owner: PublicKey,
//        merkleTree: PublicKey,
//        maxDepth: Int,
//        maxBufferSize: Int,
//        canopyDepth: Int = 0
//    ): List<TransactionInstruction> {
//        // 1. Calculate space
//        val space = Utility.getConcurrentMerkleTreeSize(
//            maxDepth, maxBufferSize,
//            canopyDepth = canopyDepth
//        )
//        val treeAuthorityPda = Utility. findTreeAuthorityPda(merkleTree)
//        // 2. Get rent-exemption lamports
//        val lamports = rpc.getMinimumBalanceForRentExemption(space.toULong())
//
//        // 3. Create Merkle tree account (SystemProgram)
//        val createTreeAccountIx = SystemProgram.createAccount(
//            fromPublicKey = payer,
//            newAccountPublickey = merkleTree,
//            lamports = lamports.toLong(),
//            space = space.toLong(),
//            programId = Utility.ACCOUNT_COMPRESSION_PROGRAM_ID,
//        )
//
//        // 4. Create tree config instruction
//        val createTreeConfigIx = BubblegumInstructions.createTree(
//            merkleTree = merkleTree,          // actual Merkle tree account
//            treeAuthority = treeAuthorityPda, // PDA derived with bump
//            payer = payer,
//            treeCreator = owner,              // the owner/creator
//            maxDepth = maxDepth.toUInt(),
//            maxBufferSize = maxBufferSize.toUInt(),
//            public = true,
//            logWrapper = Utility. NOOP_PROGRAM,
//            compressionProgram = Utility. ACCOUNT_COMPRESSION_PROGRAM_ID,
//            systemProgram = Utility. SYSTEM_PROGRAM_ID
//        )
//
//        // 5. Build transaction
//        return listOf(
//            createTreeAccountIx,
//            createTreeConfigIx
//        )
//    }
//
//

//    suspend fun createTreeInstruction(
//        rpc: RPC,
//        payer: PublicKey,
//        owner: PublicKey,
//        merkleTree: PublicKey,
//        maxDepth: Int,
//        maxBufferSize: Int,
//        canopyDepth: Int = 0
//    ): List<TransactionInstruction> {
//        // 1. Calculate space needed for Merkle tree
//        val space = Utility.getConcurrentMerkleTreeSize(maxDepth, maxBufferSize, canopyDepth)
//
//        // 2. Derive the tree authority PDA (Bubblegum expects this)
//        val (treeAuthorityPda, bump) = PublicKey.findProgramAddress(
//            listOf(
//                merkleTree.toByteArray(),
//                "tree_authority".toByteArray()
//            ),
//            Utility.BUBBLEGUM_PROGRAM_ID
//        )
//        println("treepda: $treeAuthorityPda ")
//        // 3. Get rent-exemption lamports
//        val lamports = rpc.getMinimumBalanceForRentExemption(space.toULong())
//
//        // 4. Create Merkle tree account
//        val createTreeAccountIx = SystemProgram.createAccount(
//            fromPublicKey = payer,
//            newAccountPublickey = merkleTree,
//            lamports = lamports.toLong(),
//            space = space.toLong(),
//            programId = Utility.ACCOUNT_COMPRESSION_PROGRAM_ID
//        )
//
//        // 5. Create tree config instruction
//        val createTreeConfigIx = BubblegumInstructions.createTree(
//            merkleTree = merkleTree,           // the actual Merkle tree account
//            treeAuthority = treeAuthorityPda,  // correct PDA
//            payer = payer,
//            treeCreator = owner,
//            maxDepth = maxDepth.toUInt(),
//            maxBufferSize = maxBufferSize.toUInt(),
//            public = true,
//            logWrapper = Utility.NOOP_PROGRAM,
//            compressionProgram = Utility.ACCOUNT_COMPRESSION_PROGRAM_ID,
//            systemProgram = Utility.SYSTEM_PROGRAM_ID
//        )
//
//
//        // 6. Return all instructions in order
//        return listOf(
//            createTreeAccountIx,
//            createTreeConfigIx
//        )
//    }
}
//            // --- 1) Create & initialize mint ----------------------------------------------
//            val createMintIx = SystemProgram.createAccount(
//                fromPublicKey = feePayerPubKey,
//                newAccountPublickey = mintAccount.publicKey,      // <-- MUST be mintAccount
//                lamports = rent,
//                space = mintLayout,
//                programId = Utility.TOKEN_PROGRAM_ID
//            )
//
//            val initMintIx = TokenProgram.initializeMint(
//                mint = mintAccount.publicKey,
//                decimals = 0u,
//                mintAuthority = defaultWallet.publicKey,            // keep as authority if desired
//                //freezeAuthority = defaultWallet.publicKey
//            )
//
//            // --- 2) Create ATA for the payer and mint 1 token ----------------------------
//            val ata = deriveAtaAddress(defaultWallet.publicKey, mintAccount.publicKey) // owner's ATA
//            val createAtaIx = AssociatedTokenAccountProgram.createAssociatedTokenAccount(
//                ata = ata,
//                feePayer = feePayerPubKey,
//                owner = defaultWallet.publicKey,                        // owner should match who receives the minted token
//                mint = mintAccount.publicKey
//            )
//
//            val mintToIx = TokenProgram.mintTo(
//                mint = mintAccount.publicKey,
//                destination = ata,
//                mintAuthority = defaultWallet.publicKey,
//                amount = 1uL
//            )
//
//            // --- 3) Build metadata DataV2 + collectionDetails ---------------------------
//            val dataV2 = DataV2(
//                name = option.name,
//                symbol = option.symbol,
//                uri = option.metadataUri,
//                sellerFeeBasisPoints = option.sellerFeeBasisPoints.toUShort(),
//                creators = null,
//                collection = null,
//                uses = null
//            )
//            val collectionDetails = CollectionDetails.V1(size = 0u) // marks this NFT as a collection
//
//            // --- 4) Derive Metadata PDA & Master Edition PDA ---------------------------
//            val metadataPda = Utility.derivePda(mintAccount.publicKey)
//
//
//            // --- 5) CreateMetadataAccountV3 instruction (use the generated class signature)
//            val metadataIx = TokenMetadataInstructions.CreateMetadataAccountV3(
//                metadata = metadataPda,
//                mint = mintAccount.publicKey,
//                mintAuthority = defaultWallet.publicKey,   // who can mint (update) metadata
//                payer = feePayerPubKey,                    // who pays account creation
//                updateAuthority = defaultWallet.publicKey, // update authority
//                createMetadataAccountArgsV3 = CreateMetadataAccountArgsV3(
//                    data = dataV2,
//                    isMutable = true,
//                    collectionDetails = collectionDetails
//                ),
//                systemProgram = Utility.SYSTEM_PROGRAM_ID,      // <-- Must be system program
//                rent = Utility.SYSVAR_RENT_PUBKEY
//            )
//
//            val masterEditionPda = Utility.derivePdaMasterEdition(mintAccount.publicKey)
//            // --- 6) CreateMasterEdition instruction ------------------------------------
//            val masterEditionIx = TokenMetadataInstructions.CreateMasterEdition(
//                edition = masterEditionPda,
//                mint = mintAccount.publicKey,
//                updateAuthority = defaultWallet.publicKey,
//                mintAuthority = defaultWallet.publicKey,
//                payer = feePayerPubKey,
//                metadata = metadataPda,
//                tokenProgram = Utility.TOKEN_PROGRAM_ID,
//                systemProgram = Utility.SYSTEM_PROGRAM_ID,
//                rent = Utility.SYSVAR_RENT_PUBKEY
//            )
//
//            // --- 7) Build transaction ---------------------------------------------------
//            val txInstructions = mutableListOf<TransactionInstruction>()
//            txInstructions += createMintIx
//            txInstructions += initMintIx
//            txInstructions += createAtaIx
//            txInstructions += mintToIx
//            txInstructions += metadataIx
//            txInstructions += masterEditionIx
