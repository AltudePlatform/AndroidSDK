package com.altude.core.Programs

import com.altude.core.data.ChangeLog
import com.altude.core.data.MintV2InstructionData
import com.funkatronics.kborsh.BorshEncoder
import foundation.metaplex.mplbubblegum.generated.bubblegum.Collection
import foundation.metaplex.mplbubblegum.generated.bubblegum.Creator
import foundation.metaplex.mplbubblegum.generated.bubblegum.MetadataArgs
import foundation.metaplex.mplbubblegum.generated.bubblegum.TokenProgramVersion
import foundation.metaplex.mplbubblegum.generated.bubblegum.TokenStandard
import foundation.metaplex.mplbubblegum.generated.bubblegum.hook.ConcurrentMerkleTreeHeaderData
import foundation.metaplex.rpc.RPC
import foundation.metaplex.solana.programs.SystemProgram
import foundation.metaplex.solana.transactions.AccountMeta
import foundation.metaplex.solana.transactions.TransactionInstruction
import foundation.metaplex.solanapublickeys.PublicKey
import java.security.MessageDigest
import kotlin.math.pow

object MPLCore {
    private const val PUBKEY_BYTES = 32
    fun createCollectionv2(
        payer: PublicKey,
        updateAuthority: PublicKey,
        collectionMint: PublicKey,
        name: String,
        uri: String
    ): TransactionInstruction{
        return TransactionInstruction(
            keys = listOf(
                AccountMeta(collectionMint, true, true), // writable + signer
                AccountMeta(updateAuthority, false, false),      // your update authority
                AccountMeta(payer, true, true),       // payer (signer)
                AccountMeta(PublicKey("11111111111111111111111111111111"), false, false) // system program
            ),
            programId = Utility.mplCoreProgramId,
            data = Utility. serializeCreateCollectionV2(
                name = name,
                uri = uri
            )
        )
    }

    suspend fun mintV2(
        uri: String,                       // URI pointing to the NFT metadata (JSON, typically on IPFS or Arweave)
        symbol: String,                    // Short ticker or symbol for the NFT (e.g., "ALT")
        name: String,                      // Name of the NFT
        payer: PublicKey,                  // The account paying for transaction fees and account creation (tree owner can also be payer)
        leafOwner: PublicKey,              // Owner of the NFT (leaf in the Merkle tree). They can receive or later transfer the NFT
        merkleTree: PublicKey,             // Public key of the Merkle tree where the compressed NFT will be stored
        sellerFeeBasisPoints: Int,         // Royalty fee in basis points (1% = 100 basis points) for secondary sales
        coreCollection: PublicKey? = null, // Optional core collection account (used if NFT is part of a collection)
        leafDelegate: PublicKey? = null,   // Optional delegate who can act on behalf of the leafOwner (e.g., transfer, burn)
        treeCreatorOrDelegate: PublicKey? = null, // Optional tree authority; defaults to payer if null. Needed for certain tree operations
        collectionAuthority: PublicKey? = null,   // Optional collection authority; required if updating or verifying collection metadata
        assetData: ByteArray? = null       // Optional custom binary data for off-chain references or dApp-specific features
        //assetDataSchema: AssetDataSchema? = null // Optional schema for validating the assetData structure

    ): TransactionInstruction {
        val programId = Utility.MPL_BUBBLEGUM_PROGRAM_ID// mplBubblegum

        // Resolve defaults
        val treeConfig = Utility.findTreeConfigPda(merkleTree)
        val logWrapper = Utility.MPL_NOOP  // mplNoop
        val compressionProgram = Utility.MPL_ACCOUNT_COMPRESSION// mplAccountCompression
        val mplCoreProgram = Utility.mplCoreProgramId // mplCore
        val mplCoreCpiSigner = if (coreCollection != null) {
            PublicKey("CbNY3JiXdXNE9tPNEk1aRZVEkWdj2v7kfJLNQwZZgpXk")
        } else null
        val systemProgram = Utility.SYSTEM_PROGRAM_ID

        // Build metadata args (v2)
        val metadata = MetadataArgs(
            name = name,
            symbol = symbol,
            uri = uri,
            sellerFeeBasisPoints = sellerFeeBasisPoints.toUShort(),
            creators = listOf(Creator(payer, true, 100u)),
            primarySaleHappened = false,
            isMutable = true,
            editionNonce = null,
            tokenStandard = TokenStandard.NonFungible,
            collection = if (coreCollection != null) Collection(true, coreCollection) else null,
            uses = null,
            tokenProgramVersion = TokenProgramVersion.Original
        )

        // Serialize instruction data
        val data = MintV2InstructionData(
            discriminator = byteArrayOf(120, 121, 23, 146.toByte(), 173.toByte(), 110,
                199.toByte(), 205.toByte()
            ),
            metadata = metadata,
            assetData = assetData,
            assetDataSchema = null
        ).serialize() // you'll need a serializer matching the TS struct

        // Accounts list in correct index order
        val keys = listOfNotNull(
            AccountMeta(treeConfig, isWritable = true, isSigner = false),
            AccountMeta(payer, isWritable = true, isSigner = true),
            treeCreatorOrDelegate?.let { AccountMeta(it, isWritable = false, isSigner = true) }
                ?: AccountMeta(payer, isWritable = false, isSigner = true),
            collectionAuthority?.let { AccountMeta(it, isWritable = false, isSigner = true) }
                ?: AccountMeta(treeCreatorOrDelegate ?: payer, isWritable = false, isSigner = true),
            AccountMeta(leafOwner, isWritable = false, isSigner = false),
            leafDelegate?.let { AccountMeta(it, isWritable = false, isSigner = false) },
            AccountMeta(merkleTree, isWritable = true, isSigner = false),
            coreCollection?.let { AccountMeta(it, isWritable = true, isSigner = false) },
            mplCoreCpiSigner?.let { AccountMeta(it, isWritable = false, isSigner = false) },
            AccountMeta(logWrapper, isWritable = false, isSigner = false),
            AccountMeta(compressionProgram, isWritable = false, isSigner = false),
            AccountMeta(mplCoreProgram, isWritable = false, isSigner = false),
            AccountMeta(systemProgram, isWritable = false, isSigner = false)
        )

        return TransactionInstruction(
            programId = programId,
            keys = keys,
            data = data
        )
    }

