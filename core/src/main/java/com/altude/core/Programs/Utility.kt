package com.altude.core.Programs

import com.altude.core.data.AccountData
import com.altude.core.data.AccountInfoResponse
import com.altude.core.data.AccountInfoValue
import com.altude.core.data.ConcurrentMerkleTreeHeaderDataSerializer
import com.altude.core.data.KtSerializer
import com.altude.core.data.MerkleTreeAccountData
import com.altude.core.network.QuickNodeRpc
import diglol.crypto.internal.toByteArray
import foundation.metaplex.mplbubblegum.generated.bubblegum.hook.ChangeLog
import foundation.metaplex.mplbubblegum.generated.bubblegum.hook.ConcurrentMerkleTree
import foundation.metaplex.mplbubblegum.generated.bubblegum.hook.Path
import foundation.metaplex.solanapublickeys.Pda
import foundation.metaplex.solanapublickeys.PublicKey
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.pow

object  Utility {

    val TOKEN_PROGRAM_ID  = PublicKey("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    val SYSTEM_PROGRAM_ID  = PublicKey("11111111111111111111111111111111")
    val ATA_PROGRAM_ID  = PublicKey("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
    val METADATA_PROGRAM_ID = PublicKey("metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s")
    val SYSVAR_RENT_PUBKEY  = PublicKey("SysvarRent111111111111111111111111111111111")
    val SPL_NOOP  = PublicKey("noopb9bkMVfRPU8AsbpTUg8AQkHtKwMYZiFUjNRtMmV")

    val mplCoreProgramId = PublicKey("CoREENxT6tW1HoK8ypY1SxRMZTcVPm7R94rH4PZNhX7d")
    val BUBBLEGUM_PROGRAM_ID  = PublicKey("BGUMApnG53q5bGgBdKrvSkpMFe7d4QKCEZy8Apju6U6G")

    val COMPUTE_BUDGET_PROGRAM_ID = PublicKey("ComputeBudget111111111111111111111111111111")
    //
    val MPL_BUBBLEGUM_PROGRAM_ID     = PublicKey("BGUMAp9Gq7iTEuizy4pqaxsTyUCBK68MDfK752saRPUY")
    val SPL_ACCOUNT_COMPRESSION     = PublicKey("cmtDvXumGCrqC1Age74AVPhSRVXJMd8PJS91L8KbNCK")
    val MPL_ACCOUNT_COMPRESSION  = PublicKey("mcmt6YrQEMKw8Mw43FmpRLmf7BqRnFMKmAcbxE3xkAW") // mplAccountCompression
    val MPL_NOOP                 = PublicKey("mnoopTCrg4p8ry25e4bcWA9XZjbNjMTfgYVGGEdRsf3") // mplNoop

    //
    val quickNodeUrl = "https://multi-ultra-frost.solana-devnet.quiknode.pro/417151c175bae42230bf09c1f87acda90dc21968/" //change this with envi variable



    fun Long.toLittleEndian(size: Int): ByteArray {
        return ByteArray(size) { i ->
            ((this shr (8 * i)) and 0xFF).toByte()
        }
    }

    suspend fun getTokenDecimals(mintAddress: String): Int {
        val response = getAccountInfo(mintAddress)

        val decimals = response?.data?.parsed?.info?.decimals

        return decimals ?: throw Exception("Unable to parse token decimals from mint: $mintAddress")
    }



    fun buildSetAuthorityData(
        authorityType: Int,           // e.g. 2 for AccountOwner
        newAuthority: PublicKey       // the new owner's public key
    ): ByteArray {
        val instruction: Byte = 6 // SPL Token: SetAuthority instruction
        val authorityTypeByte = authorityType.toByte()

        val buffer = ByteBuffer.allocate(1 + 1 + 1 + 32)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(instruction)          // 1 byte: instruction (6)
        buffer.put(authorityTypeByte)    // 1 byte: authority type (2 for AccountOwner)
        buffer.put(1.toByte())           // 1 byte: "Option<Pubkey>" = Some = 1
        buffer.put(newAuthority.toByteArray()) // 32 bytes: new authority public key

        return buffer.array()
    }

    suspend fun getAccountInfo(publicKey: String, useBase64: Boolean = false): AccountInfoValue? {
        val rpc = QuickNodeRpc(quickNodeUrl)
        return  rpc.getAccountInfo(publicKey).value
    }



    fun createEd25519KeypairFromSeed(seed: ByteArray): Pair<ByteArray, ByteArray> {
        val privParams = Ed25519PrivateKeyParameters(seed, 0)
        val pubKey = privParams.generatePublicKey().encoded
        val privKey = privParams.encoded
        return pubKey to privKey
    }

    fun ataExists(publicKey: String): Boolean {
        val response = getAccountInfo(publicKey)
        return response != null
    }

//    fun getAtaOwner(publicKey: String): String? {
//        val response = getAccountInfo(publicKey)
//        return response?.data.parse?.owner
//    }

    fun validateAta(publicKey: String, expectedOwner: String) {
        val response = getAccountInfo(publicKey)
        val value = response
            ?: throw Error("Associated token account does not exist!")

        val actualOwner = value.data?.parsed?.info?.owner
        if (expectedOwner != actualOwner) {
            throw Error("Authorized owner is $actualOwner, expected $expectedOwner")
        }
    }
    fun getRawQuantity(quantity: Double, decimals: Int): Long {
        return (quantity * 10.0.pow(decimals)).toLong()
    }
    suspend fun findMetadataPda(mint: PublicKey): PublicKey {
        val seeds = listOf(
            "metadata".toByteArray(),
            METADATA_PROGRAM_ID.toByteArray(),
            mint.toByteArray()
        )
        val programid = METADATA_PROGRAM_ID
        return PublicKey.findProgramAddress(seeds, programid).address
    }
    suspend fun findMasterEditionPda(mint: PublicKey): PublicKey {
        val seeds = listOf(
            "metadata".toByteArray(),
            METADATA_PROGRAM_ID.toByteArray(),
            mint.toByteArray(),
            "edition".toByteArray(Charsets.UTF_8)
        )
        val programid = METADATA_PROGRAM_ID
        return PublicKey.findProgramAddress(seeds, programid).address
    }


    // --- Borsh helpers ---
    // --- Serializer helpers ---
    fun writeU32LE(writer: DataOutputStream, value: Int) {
        writer.write(byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        ))
    }

    fun writeBorshString(writer: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeU32LE(writer, bytes.size)
        writer.write(bytes)
    }

    // --- Serialize CreateCollectionV2 instruction data ---
//    Plugin Name	        Discriminator (Hex)	        As Integer
//    BubblegumV2	                0f	                    15
//    PermanentFreezeDelegate	    10	                    16
//    ProgramDenyList	            11	                    17
//    ProgramAllowList	            12	                    18
//    CollectionAuthorityRecord	    13	                    19
    fun serializeCreateCollectionV2(
        name: String,
        uri: String
    ): ByteArray {
        val discriminator: Byte = 21

        // Encode name and uri as Rust strings: [u32 length][bytes]
        fun encodeString(s: String): ByteArray {
            val utf8 = s.toByteArray(StandardCharsets.UTF_8)
            val buf = ByteBuffer.allocate(4 + utf8.size)
            buf.order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(utf8.size)
            buf.put(utf8)
            return buf.array()
        }

        // Encode Option::None as 0u8
        val buffer = ByteArrayOutputStream()
        val writer = DataOutputStream(buffer)

        // discriminator
        writer.writeByte(21)

        // name & uri
        writer.write(encodeString(name))
        writer.write(encodeString(uri))

        // plugins: Option<Vec<Plugin>>
        writer.writeByte(1)             // Some
        writer.write(byteArrayOf(1,0,0,0)) // Vec length = 1
        writer.writeByte(15)             // Plugin::BubblegumV2 discriminator
        writer.writeByte(0)             // authority = None

        // externalPluginAdapters: Option<Vec> (empty)
        writer.writeByte(1)             // Some
        writer.write(byteArrayOf(0,0,0,0)) // Vec length = 0


        return buffer.toByteArray()
    }


    /*
    * Max Depth	Max Buffer Size	Leaves Capacity	Notes
14	64	~16k leaves	Small tree, cheaper rent
20	640	~1M leaves	Large, high rent cost
26	2048+	>67M leaves	Huge, very expensive rent
    * */

    fun concurrentMerkleTreeAccountSize(
        maxDepth: Int,
        maxBufferSize: Int,
        canopyDepth: Int
    ): Int {
        val header = 8 + 8 + 8 // sequenceNumber + activeIndex + bufferSize

        // Each changeLog has root + pathNodes + index/padding
        val changeLogSize = 32 + (32 * maxDepth) + 8
        val changeLogs = changeLogSize * maxBufferSize

        // Right-most path proof
        val pathSize = (32 * maxDepth) + 32 + 4 + 4

        // Add canopy: number of cached nodes * 32
        val canopySize = canopyDepth * 32

        val total = header + changeLogs + pathSize + canopySize

        // Align to 8 bytes
        return (total + 7) and -8
    }
    suspend fun findTreeConfigPda(merkleTree: PublicKey): PublicKey {
        val seeds = listOf("tree_config".toByteArray(Charsets.UTF_8), merkleTree.toByteArray())
        val programId = MPL_BUBBLEGUM_PROGRAM_ID // Bubblegum program

        val (pda, _) = PublicKey.findProgramAddress(seeds, programId)
        return pda
    }
    fun getConcurrentMerkleTreeSerializer(
        maxDepth: Int,
        maxBufferSize: Int
    ): KtSerializer<ConcurrentMerkleTree> {
        return object : KtSerializer<ConcurrentMerkleTree> {

            override fun serialize(value: ConcurrentMerkleTree): ByteArray {
                val buffer = mutableListOf<Byte>()

                buffer.addAll(value.sequenceNumber.toByteArray64LE().toList())
                buffer.addAll(value.activeIndex.toByteArray64LE().toList())
                buffer.addAll(value.bufferSize.toByteArray64LE().toList())

                val changeLogSerializer = getChangeLogSerializer(maxDepth)
                value.changeLogs.forEach { log ->
                    buffer.addAll(changeLogSerializer.serialize(log).toList())
                }
                // pad if changeLogs.size < maxBufferSize
                repeat(maxBufferSize - value.changeLogs.size) {
                    buffer.addAll(ByteArray(changeLogSerializer.size()).toList())
                }

                val pathSerializer = getPathSerializer(maxDepth)
                buffer.addAll(pathSerializer.serialize(value.rightMostPath).toList())

                return buffer.toByteArray()
            }

            override fun deserialize(data: ByteArray): ConcurrentMerkleTree {
                var offset = 0

                val sequenceNumber = data.readULong64LE(offset).also { offset += 8 }
                val activeIndex = data.readULong64LE(offset).also { offset += 8 }
                val bufferSize = data.readULong64LE(offset).also { offset += 8 }

                val changeLogSerializer = getChangeLogSerializer(maxDepth)
                val changeLogs = mutableListOf<ChangeLog>()
                repeat(maxBufferSize) {
                    val logData = data.copyOfRange(offset, offset + changeLogSerializer.size())
                    val log = changeLogSerializer.deserialize(logData)
                    changeLogs.add(log)
                    offset += changeLogSerializer.size()
                }

                val pathSerializer = getPathSerializer(maxDepth)
                val rightMostPath = pathSerializer.deserialize(
                    data.copyOfRange(offset, offset + pathSerializer.size())
                )
                offset += pathSerializer.size()

                return ConcurrentMerkleTree(
                    sequenceNumber,
                    activeIndex,
                    bufferSize,
                    changeLogs,
                    rightMostPath
                )
            }

            override fun size(): Int {
                val changeLogSize = getChangeLogSerializer(maxDepth).size()
                val pathSize = getPathSerializer(maxDepth).size()
                return 8 + 8 + 8 + (changeLogSize * maxBufferSize) + pathSize
            }
        }
    }

    fun getChangeLogSerializer(maxDepth: Int): KtSerializer<ChangeLog> {
        return object : KtSerializer<ChangeLog> {

            override fun serialize(value: ChangeLog): ByteArray {
                val buffer = mutableListOf<Byte>()

                // root (32 bytes)
                buffer.addAll(value.root.toByteArray().toList())

                // pathNodes (maxDepth * 32 bytes)
                value.pathNodes.forEach { node ->
                    buffer.addAll(node.toByteArray().toList())
                }
                // pad if pathNodes < maxDepth
                repeat(maxDepth - value.pathNodes.size) {
                    buffer.addAll(ByteArray(32).toList())
                }

                // index (u32 fixed to 8 bytes)
                buffer.addAll(value.index.toByteArray32LE().toList())
                buffer.addAll(ByteArray(4).toList()) // pad to 8 bytes

                return buffer.toByteArray()
            }

            override fun deserialize(data: ByteArray): ChangeLog {
                var offset = 0

                val root = PublicKey(data.copyOfRange(offset, offset + 32)).also { offset += 32 }

                val pathNodes = mutableListOf<PublicKey>()
                repeat(maxDepth) {
                    pathNodes.add(PublicKey(data.copyOfRange(offset, offset + 32)))
                    offset += 32
                }

                val index = data.readUInt32LE(offset)
                offset += 8 // skip 8 bytes (including padding)

                return ChangeLog(root, pathNodes, index)
            }

            // Optional: return fixed size of serialized ChangeLog
            override fun size(): Int = 32 + (32 * maxDepth) + 8
        }
    }
    fun getPathSerializer(maxDepth: Int): KtSerializer<Path> {
        return object : KtSerializer<Path> {

            override fun serialize(value: Path): ByteArray {
                val buffer = mutableListOf<Byte>()

                // proof (maxDepth public keys)
                value.proof.forEach { node ->
                    buffer.addAll(node.toByteArray().toList())
                }
                repeat(maxDepth - value.proof.size) {
                    buffer.addAll(ByteArray(32).toList())
                }

                // leaf
                buffer.addAll(value.leaf.toByteArray().toList())

                // index (u32)
                buffer.addAll(value.index.toByteArray32LE().toList())

                // padding (u32)
                buffer.addAll(value.padding.toByteArray32LE().toList())

                return buffer.toByteArray()
            }

            override fun deserialize(data: ByteArray): Path {
                var offset = 0

                val proof = mutableListOf<PublicKey>()
                repeat(maxDepth) {
                    proof.add(PublicKey(data.copyOfRange(offset, offset + 32)))
                    offset += 32
                }

                val leaf = PublicKey(data.copyOfRange(offset, offset + 32))
                offset += 32

                val index = data.readUInt32LE(offset)
                offset += 4

                val padding = data.readUInt32LE(offset)
                offset += 4

                return Path(proof, leaf, index, padding)
            }

            override fun size(): Int = (32 * maxDepth) + 32 + 4 + 4
        }
    }

    private fun ByteArray.readULong64LE(offset: Int): ULong {
        var result = 0UL
        for (i in 0..7) {
            result = result or ((this[offset + i].toULong() and 0xFFUL) shl (8 * i))
        }
        return result
    }

    // Convert ULong to 8-byte little-endian ByteArray
    private fun ULong.toByteArray64LE(): ByteArray {
        val bytes = ByteArray(8)
        for (i in 0..7) {
            bytes[i] = ((this shr (8 * i)) and 0xFFUL).toByte()
        }
        return bytes
    }
    // Read UInt (32-bit little-endian) from ByteArray at offset
    private fun ByteArray.readUInt32LE(offset: Int): UInt {
        return (this[offset].toUInt() and 0xFFu) or
                ((this[offset + 1].toUInt() and 0xFFu) shl 8) or
                ((this[offset + 2].toUInt() and 0xFFu) shl 16) or
                ((this[offset + 3].toUInt() and 0xFFu) shl 24)
    }

    // Convert UInt to 4-byte little-endian ByteArray
    private fun UInt.toByteArray32LE(): ByteArray {
        val bytes = ByteArray(4)
        bytes[0] = (this.toInt() and 0xFF).toByte()
        bytes[1] = ((this.toInt() shr 8) and 0xFF).toByte()
        bytes[2] = ((this.toInt() shr 16) and 0xFF).toByte()
        bytes[3] = ((this.toInt() shr 24) and 0xFF).toByte()
        return bytes
    }

    fun getMerkleTreeAccountDataV1Serializer(
        maxDepth: Int,
        maxBufferSize: Int,
        totalAccountSize: Int // full Solana account data size
    ): KtSerializer<MerkleTreeAccountData> {
        val headerSerializer = ConcurrentMerkleTreeHeaderDataSerializer
        val treeSerializer = getConcurrentMerkleTreeSerializer(maxDepth, maxBufferSize)

        return object : KtSerializer<MerkleTreeAccountData> {

            override fun serialize(value: MerkleTreeAccountData): ByteArray {
                val buffer = mutableListOf<Byte>()

                // discriminator (u8)
                buffer.add(value.discriminator.toByte())

                // tree header
                buffer.addAll(headerSerializer.serialize(value.treeHeader).toList())

                // tree
                buffer.addAll(treeSerializer.serialize(value.tree).toList())

                // canopy
                value.canopy.forEach { pk ->
                    buffer.addAll(pk.toByteArray().toList())
                }

                return buffer.toByteArray()
            }

            override fun deserialize(data: ByteArray): MerkleTreeAccountData {
                var offset = 0

                val discriminator = data[offset].toUByte()
                offset += 1

                val headerSize = headerSerializer.size()
                val treeHeader = headerSerializer.deserialize(data.copyOfRange(offset, offset + headerSize))
                offset += headerSize

                val treeSize = treeSerializer.size()
                val tree = treeSerializer.deserialize(data.copyOfRange(offset, offset + treeSize))
                offset += treeSize

                // canopy = remaining bytes, each PublicKey is 32 bytes
                val canopyBytes = data.copyOfRange(offset, data.size)
                val canopy = canopyBytes.toList().chunked(32).map { bytes ->
                    PublicKey(bytes.toByteArray())
                }

                return MerkleTreeAccountData(discriminator, treeHeader, tree, canopy)
            }

            override fun size(): Int {
                // Fixed part: discriminator + header + tree
                val fixedSize = 1 + headerSerializer.size() + treeSerializer.size()
                // Canopy is variable, but if totalAccountSize provided, canopySize = remainder
                val canopySize = totalAccountSize - fixedSize
                return fixedSize + canopySize
            }
        }
    }


}