    suspend fun findTreeConfigPda(merkleTree: PublicKey): PublicKey {
        // matches Umi/TS: findTreeConfigPda(context, { merkleTree })
        val seeds = listOf(merkleTree.publicKeyBytes)
        return PublicKey.findProgramAddress(seeds, Utility.MPL_BUBBLEGUM_PROGRAM_ID).address
    }

    // --- Size helper (same math you already use) --------------------------------
    fun getMerkleTreeSize(maxDepth: Int, maxBufferSize: Int, canopyDepth: Int = 0): Long {
        // Recommended: reuse your Utility.getConcurrentMerkleTreeSize if you have it.
        return Utility.concurrentMerkleTreeAccountSize(maxDepth, maxBufferSize, canopyDepth).toLong()
    }



// ---------------- Discriminator ----------------
    // 1️⃣ Discriminator
    fun createTreeV2Discriminator(): ByteArray {
        val name = "global:create_tree_v2"
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(name.toByteArray())
        return hash.copyOfRange(0, 8)
    }

    // ---------- Header (ConcurrentMerkleTreeHeaderData::V1) ----------
// Fields: maxBufferSize(u32)=4, maxDepth(u32)=4, authority(pubkey)=32,
// creationSlot(u64)=8, isBatchInitialized(bool)=1, padding([u8;5])=5
// PLUS data-enum tag (u8)=1  => total 4+4+32+8+1+5+1 = 55
    fun getConcurrentMerkleTreeHeaderV1Size(): Int = 55

    // ---------- ChangeLog(maxDepth) ----------
// root(pubkey)=32
// pathNodes([pubkey; maxDepth]) = maxDepth * 32
// index(fixSerializer(u32, 8)) = 8 bytes
    fun getChangeLogSize(maxDepth: Int): Int =
        PUBKEY_BYTES + (maxDepth * PUBKEY_BYTES) + 8

    // ---------- RightMostPath(maxDepth) ----------
// proof([pubkey; maxDepth]) = maxDepth * 32
// leaf(pubkey)=32
// index(u32)=4
// padding(u32)=4
    fun getRightMostPathSize(maxDepth: Int): Int =
        (maxDepth * PUBKEY_BYTES) + PUBKEY_BYTES + 4 + 4

    // ---------- ConcurrentMerkleTree(maxDepth, maxBufferSize) ----------
// sequenceNumber(u64)=8, activeIndex(u64)=8, bufferSize(u64)=8
// changeLogs([ChangeLog; maxBufferSize]) = maxBufferSize * getChangeLogSize(maxDepth)
// rightMostPath(Path) = getRightMostPathSize(maxDepth)
    fun getConcurrentMerkleTreeV1Size(maxDepth: Int, maxBufferSize: Int): Int {
        val sequenceNumber = 8
        val activeIndex = 8
        val bufferSizeField = 8
        val changeLogs = maxBufferSize * getChangeLogSize(maxDepth)
        val rightMostPath = getRightMostPathSize(maxDepth)
        return sequenceNumber + activeIndex + bufferSizeField + changeLogs + rightMostPath
    }

    // ---------- Canopy (remainder) ----------
// canopySize = 32 * max( (1 << (canopyDepth + 1)) - 2, 0 )
    fun getCanopySize(canopyDepth: Int): Int {
        if (canopyDepth <= 0) return 0
        val nodes = (1 shl (canopyDepth + 1)) - 2
        return PUBKEY_BYTES * nodes
    }

    // ---------- Full v1 Merkle Tree Account size ----------
// account discriminator(u8)=1
    fun getMerkleTreeV1Space(maxDepth: Int, maxBufferSize: Int, canopyDepth: Int = 0): Int {
        val discriminatorSize = 1
        val headerSize = getConcurrentMerkleTreeHeaderV1Size()
        val treeSize = getConcurrentMerkleTreeV1Size(maxDepth, maxBufferSize)
        val canopySize = getCanopySize(canopyDepth)
        return discriminatorSize + headerSize + treeSize + canopySize
    }
    // 2️⃣ Serialize instruction data
    fun serializeCreateTreeConfigV1Data(
        maxDepth: UInt,
        maxBufferSize: UInt,
        isPublic: Boolean? = null
    ): ByteArray {
        val buffer = mutableListOf<Byte>()

        // discriminator for CreateTreeConfig (V1)
        val discriminator = byteArrayOf(
            165.toByte(), 83.toByte(), 136.toByte(), 142.toByte(),
            89.toByte(), 202.toByte(), 47.toByte(), 220.toByte()
        )
        buffer.addAll(discriminator.toList())

        // u32 LE
        buffer.addAll(maxDepth.toByteArrayLE().toList())
        buffer.addAll(maxBufferSize.toByteArrayLE().toList())

        // Option<bool>
        // Anchor encodes Option<T> as: 0 = None, 1 = Some
        buffer.add(
            when (isPublic) {
                null -> 0.toByte()   // None
                true -> 1.toByte()   // Some(true)
                false -> 1.toByte()  // Some(false)
            }
        )

        // If Some(false) or Some(true), Anchor expects the bool value after the Option byte
        if (isPublic != null) {
            buffer.add(if (isPublic) 1 else 0)
        }

        return buffer.toByteArray()
    }
    fun serializeCreateTreeConfigV2Data(
        maxDepth: UInt,
        maxBufferSize: UInt,
        isPublic: Boolean? = null
    ): ByteArray {
        val buffer = mutableListOf<Byte>()

        // discriminator
        buffer.addAll(createTreeV2Discriminator().toList())

        // u32 LE
        buffer.addAll(maxDepth.toByteArrayLE().toList())
        buffer.addAll(maxBufferSize.toByteArrayLE().toList())

        // Option<bool>
        // Anchor encodes Option as: 0 = None, 1 = Some
        buffer.add(
            when (isPublic) {
                null -> 0.toByte()   // None
                true -> 1.toByte()   // Some(true)
                false -> 1.toByte()  // Some(false)
            }
        )

        // If Some(false), Anchor expects the bool value after the Option byte
        if (isPublic != null) {
            buffer.add(if (isPublic) 1 else 0)
        }

        return buffer.toByteArray()
    }

    // 3️⃣ UInt -> 4-byte LE
    fun UInt.toByteArrayLE(): ByteArray {
        return byteArrayOf(
            (this and 0xFFu).toByte(),
            ((this shr 8) and 0xFFu).toByte(),
            ((this shr 16) and 0xFFu).toByte(),
            ((this shr 24) and 0xFFu).toByte()
        )
    }

    fun getConcurrentMerkleTreeAccountSize(
        maxDepth: Int,
        maxBufferSize: Int,
        canopyDepth: Int
    ): Int {
        val HEADER_SIZE = 2 + 4 + 4 + 32 + 8 // version + maxBufferSize + maxDepth + authority + creationSlot
        val CHANGELOG_ENTRY_SIZE = 32 + 4 + 4 + 8
        val RIGHTMOST_PATH_ENTRY_SIZE = 32 + 4 + 8

        val changelogSize = CHANGELOG_ENTRY_SIZE * maxBufferSize
        val rightmostPathSize = RIGHTMOST_PATH_ENTRY_SIZE * maxDepth

        val nodeCount = (1 shl maxDepth) - 1
        val nodeSize = nodeCount * 32

        val canopyNodeCount = (1 shl canopyDepth) - 1
        val canopySize = canopyNodeCount * 32

        return HEADER_SIZE + changelogSize + rightmostPathSize + nodeSize + canopySize
    }


    // --- Main function to create tree ---
    suspend fun createTree(
        rpc: RPC,
        payer: PublicKey,
        merkleTree: PublicKey,
        treeCreator: PublicKey,
        maxDepth: Int,
        maxBufferSize: Int,
        requestedCanopyLen: Int = 0,
        isPublic: Boolean? = null,
        logWrapper: PublicKey = Utility.SPL_NOOP,
        compressionProgram: PublicKey = Utility.SPL_ACCOUNT_COMPRESSION,
        systemProgram: PublicKey = Utility.SYSTEM_PROGRAM_ID,
        bubblegumProgram: PublicKey = Utility.MPL_BUBBLEGUM_PROGRAM_ID
    ): List<TransactionInstruction> {


        val space = getMerkleTreeV1Space(
            maxDepth = 14,
            maxBufferSize = 64,
            canopyDepth = 8   // always 0 if using createTreeConfig v1
        )

        val lamports = rpc.getMinimumBalanceForRentExemption(space.toULong())

        val createTreeIx = SystemProgram.createAccount(
            fromPublicKey = payer,
            newAccountPublickey = merkleTree,
            lamports = lamports.toLong(),
            space = space.toLong(),
            programId = compressionProgram
        )



        // Compute treeConfig PDA
        val treeConfigPda = MPLCore.findTreeConfigPda(merkleTree)

        //Prepare keys for CreateTreeV2 instruction
        val keys = listOf(
            AccountMeta(treeConfigPda, isSigner = false, isWritable = true),
            AccountMeta(merkleTree, isSigner = false, isWritable = true),
            AccountMeta(payer, isSigner = true, isWritable = true),
            AccountMeta(treeCreator, isSigner = true, isWritable = false),
            AccountMeta(logWrapper, isSigner = false, isWritable = false),
            AccountMeta(compressionProgram, isSigner = false, isWritable = false), // MUST be Bubblegum program
            AccountMeta(systemProgram, isSigner = false, isWritable = false)
        )

        // Serialize instruction data
        val data = serializeCreateTreeConfigV1Data(
            maxDepth = maxDepth.toUInt(),
            maxBufferSize = maxBufferSize.toUInt(),
            isPublic = isPublic
        )

        val createTreeConfigV2Ix = TransactionInstruction(
            programId = bubblegumProgram,
            keys = keys,
            data = data
        )

        println("CreateTreeV2 → depth=$maxDepth buffer=$maxBufferSize lamports=$lamports space=$space")

        return listOf(createTreeIx, createTreeConfigV2Ix)
    }
    suspend fun createTreeV2(
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
        systemProgram: PublicKey = Utility.SYSTEM_PROGRAM_ID,
        bubblegumProgram: PublicKey = Utility.MPL_BUBBLEGUM_PROGRAM_ID
    ): List<TransactionInstruction> {



        val space = getMerkleTreeV1Space(
            maxDepth = 14,
            maxBufferSize = 64,
            canopyDepth = 0   // always 0 if using createTreeConfig v1
        )

        val lamports = rpc.getMinimumBalanceForRentExemption(space.toULong())

        val createTreeIx = SystemProgram.createAccount(
            fromPublicKey = payer,
            newAccountPublickey = merkleTree,
            lamports = lamports.toLong(),
            space = space.toLong(),
            programId = compressionProgram
        )



        // Compute treeConfig PDA
        val treeConfigPda = MPLCore.findTreeConfigPda(merkleTree)

        //Prepare keys for CreateTreeV2 instruction
        val keys = listOf(
            AccountMeta(treeConfigPda, isSigner = false, isWritable = true),
            AccountMeta(merkleTree, isSigner = false, isWritable = true),
            AccountMeta(payer, isSigner = true, isWritable = true),
            AccountMeta(treeCreator, isSigner = true, isWritable = false),
            AccountMeta(logWrapper, isSigner = false, isWritable = false),
            AccountMeta(compressionProgram, isSigner = false, isWritable = false), // MUST be Bubblegum program
            AccountMeta(systemProgram, isSigner = false, isWritable = false)
        )

        // Serialize instruction data
        val data = serializeCreateTreeConfigV2Data(
            maxDepth = maxDepth.toUInt(),
            maxBufferSize = maxBufferSize.toUInt(),
            isPublic = isPublic
        )

        val createTreeConfigV2Ix = TransactionInstruction(
            programId = bubblegumProgram,
            keys = keys,
            data = data
        )

        println("CreateTreeV2 → depth=$maxDepth buffer=$maxBufferSize lamports=$lamports space=$space")

        return listOf(createTreeIx, createTreeConfigV2Ix)
    }

    fun getValidCanopy(maxDepth: Int, canopy: Int): Int {
        // Allow 0 only if tree is small enough
        if (canopy == 0 && maxDepth <= 14) {
            return 0
        }

        // Force canopy into valid form: 2^k - 2
        var k = 2
        var valid = 2
        while (true) {
            val candidate = (1 shl k) - 2 // 2^k - 2
            if (candidate >= maxDepth) break
            valid = candidate
            k++
        }
        return valid
    }
}

sealed class Option<out T>
data class Some<T>(val value: T) : Option<T>()
object None : Option<Nothing>()