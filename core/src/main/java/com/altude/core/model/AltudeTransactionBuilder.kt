package com.altude.core.model

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.metaplex.signer.Signer
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AddressTableLookup
import foundation.metaplex.solana.transactions.Message
import foundation.metaplex.solana.transactions.AccountMeta
import foundation.metaplex.solana.transactions.Blockhash
import foundation.metaplex.solana.transactions.CompiledInstruction
import foundation.metaplex.solana.transactions.MessageHeader
import foundation.metaplex.solana.transactions.NonceInformation
import foundation.metaplex.solana.transactions.PACKET_DATA_SIZE
import foundation.metaplex.solana.transactions.SIGNATURE_LENGTH
import foundation.metaplex.solana.transactions.SerializeConfig
import foundation.metaplex.solana.transactions.SerializedTransaction
import foundation.metaplex.solana.transactions.SerializedTransactionMessage
import foundation.metaplex.solana.transactions.SignaturePubkeyPair
import foundation.metaplex.solana.transactions.Transaction
import foundation.metaplex.solana.transactions.TransactionInstruction
import foundation.metaplex.solana.transactions.TransactionSignature
import foundation.metaplex.solana.util.Shortvec
import foundation.metaplex.solanaeddsa.SolanaEddsa
import foundation.metaplex.solanapublickeys.PublicKey
import java.lang.Error
import kotlin.collections.count

/**
 * Base58 encoder/decoder utility
 * Correct implementation that handles all byte values including high bytes (> 127)
 */
private object Base58Util {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEXES = IntArray(128) { -1 }.also { indexes ->
        ALPHABET.forEachIndexed { i, c -> indexes[c.code] = i }
    }

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        
        // Make a copy to avoid modifying the original
        val bytes = input.copyOf()
        
        // Count leading zeros (using unsigned comparison)
        var zeros = 0
        while (zeros < bytes.size && (bytes[zeros].toInt() and 0xFF) == 0) {
            zeros++
        }
        
        // Allocate enough space for the encoded result
        val encoded = CharArray(bytes.size * 2)
        var outputEnd = encoded.size
        
        var start = zeros
        while (start < bytes.size) {
            // Division step: divide entire number by 58, get remainder
            var carry = 0
            for (i in start until bytes.size) {
                val digit = (bytes[i].toInt() and 0xFF) + carry * 256
                bytes[i] = (digit / 58).toByte()
                carry = digit % 58
            }
            // Move start forward if leading byte became zero
            while (start < bytes.size && (bytes[start].toInt() and 0xFF) == 0) {
                start++
            }
            outputEnd--
            encoded[outputEnd] = ALPHABET[carry]
        }
        
        // Add leading '1's for leading zeros
        repeat(zeros) {
            outputEnd--
            encoded[outputEnd] = '1'
        }
        
        return String(encoded, outputEnd, encoded.size - outputEnd)
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        
        // Count leading '1's (zeros in byte array)
        var zeros = 0
        while (zeros < input.length && input[zeros] == '1') {
            zeros++
        }
        
        // Allocate enough space for decoded result
        val bytes = ByteArray(input.length)
        var outputEnd = bytes.size
        
        for (i in zeros until input.length) {
            val c = input[i]
            var carry = if (c.code < 128) INDEXES[c.code] else -1
            if (carry < 0) throw IllegalArgumentException("Invalid character '$c' at position $i")
            
            // Multiplication step: multiply entire number by 58, add carry
            for (j in bytes.size - 1 downTo outputEnd) {
                carry += (bytes[j].toInt() and 0xFF) * 58
                bytes[j] = (carry and 0xFF).toByte()
                carry = carry shr 8
            }
            // Add new byte if carry remains
            while (carry > 0) {
                outputEnd--
                bytes[outputEnd] = (carry and 0xFF).toByte()
                carry = carry shr 8
            }
        }
        
        // Skip leading zeros in output (but keep the ones we need)
        while (outputEnd < bytes.size && bytes[outputEnd].toInt() == 0) {
            outputEnd++
        }
        
        // Build result with leading zeros
        val result = ByteArray(zeros + bytes.size - outputEnd)
        System.arraycopy(bytes, outputEnd, result, zeros, bytes.size - outputEnd)
        return result
    }
}

class AltudeTransactionBuilder(transactionVersion: TransactionVersion = TransactionVersion.Legacy) : TransactionBuilder {

    private val transaction: VersionedSolanaTransaction = VersionedSolanaTransaction(transactionVersion)

    override fun addInstruction(transactionInstruction: TransactionInstruction): TransactionBuilder {
        transaction.add(transactionInstruction)
        return this
    }

    override fun addRangeInstruction(transactionInstruction: List<TransactionInstruction>): TransactionBuilder {
        transactionInstruction.forEach { transaction.add(it) }
        return this
    }

    override fun setRecentBlockHash(recentBlockHash: Blockhash): TransactionBuilder {
        transaction.setRecentBlockHash(recentBlockHash)
        return this
    }

    override suspend fun setSigners(signers: List<Signer>): TransactionBuilder {
        transaction.sign(signers)
        return this
    }

    override suspend fun build(): Transaction {
        return transaction
    }

    override fun setFeePayer(publicKey: PublicKey): TransactionBuilder {
        transaction.feePayer = publicKey
        return this
    }

    override fun addLookUpTable(lookupTable: MessageAddressTableLookup): TransactionBuilder {
        transaction.addMessageAddressTableLookup(AddressTableLookup(
            account = SolanaPublicKey.from(lookupTable.accountKey.toBase58()),
            writableIndexes = lookupTable.writableIndexes.map { it -> it.toUByte() },
            readOnlyIndexes = lookupTable.readonlyIndexes.map { it -> it.toUByte() }
        ))
        return this
    }

    override fun addLookUpTables(lookupTables: List<MessageAddressTableLookup>?): TransactionBuilder {
        transaction.addMessageAddressTableLookups(lookupTables?.map { lookupTable ->
            AddressTableLookup(
                account = SolanaPublicKey.from(lookupTable.accountKey.toBase58()),
                writableIndexes = lookupTable.writableIndexes.map { it -> it.toUByte() },
                readOnlyIndexes = lookupTable.readonlyIndexes.map { it -> it.toUByte() }
            )
        })
        return this
    }
}



private const val VERSION_BIT: Int = 0x80
private const val VERSION_V0: Int = 0

const val PACKET_DATA_SIZE = 1280 - 40 - 8

const val SIGNATURE_LENGTH = 64

enum class TransactionVersion {
    V0,
    Legacy
}

/**
 * V0 Versioned Message implementation using PublicKey
 * This message type supports Address Lookup Tables (ALTs)
 */
class VersionedMessage(
    val version: Byte = (VERSION_BIT or VERSION_V0).toByte(),
    val signatureCount: UByte,
    val readOnlyAccounts: UByte,
    val readOnlyNonSigners: UByte,
    val accounts: List<PublicKey>,
    val blockhash: String,
    override val instructions: List<CompiledInstruction>,
    val addressTableLookups: List<AddressTableLookup> = emptyList()
) : Message {

    override val header: MessageHeader
        get() = MessageHeader().apply {
            numRequiredSignatures = signatureCount.toByte()
            numReadonlySignedAccounts = readOnlyAccounts.toByte()
            numReadonlyUnsignedAccounts = readOnlyNonSigners.toByte()
        }

    override val accountKeys: List<PublicKey>
        get() = accounts

    override val recentBlockhash: String
        get() = blockhash

    override fun isAccountSigner(index: Int): Boolean {
        return index < signatureCount.toInt()
    }

    override fun isAccountWritable(index: Int): Boolean {
        val numSigners = signatureCount.toInt()
        val numReadonlySigned = readOnlyAccounts.toInt()
        val numReadonlyUnsigned = readOnlyNonSigners.toInt()
        
        if (index < numSigners) {
            // Signed account - writable if not in readonly signed range
            return index < (numSigners - numReadonlySigned)
        } else {
            // Unsigned account - writable if not in readonly unsigned range
            val unsignedIndex = index - numSigners
            val numUnsigned = accounts.size - numSigners
            return unsignedIndex < (numUnsigned - numReadonlyUnsigned)
        }
    }

    override fun isProgramId(index: Int): Boolean {
        return instructions.any { it.programIdIndex == index }
    }

    override fun nonProgramIds(): List<PublicKey> {
        val programIdIndices = instructions.map { it.programIdIndex }.toSet()
        return accounts.filterIndexed { index, _ -> index !in programIdIndices }
    }

    override fun programIds(): List<PublicKey> {
        val programIdIndices = instructions.map { it.programIdIndex }.toSet()
        return programIdIndices.map { accounts[it] }
    }

    override fun setFeePayer(publicKey: PublicKey) {
        // V0 messages are immutable after creation, fee payer should be set during construction
        throw UnsupportedOperationException("VersionedMessage is immutable, fee payer must be set during construction")
    }

    override fun serialize(): ByteArray {
        // V0 message serialization
        val accountAddressesLength = Shortvec.encodeLength(accounts.size)
        val instructionsLength = Shortvec.encodeLength(instructions.size)
        val addressTableLookupsLength = Shortvec.encodeLength(addressTableLookups.size)

        // Calculate instruction data sizes
        val instructionBytes = instructions.map { serializeInstruction(it) }
        val instructionsTotalSize = instructionBytes.sumOf { it.size }

        // Calculate address table lookups sizes
        val lookupBytes = addressTableLookups.map { serializeAddressTableLookup(it) }
        val lookupsTotalSize = lookupBytes.sumOf { it.size }

        // Total size: version prefix (1) + header (3) + accounts + blockhash (32) + instructions + lookups
        val totalSize = 1 + 3 + accountAddressesLength.size + (accounts.size * 32) + 32 +
                instructionsLength.size + instructionsTotalSize +
                addressTableLookupsLength.size + lookupsTotalSize

        val buffer = PlatformBuffer.allocate(totalSize)

        // 1. Version prefix (0x80 for V0)
        buffer.writeByte(version)

        // 2. Header: numRequiredSignatures, numReadonlySignedAccounts, numReadonlyUnsignedAccounts
        buffer.writeByte(signatureCount.toByte())
        buffer.writeByte(readOnlyAccounts.toByte())
        buffer.writeByte(readOnlyNonSigners.toByte())

        // 3. Account addresses length + addresses
        buffer.writeBytes(accountAddressesLength)
        accounts.forEach { account ->
            buffer.writeBytes(account.toByteArray())
        }

        // 4. Recent blockhash (32 bytes)
        buffer.writeBytes(SolanaPublicKey.from(blockhash).bytes)

        // 5. Instructions length + instructions
        buffer.writeBytes(instructionsLength)
        instructionBytes.forEach { buffer.writeBytes(it) }

        // 6. Address table lookups length + lookups
        buffer.writeBytes(addressTableLookupsLength)
        lookupBytes.forEach { buffer.writeBytes(it) }

        buffer.resetForRead()
        return buffer.readByteArray(totalSize)
    }

    private fun serializeInstruction(instruction: CompiledInstruction): ByteArray {
        val accounts = instruction.accounts
        val dataBytes = Base58Util.decode(instruction.data) // Decode Base58 data back to bytes
        val accountIndicesLength = Shortvec.encodeLength(accounts.size)
        val dataLength = Shortvec.encodeLength(dataBytes.size)

        val size = 1 + accountIndicesLength.size + accounts.size +
                dataLength.size + dataBytes.size

        val buffer = PlatformBuffer.allocate(size)

        // Program ID index
        buffer.writeByte(instruction.programIdIndex.toByte())

        // Account indices
        buffer.writeBytes(accountIndicesLength)
        accounts.forEach { buffer.writeByte(it.toByte()) }

        // Data
        buffer.writeBytes(dataLength)
        buffer.writeBytes(dataBytes)

        buffer.resetForRead()
        return buffer.readByteArray(size)
    }

    private fun serializeAddressTableLookup(lookup: AddressTableLookup): ByteArray {
        val writableLength = Shortvec.encodeLength(lookup.writableIndexes.size)
        val readonlyLength = Shortvec.encodeLength(lookup.readOnlyIndexes.size)

        val size = 32 + writableLength.size + lookup.writableIndexes.size +
                readonlyLength.size + lookup.readOnlyIndexes.size

        val buffer = PlatformBuffer.allocate(size)

        // Account key (32 bytes)
        buffer.writeBytes(lookup.account.bytes)

        // Writable indexes
        buffer.writeBytes(writableLength)
        lookup.writableIndexes.forEach { buffer.writeByte(it.toByte()) }

        // Readonly indexes
        buffer.writeBytes(readonlyLength)
        lookup.readOnlyIndexes.forEach { buffer.writeByte(it.toByte()) }

        buffer.resetForRead()
        return buffer.readByteArray(size)
    }

    companion object {
        /**
         * Creates a V0 VersionedMessage from transaction components using PublicKey
         */
        fun create(
            numRequiredSignatures: Int,
            numReadonlySignedAccounts: Int,
            numReadonlyUnsignedAccounts: Int,
            accountKeys: List<PublicKey>,
            recentBlockhash: String,
            instructions: List<CompiledInstruction>,
            addressTableLookups: List<AddressTableLookup> = emptyList()
        ): VersionedMessage {
            return VersionedMessage(
                version = (VERSION_BIT or VERSION_V0).toByte(),
                signatureCount = numRequiredSignatures.toUByte(),
                readOnlyAccounts = numReadonlySignedAccounts.toUByte(),
                readOnlyNonSigners = numReadonlyUnsignedAccounts.toUByte(),
                accounts = accountKeys,
                blockhash = recentBlockhash,
                instructions = instructions,
                addressTableLookups = addressTableLookups
            )
        }

        /**
         * Creates a V0 VersionedMessage from transaction components
         * Returns a V0 message for versioned transactions
         */
        fun createV0Message(
            numRequiredSignatures: Int,
            numReadonlySignedAccounts: Int,
            numReadonlyUnsignedAccounts: Int,
            accountKeys: List<PublicKey>,
            recentBlockhash: String,
            instructions: List<CompiledInstruction>,
            addressTableLookups: List<AddressTableLookup> = emptyList()
        ): VersionedMessage {
            return create(
                numRequiredSignatures = numRequiredSignatures,
                numReadonlySignedAccounts = numReadonlySignedAccounts,
                numReadonlyUnsignedAccounts = numReadonlyUnsignedAccounts,
                accountKeys = accountKeys,
                recentBlockhash = recentBlockhash,
                instructions = instructions,
                addressTableLookups = addressTableLookups
            )
        }
    }
}

/**
 * Legacy Message implementation with custom serialization
 * This ensures consistent instruction data handling for Legacy transactions
 */
class LegacyMessage(
    override val header: MessageHeader,
    override val accountKeys: List<PublicKey>,
    override val recentBlockhash: String,
    override val instructions: List<CompiledInstruction>
) : Message {

    override fun isAccountSigner(index: Int): Boolean {
        return index < header.numRequiredSignatures
    }

    override fun isAccountWritable(index: Int): Boolean {
        val numSigners = header.numRequiredSignatures.toInt()
        val numReadonlySigned = header.numReadonlySignedAccounts.toInt()
        val numReadonlyUnsigned = header.numReadonlyUnsignedAccounts.toInt()
        
        if (index < numSigners) {
            // Signed account - writable if not in readonly signed range
            return index < (numSigners - numReadonlySigned)
        } else {
            // Unsigned account - writable if not in readonly unsigned range
            val unsignedIndex = index - numSigners
            val numUnsigned = accountKeys.size - numSigners
            return unsignedIndex < (numUnsigned - numReadonlyUnsigned)
        }
    }

    override fun isProgramId(index: Int): Boolean {
        return instructions.any { it.programIdIndex == index }
    }

    override fun nonProgramIds(): List<PublicKey> {
        val programIdIndices = instructions.map { it.programIdIndex }.toSet()
        return accountKeys.filterIndexed { index, _ -> index !in programIdIndices }
    }

    override fun programIds(): List<PublicKey> {
        val programIdIndices = instructions.map { it.programIdIndex }.toSet()
        return programIdIndices.map { accountKeys[it] }
    }

    override fun setFeePayer(publicKey: PublicKey) {
        throw UnsupportedOperationException("LegacyMessage is immutable, fee payer must be set during construction")
    }

    override fun serialize(): ByteArray {
        val accountAddressesLength = Shortvec.encodeLength(accountKeys.size)
        val instructionsLength = Shortvec.encodeLength(instructions.size)

        // Calculate instruction data sizes
        val instructionBytes = instructions.map { serializeInstruction(it) }
        val instructionsTotalSize = instructionBytes.sumOf { it.size }

        // Total size: header (3) + accounts + blockhash (32) + instructions
        val totalSize = 3 + accountAddressesLength.size + (accountKeys.size * 32) + 32 +
                instructionsLength.size + instructionsTotalSize

        android.util.Log.d("LegacyMessage", "=== LEGACY MESSAGE SERIALIZATION ===")
        android.util.Log.d("LegacyMessage", "Header: numReqSigs=${header.numRequiredSignatures}, readonlySigned=${header.numReadonlySignedAccounts}, readonlyUnsigned=${header.numReadonlyUnsignedAccounts}")
        android.util.Log.d("LegacyMessage", "Account count: ${accountKeys.size}")
        android.util.Log.d("LegacyMessage", "Instructions count: ${instructions.size}")
        android.util.Log.d("LegacyMessage", "Total size: $totalSize bytes")

        val buffer = PlatformBuffer.allocate(totalSize)

        // 1. Header: numRequiredSignatures, numReadonlySignedAccounts, numReadonlyUnsignedAccounts
        buffer.writeByte(header.numRequiredSignatures)
        buffer.writeByte(header.numReadonlySignedAccounts)
        buffer.writeByte(header.numReadonlyUnsignedAccounts)

        // 2. Account addresses length + addresses
        buffer.writeBytes(accountAddressesLength)
        accountKeys.forEach { account ->
            buffer.writeBytes(account.toByteArray())
        }

        // 3. Recent blockhash (32 bytes)
        buffer.writeBytes(SolanaPublicKey.from(recentBlockhash).bytes)

        // 4. Instructions length + instructions
        buffer.writeBytes(instructionsLength)
        instructionBytes.forEach { buffer.writeBytes(it) }

        buffer.resetForRead()
        val result = buffer.readByteArray(totalSize)
        
        // Log the final message bytes for debugging
        android.util.Log.d("LegacyMessage", "=== FINAL MESSAGE ===")
        android.util.Log.d("LegacyMessage", "Total bytes: ${result.size}")
        android.util.Log.d("LegacyMessage", "First 64 bytes hex: ${result.take(64).joinToString("") { "%02x".format(it) }}")
        
        return result
    }

    private fun serializeInstruction(instruction: CompiledInstruction): ByteArray {
        val accounts = instruction.accounts
        val dataBytes = Base58Util.decode(instruction.data) // Decode Base58 data back to bytes
        val accountIndicesLength = Shortvec.encodeLength(accounts.size)
        val dataLength = Shortvec.encodeLength(dataBytes.size)

        val size = 1 + accountIndicesLength.size + accounts.size +
                dataLength.size + dataBytes.size

        android.util.Log.d("LegacyMessage", "=== INSTRUCTION SERIALIZATION ===")
        android.util.Log.d("LegacyMessage", "  ProgramIdIndex: ${instruction.programIdIndex}")
        android.util.Log.d("LegacyMessage", "  Account indices (${accounts.size}): $accounts")
        android.util.Log.d("LegacyMessage", "  Data size: ${dataBytes.size} bytes")
        android.util.Log.d("LegacyMessage", "  Data hex: ${dataBytes.take(32).joinToString("") { "%02x".format(it) }}${if (dataBytes.size > 32) "..." else ""}")
        android.util.Log.d("LegacyMessage", "  Total instruction size: $size bytes")

        val buffer = PlatformBuffer.allocate(size)

        // Program ID index
        buffer.writeByte(instruction.programIdIndex.toByte())

        // Account indices
        buffer.writeBytes(accountIndicesLength)
        accounts.forEach { buffer.writeByte(it.toByte()) }

        // Data
        buffer.writeBytes(dataLength)
        buffer.writeBytes(dataBytes)

        buffer.resetForRead()
        val result = buffer.readByteArray(size)
        
        // Log the raw instruction bytes
        android.util.Log.d("LegacyMessage", "  Serialized instruction hex: ${result.joinToString("") { "%02x".format(it) }}")
        
        return result
    }
}


/**
 * A lookup entry for a MessageV0 describing which indices (u8) in the lookup table
 * are referenced as writable or readonly.
 */
data class MessageAddressTableLookup(
    val accountKey: PublicKey,
    val writableIndexes: List<Int>,   // u8 indexes
    val readonlyIndexes: List<Int>    // u8 indexes
) {
    fun serialize(): ByteArray {
        val wLen = Shortvec.encodeLength(writableIndexes.size)
        val rLen = Shortvec.encodeLength(readonlyIndexes.size)

        val total = 32 + wLen.size + writableIndexes.size + rLen.size + readonlyIndexes.size

        val buf = PlatformBuffer.allocate(total)

        // 32-byte table address
        buf.writeBytes(accountKey.toByteArray())

        // writable indexes
        buf.writeBytes(wLen)
        writableIndexes.forEach { buf.writeByte(it.toByte()) }

        // readonly indexes
        buf.writeBytes(rLen)
        readonlyIndexes.forEach { buf.writeByte(it.toByte()) }

        // 🔥 reset before reading!
        buf.resetForRead()

        return buf.readByteArray(total)
    }

    companion object {
        fun deserialize(bytes: ByteArray): Pair<MessageAddressTableLookup, ByteArray> {
            var b = bytes

            // --- 1. table address ---
            val accountKey = PublicKey(b.sliceArray(0 until 32))
            b = b.sliceArray(32 until b.size)

            // --- 2. writable length ---
            val (wCount, afterWLen) = Shortvec.decodeLength(b)
            val wLenConsumed = b.size - afterWLen.size
            b = afterWLen

            // --- 3. writable indexes ---
            val writableIndexes = b.take(wCount).map { it.toInt() and 0xff }
            b = b.drop(wCount).toByteArray()

            // --- 4. readonly length ---
            val (rCount, afterRLen) = Shortvec.decodeLength(b)
            val rLenConsumed = b.size - afterRLen.size
            b = afterRLen

            // --- 5. readonly indexes ---
            val readonlyIndexes = b.take(rCount).map { it.toInt() and 0xff }
            b = b.drop(rCount).toByteArray()

            return MessageAddressTableLookup(
                accountKey = accountKey,
                writableIndexes = writableIndexes,
                readonlyIndexes = readonlyIndexes
            ) to b
        }
    }
}


/**
 * A full versioned transaction wrapper. On-wire format:
 * [versionedPrefixByte][messageV0 bytes]  (for v0 prefix = 0x80 | 0)
 */
class VersionedTransaction(
    var message: Message,
    val signatures: MutableList<SignaturePubkeyPair> // each 64 bytes; use null signature for partial
) {
    // ... other fields/methods remain the same ...

    fun serialize(): ByteArray {
        val messageBytes = message.serialize()
        
        // Check if this is a versioned message (V0) or legacy message
        return when (message) {
            is VersionedMessage -> {
                // V0 transaction - use version prefix
                val prefix = (VERSION_BIT or VERSION_V0).toByte()
                val sigCountEnc = Shortvec.encodeLength(signatures.size)

                // Order on the wire: prefix, sigCount, signatures, messageBytes
                val total = 1 + sigCountEnc.size + signatures.size * SIGNATURE_LENGTH + messageBytes.size

                val buf = PlatformBuffer.allocate(total)
                buf.writeByte(prefix)
                buf.writeBytes(sigCountEnc)

                // signatures: write 64 bytes per signature (zeroes if null/partial)
                signatures.forEach { pair ->
                    val sig = pair.signature
                    if (sig == null) {
                        buf.writeBytes(ByteArray(SIGNATURE_LENGTH)) // placeholder zeros
                    } else {
                        require(sig.size == SIGNATURE_LENGTH) { "signature has invalid length" }
                        buf.writeBytes(sig)
                    }
                }

                // finally the message bytes
                buf.writeBytes(messageBytes)

                buf.readByteArray(total)
            }
            else -> {
                // Legacy transaction - no version prefix
                serializeLegacyFormat()
            }
        }
    }

    /**
     * Serializes Legacy transactions without version prefix
     */
    private fun serializeLegacyFormat(): ByteArray {
        val messageBytes = message.serialize()
        val sigCountEnc = Shortvec.encodeLength(signatures.size)

        // Order on the wire for Legacy: sigCount, signatures, messageBytes (no prefix)
        val total = sigCountEnc.size + signatures.size * SIGNATURE_LENGTH + messageBytes.size

        val buf = PlatformBuffer.allocate(total)
        buf.writeBytes(sigCountEnc)

        // signatures: write 64 bytes per signature (zeroes if null/partial)
        signatures.forEach { pair ->
            val sig = pair.signature
            if (sig == null) {
                buf.writeBytes(ByteArray(SIGNATURE_LENGTH)) // placeholder zeros
            } else {
                require(sig.size == SIGNATURE_LENGTH) { "signature has invalid length" }
                buf.writeBytes(sig)
            }
        }

        // finally the message bytes
        buf.writeBytes(messageBytes)

        return buf.readByteArray(total)
    }

//    companion object {
//        fun deserialize(buffer: ByteArray): VersionedTransaction {
//            require(buffer.isNotEmpty())
//
//            val prefix = buffer[0].toInt() and 0xff
//            require((prefix and VERSION_BIT) != 0) { "Not a versioned transaction" }
//            require((prefix and 0x7f) == VERSION_V0) { "Unsupported version" }
//
//            val tail = buffer.copyOfRange(1, buffer.size)
//
//            // --- Shortvec decode ---
//            val (sigCount, afterLen) = Shortvec.decodeLength(tail)
//
//            // bytes consumed by shortvec
//            val shortvecLen = tail.size - afterLen.size
//            var pos = shortvecLen
//
//            // --- Signatures ---
//            val requiredBytes = pos + sigCount * SIGNATURE_LENGTH
//            require(tail.size >= requiredBytes) {
//                "Not enough bytes for $sigCount signatures"
//            }
//
//            val sigs = mutableListOf<SignaturePubkeyPair>()
//            val placeholder = PublicKey(ByteArray(32))
//
//            repeat(sigCount) {
//                val sig = tail.copyOfRange(pos, pos + SIGNATURE_LENGTH)
//                pos += SIGNATURE_LENGTH
//                sigs.add(SignaturePubkeyPair(sig, placeholder))
//            }
//
//            // --- Remaining bytes = message ---
//            val messageBytes = tail.copyOfRange(pos, tail.size)
//
//            val (message, leftover) = MessageV0.from(messageBytes)
//            require(leftover.isEmpty()) { "Unexpected trailing data after message" }
//
//            return VersionedTransaction(message, sigs)
//        }
//    }
}

/**
 * Modify your existing top-level deserializer factory to detect legacy vs versioned
 */
//fun parseWireTransaction(buffer: ByteArray): Transaction {
//    if (buffer.isEmpty()) throw Error("empty buffer")
//    val first = buffer[0].toInt() and 0xff
//    val isVersioned = (first and VERSION_BIT) != 0
//    return if (isVersioned) {
//        // versioned: use VersionedTransaction and then convert/populate into your Transaction model (or return a new versioned wrapper)
//        val vtx = VersionedTransaction.deserialize(buffer)
//        // If you want to maintain the same Transaction type (SolanaTransaction) for legacy compatibility
//        // you can create a wrapper or return a specialized VersionedTransaction class.
//        // Here we return a specialized wrapper that implements Transaction (you may want to adapt)
//        VersionedSolanaTransaction.fromVersioned(vtx)
//    } else {
//        // legacy path: same as you had (signatures count + message)
//        SolanaTransaction.from(buffer)
//    }
//}

/**
 * A lightweight wrapper to represent versioned tx as your Transaction interface.
 * You can expand this to match your Transaction API (signing, partialSign etc).
 */
class VersionedSolanaTransaction (
    val transacionVersion: TransactionVersion = TransactionVersion.Legacy
) : Transaction {

    var signatures = mutableListOf<SignaturePubkeyPair>()
    val signature: ByteArray?
        get() = signatures.firstOrNull()?.signature

    private lateinit var serializedMessage: ByteArray
    var feePayer: PublicKey? = null
    var addressTableLookups = mutableListOf<AddressTableLookup>()
    val instructions = mutableListOf<TransactionInstruction>()
    lateinit var recentBlockhash: String
    var nonceInfo: NonceInformation? = null
    override fun add(vararg instruction: TransactionInstruction): Transaction {
        instructions .addAll(instruction)
        return this
    }

//    companion object {
//        fun fromVersioned(vtx: VersionedTransaction): VersionedSolanaTransaction =
//            VersionedSolanaTransaction(vtx)
//    }

    private lateinit var message: Message
    val versioned: VersionedTransaction
        get() {
            if (!::message.isInitialized) {
                message = compileVersionedMessage()
            }
            return VersionedTransaction(message, signatures)
        }
    override fun addInstruction(vararg instruction: TransactionInstruction): Transaction {
        instructions.addAll(instruction)
        return this
    }
    fun addMessageAddressTableLookup (lookupTable: AddressTableLookup) {
        addressTableLookups.add(lookupTable)
    }
    fun addMessageAddressTableLookups (lookupTables: List<AddressTableLookup>?) {
        if (lookupTables != null)
            addressTableLookups.addAll(lookupTables)
    }
    override fun setRecentBlockHash(recentBlockhash: String) {
        this.recentBlockhash = recentBlockhash
    }

    override suspend fun sign(vararg signer: Signer) {
        sign(signer.toList())
    }
    private fun compile(): Message {
        val message = compileVersionedMessage()
        this.versioned.message = message
        val signedKeys = message.accountKeys.slice(
            0 until message.header.numRequiredSignatures
        )

        if (this.signatures.count() == signedKeys.count()) {
            var valid = true
            this.signatures.forEachIndexed { index, pair ->
                if (signedKeys[index].toBase58() != pair.publicKey.toBase58()) {
                    valid = false
                    return@forEachIndexed
                }
            }
            if (valid) return message
        }

        this.signatures = signedKeys.map { publicKey ->
            SignaturePubkeyPair(
                signature = null,
                publicKey = publicKey
            )
        }.toMutableList()

        return message
//        val required = message.header.numRequiredSignatures.toInt()
//
//        val signedKeys = message.accountKeys.take(required)
//
//        // Ensure signatures match required signing keys
//        if (this.versioned.signatures.size == required) {
//            var valid = true
//            this.versioned.signatures.forEachIndexed { index, pair ->
//                if (!signedKeys[index].equals(pair.publicKey)) {
//                    valid = false
//                    return@forEachIndexed
//                }
//            }
//            if (valid) return message
//        }
//
//        // Rebuild signatures array to match signer order
//        //versioned.signatures.clear()
//        signedKeys.forEach { pubkey ->
//            versioned.signatures.add(
//                SignaturePubkeyPair(
//                    signature = null,
//                    publicKey = pubkey
//                )
//            )
//        }
//
//        return message
    }

    override suspend fun sign(signers: List<Signer>) {
        require(signers.isNotEmpty()) { "No signers" }

        // Dedupe signers
        val seen = mutableSetOf<String>()
        val uniqueSigners = mutableListOf<Signer>()
        for (signer in signers) {
            val key = signer.publicKey.toBase58()
            if (seen.contains(key)) {
                continue
            } else {
                seen.add(key)
                uniqueSigners.add(signer)
            }
        }

        uniqueSigners.map {
            SignaturePubkeyPair(
                signature = null,
                publicKey = it.publicKey
            )
        }.let {
            versioned.signatures.addAll(it)
        }

        val message = compile()
        partialSign(message, uniqueSigners)
        verifySignatures(message.serialize(), true)
    }

    override suspend fun verifySignatures(): Boolean {
        return verifySignatures(this.serializeMessage(), true)
    }

    override suspend fun partialSign(vararg signers: Signer) {
        require(signers.isNotEmpty()) { "No signers" }

        // Dedupe signers
        val seen = mutableSetOf<String>()
        val uniqueSigners = mutableListOf<Signer>()
        for (signer in signers) {
            val key = signer.publicKey.toString()
            if (seen.contains(key)) {
                continue
            } else {
                seen.add(key)
                uniqueSigners.add(signer)
            }
        }

        val message = compile()
        partialSign(message, uniqueSigners)
    }
    private suspend fun partialSign(message: Message, signers: List<Signer>) {
        val signData = message.serialize()
        signers.forEach { signer ->
            val signature = signer.signMessage(signData)
            _addSignature(signer.publicKey, signature)
        }
    }
    private suspend fun partialSignMessage(
        message: Message,
        signers: List<Signer>
    ) {
        val payload = message.serialize()

        signers.forEach { signer ->
            val sig = signer.signMessage(payload)
            val signerBase58 = signer.publicKey.toBase58()
            val match = versioned.signatures.firstOrNull {
                it.publicKey.toBase58() == signerBase58
            } ?: throw Error("Unknown signer ${signer.publicKey}")

            match.signature = sig
        }
    }
    private suspend fun verifySignatures(signData: ByteArray, requireAllSignatures: Boolean): Boolean {
        versioned.signatures.forEach { (signature, publicKey) ->
            if (signature === null) {
                if (requireAllSignatures) {
                    return false
                }
            } else {
                if (!SolanaEddsa.verify(signData, signature, publicKey)) {
                    return false
                }
            }
        }
        return true
    }

    override suspend fun serialize(config: SerializeConfig): SerializedTransaction {
        val signData = serializeMessage()
        if (config.verifySignatures &&
            !verifySignatures(signData, config.requireAllSignatures)
        ) {
            throw Error("Signature verification failed")
        }
        return serialize(signData)
    }

    override suspend fun serialize(signData: ByteArray): SerializedTransactionMessage {
        // Check transaction version and serialize accordingly
        return when (transacionVersion) {
            TransactionVersion.V0 -> {
                // V0 transaction format - includes version prefix
                val versionPrefix = (VERSION_BIT or VERSION_V0).toByte()
                val signatureCount = Shortvec.encodeLength(signatures.count())
                val transactionLength = 1 + signatureCount.count() + signatures.count() * 64 + signData.count()
                val wireTransaction = PlatformBuffer.allocate(transactionLength)
                
                require(signatures.count() < 256)
                wireTransaction.writeByte(versionPrefix) // Add version prefix for V0
                wireTransaction.writeBytes(signatureCount)
                signatures.forEach { (signature, _) ->
                    when {
                        signature !== null -> {
                            require(signature.count() == 64) { "signature has invalid length" }
                            wireTransaction.writeBytes(signature)
                        }
                        else -> {
                            wireTransaction.writeBytes(ByteArray(SIGNATURE_LENGTH))
                        }
                    }
                }
                wireTransaction.writeBytes(signData)
                wireTransaction.resetForRead()
                val out = wireTransaction.readByteArray(transactionLength)
                require(out.count() <= PACKET_DATA_SIZE) {
                    "Transaction too large: ${out.count()} > $PACKET_DATA_SIZE"
                }
                out
            }
            TransactionVersion.Legacy -> {
                // Legacy transaction format - no version prefix
                val signatureCount = Shortvec.encodeLength(signatures.count())
                val transactionLength = signatureCount.count() + signatures.count() * 64 + signData.count()
                val wireTransaction = PlatformBuffer.allocate(transactionLength)
                
                require(signatures.count() < 256)
                wireTransaction.writeBytes(signatureCount)
                signatures.forEach { (signature, _) ->
                    when {
                        signature !== null -> {
                            require(signature.count() == 64) { "signature has invalid length" }
                            wireTransaction.writeBytes(signature)
                        }
                        else -> {
                            wireTransaction.writeBytes(ByteArray(SIGNATURE_LENGTH))
                        }
                    }
                }
                wireTransaction.writeBytes(signData)
                wireTransaction.resetForRead()
                val out = wireTransaction.readByteArray(transactionLength)
                require(out.count() <= PACKET_DATA_SIZE) {
                    "Transaction too large: ${out.count()} > $PACKET_DATA_SIZE"
                }
                out
            }
        }

//        val signatureCount = Shortvec.encodeLength(versioned.signatures.count())
//        val transactionLength = signatureCount.count() + versioned.signatures.count() * 64 + signData.count()
    }

    override fun serializeMessage(): SerializedTransactionMessage =
        compile().serialize()

    override fun addSignature(pubkey: PublicKey, signature: TransactionSignature) {
        compile() // Ensure signatures array is populated
        _addSignature(pubkey, signature)
    }

    override fun compileMessage(): Message {
        TODO("Not yet implemented")
    }

    private fun _addSignature(pubkey: PublicKey, signature: TransactionSignature) {
        require(signature.count() == 64)

        val pubkeyBase58 = pubkey.toBase58()
        val index = versioned.signatures.indexOfFirst { sigpair ->
            sigpair.publicKey.toBase58() == pubkeyBase58
        }
        if (index < 0) {
            throw Error("unknown signer: $pubkey")
        }

        versioned.signatures[index].signature = signature
    }
    private fun toSolanaPublicKey(publicKey: PublicKey): SolanaPublicKey{
        return SolanaPublicKey.from(publicKey.toBase58())
    }
    fun compileVersionedMessage(): Message {
        this.nonceInfo?.let { nonceInfo ->
            if (instructions.first() != nonceInfo.nonceInstruction) {
                recentBlockhash = nonceInfo.nonce
                instructions.add(0, nonceInfo.nonceInstruction)
            }
        }
        require(recentBlockhash.isNotEmpty()) { "Transaction recentBlockhash required" }

        if (instructions.isEmpty()) {
            print("No instructions provided")
        }

        val feePayer = feePayer ?: signatures.firstOrNull()?.publicKey
        requireNotNull(feePayer) { "Transaction fee payer required" }
        
        android.util.Log.d("AltudeTxBuilder", "=== compileVersionedMessage START ===")
        android.util.Log.d("AltudeTxBuilder", "Set fee payer: ${feePayer.toBase58()}")
        android.util.Log.d("AltudeTxBuilder", "this.feePayer: ${this.feePayer?.toBase58() ?: "NULL"}")
        android.util.Log.d("AltudeTxBuilder", "Transaction version: $transacionVersion")

        val programIds = mutableSetOf<String>() // Store as Base58 for reliable comparison
        val accountMetas = mutableListOf<AccountMeta>()
        for (instruction in instructions) {
            for (accountMeta in instruction.keys) {
                accountMetas.add(accountMeta)
            }
            programIds.add(instruction.programId.toBase58())
        }
        
        android.util.Log.d("AltudeTxBuilder", "Collected ${accountMetas.size} account metas from instructions")
        android.util.Log.d("AltudeTxBuilder", "Program IDs: $programIds")

        // Cull duplicate account metas, EXCLUDING program IDs
        // Program IDs will be added at the very end
        val uniqueMetas = mutableListOf<AccountMeta>()
        for (accountMeta in accountMetas) {
            val pubkeyString = accountMeta.publicKey.toBase58()
            
            // Skip if this is a program ID - we'll add it at the end
            if (programIds.contains(pubkeyString)) {
                continue
            }
            
            val uniqueIndex = uniqueMetas.indexOfFirst { it.publicKey.toBase58() == pubkeyString }
            if (uniqueIndex > -1) {
                // When merging duplicates, preserve the highest privilege level
                uniqueMetas[uniqueIndex].isWritable =
                    uniqueMetas[uniqueIndex].isWritable || accountMeta.isWritable
                uniqueMetas[uniqueIndex].isSigner =
                    uniqueMetas[uniqueIndex].isSigner || accountMeta.isSigner
            } else {
                uniqueMetas.add(accountMeta)
            }
        }

        android.util.Log.d("AltudeTxBuilder", "=== BEFORE SORTING ===")
        android.util.Log.d("AltudeTxBuilder", "uniqueMetas (${uniqueMetas.size}):")
        uniqueMetas.forEachIndexed { index, meta ->
            android.util.Log.d("AltudeTxBuilder", "  [$index] ${meta.publicKey.toBase58()} signer=${meta.isSigner} writable=${meta.isWritable}")
        }
        
        // Sort. Prioritizing first by signer, then by writable
        // Note: We use stable sort to preserve insertion order for accounts with same flags
        uniqueMetas.sortWith { x, y ->
            if (x.isSigner != y.isSigner) {
                // Signers always come before non-signers
                return@sortWith if (x.isSigner) -1 else 1
            }
            if (x.isWritable != y.isWritable) {
                // Writable accounts always come before read-only accounts
                return@sortWith if (x.isWritable) -1 else 1
            }
            // For accounts with same flags, preserve original order (return 0)
            return@sortWith 0
        }
        
        android.util.Log.d("AltudeTxBuilder", "=== AFTER SORTING ===")
        android.util.Log.d("AltudeTxBuilder", "uniqueMetas (${uniqueMetas.size}):")
        uniqueMetas.forEachIndexed { index, meta ->
            android.util.Log.d("AltudeTxBuilder", "  [$index] ${meta.publicKey.toBase58()} signer=${meta.isSigner} writable=${meta.isWritable}")
        }

        // Move fee payer to the front
        val feePayerBase58 = feePayer.toBase58()
        android.util.Log.d("AltudeTxBuilder", "Looking for fee payer: $feePayerBase58")
        val feePayerIndex = uniqueMetas.indexOfFirst { it.publicKey.toBase58() == feePayerBase58 }
        android.util.Log.d("AltudeTxBuilder", "Fee payer index in uniqueMetas: $feePayerIndex")
        
        if (feePayerIndex > -1) {
            val payerMeta = uniqueMetas.removeAt(feePayerIndex)
            android.util.Log.d("AltudeTxBuilder", "Moving fee payer ${payerMeta.publicKey.toBase58()} to index 0")
            payerMeta.isSigner = true
            payerMeta.isWritable = true
            uniqueMetas.add(0, payerMeta)
        } else {
            android.util.Log.d("AltudeTxBuilder", "Fee payer NOT found in uniqueMetas, adding to index 0")
            uniqueMetas.add(
                index = 0,
                element = AccountMeta(
                    publicKey = feePayer,
                    isSigner = true,
                    isWritable = true
                )
            )
        }
        
        android.util.Log.d("AltudeTxBuilder", "=== AFTER FEE PAYER MOVE ===")
        android.util.Log.d("AltudeTxBuilder", "uniqueMetas (${uniqueMetas.size}):")
        uniqueMetas.forEachIndexed { index, meta ->
            android.util.Log.d("AltudeTxBuilder", "  [$index] ${meta.publicKey.toBase58()} signer=${meta.isSigner} writable=${meta.isWritable}")
        }

        // Now add program IDs that aren't already in the list (at the end, as readonly non-signers)
        for (programIdBase58 in programIds) {
            if (uniqueMetas.none { it.publicKey.toBase58() == programIdBase58 }) {
                uniqueMetas.add(
                    AccountMeta(
                        publicKey = PublicKey(programIdBase58),
                        isSigner = false,
                        isWritable = false
                    )
                )
            }
        }

        // Disallow unknown signers
        for (signature in signatures) {
            val sigPubkeyBase58 = signature.publicKey.toBase58()
            val uniqueIndex = uniqueMetas.indexOfFirst { it.publicKey.toBase58() == sigPubkeyBase58 }
            if (uniqueIndex > -1) {
                if (!uniqueMetas[uniqueIndex].isSigner) {
                    uniqueMetas[uniqueIndex].isSigner = true
                    print(
                        "Transaction references a signature that is unnecessary, " +
                                "only the fee payer and instruction signer accounts should sign a transaction. " +
                                "This behavior is deprecated and will throw an error in the next major version release"
                    )
                }
            } else {
                throw Error("unknown signer: ${signature.publicKey}")
            }
        }

        var numRequiredSignatures = 0
        var numReadonlySignedAccounts = 0
        var numReadonlyUnsignedAccounts = 0

        // Split out signing from non-signing keys and count header values
        val signedKeys = mutableListOf<PublicKey>()
        val unsignedKeys = mutableListOf<PublicKey>()
        uniqueMetas.forEach {
            if (it.isSigner) {
                signedKeys.add(it.publicKey)
                numRequiredSignatures += 1
                if (!it.isWritable) {
                    numReadonlySignedAccounts += 1
                }
            } else {
                unsignedKeys.add(it.publicKey)
                if (!it.isWritable) {
                    numReadonlyUnsignedAccounts += 1
                }
            }
        }

        val accountKeys = signedKeys.plus(unsignedKeys)
        
        // Debug: Log the final account order
        android.util.Log.d("AltudeTxBuilder", "=== COMPILED MESSAGE DEBUG ===")
        android.util.Log.d("AltudeTxBuilder", "Header: numReqSigs=$numRequiredSignatures, readonlySigned=$numReadonlySignedAccounts, readonlyUnsigned=$numReadonlyUnsignedAccounts")
        android.util.Log.d("AltudeTxBuilder", "Account Keys (${accountKeys.size}):")
        accountKeys.forEachIndexed { index, pk ->
            android.util.Log.d("AltudeTxBuilder", "  [$index] ${pk.toBase58()}")
        }
        
        val compiledInstructions: List<CompiledInstruction> = instructions.map { instruction ->
            val (programId, _, data) = instruction
            
            val programIdIndex = accountKeys.indexOfFirst { it.toBase58() == programId.toBase58() }
            val accountIndices = instruction.keys.map { meta ->
                accountKeys.indexOfFirst { it.toBase58() == meta.publicKey.toBase58() }
            }
            
            // Debug: Log instruction compilation
            android.util.Log.d("AltudeTxBuilder", "Instruction compilation:")
            android.util.Log.d("AltudeTxBuilder", "  ProgramId: ${programId.toBase58()} -> idx $programIdIndex")
            android.util.Log.d("AltudeTxBuilder", "  Instruction accounts (${instruction.keys.size}):")
            instruction.keys.forEachIndexed { i, meta ->
                val mappedIndex = accountIndices[i]
                android.util.Log.d("AltudeTxBuilder", "    [$i] ${meta.publicKey.toBase58()} -> idx $mappedIndex")
            }
            android.util.Log.d("AltudeTxBuilder", "  Account indices: $accountIndices")
            
            // Debug: Log the raw instruction data before Base58 encoding
            android.util.Log.d("AltudeTxBuilder", "Raw instr data (${data.size} bytes):")
            android.util.Log.d("AltudeTxBuilder", "  First 8: ${data.take(8).joinToString(",") { (it.toInt() and 0xFF).toString() }}")
            android.util.Log.d("AltudeTxBuilder", "  Hex: ${data.take(16).joinToString("") { "%02x".format(it) }}")
            
            val encoded = Base58Util.encode(data)
            android.util.Log.d("AltudeTxBuilder", "  Base58: $encoded")
            
            // Debug: Verify decode matches original
            val decoded = Base58Util.decode(encoded)
            android.util.Log.d("AltudeTxBuilder", "  Decoded (${decoded.size} bytes):")
            android.util.Log.d("AltudeTxBuilder", "  First 8: ${decoded.take(8).joinToString(",") { (it.toInt() and 0xFF).toString() }}")
            android.util.Log.d("AltudeTxBuilder", "  Match: ${data.contentEquals(decoded)}")
            
            // Verify all indices are valid
            require(programIdIndex >= 0) { "Program ID not found in account keys" }
            require(programIdIndex < accountKeys.size) { "Program ID index out of bounds: $programIdIndex >= ${accountKeys.size}" }
            accountIndices.forEachIndexed { i, idx ->
                require(idx >= 0) { "Account[$i] not found in account keys" }
                require(idx < accountKeys.size) { "Account[$i] index out of bounds: $idx >= ${accountKeys.size}" }
            }
            
            CompiledInstruction(
                programIdIndex = programIdIndex,
                accounts = accountIndices,
                data = encoded
            )
        }

        for (instruction in compiledInstructions) {
            require(instruction.programIdIndex >= 0)
            instruction.accounts.forEach { keyIndex -> require(keyIndex >= 0) }
        }
        
        // Summary log
        android.util.Log.d("AltudeTxBuilder", "=== FINAL MESSAGE SUMMARY ===")
        android.util.Log.d("AltudeTxBuilder", "Version: $transacionVersion")
        android.util.Log.d("AltudeTxBuilder", "numRequiredSignatures: $numRequiredSignatures")
        android.util.Log.d("AltudeTxBuilder", "numReadonlySignedAccounts: $numReadonlySignedAccounts")
        android.util.Log.d("AltudeTxBuilder", "numReadonlyUnsignedAccounts: $numReadonlyUnsignedAccounts")
        android.util.Log.d("AltudeTxBuilder", "Blockhash: $recentBlockhash")
        android.util.Log.d("AltudeTxBuilder", "Account keys (${accountKeys.size}):")
        accountKeys.forEachIndexed { index, pk ->
            val isSigner = index < numRequiredSignatures
            val isWritable = if (isSigner) {
                index < (numRequiredSignatures - numReadonlySignedAccounts)
            } else {
                val unsignedIndex = index - numRequiredSignatures
                val numUnsigned = accountKeys.size - numRequiredSignatures
                unsignedIndex < (numUnsigned - numReadonlyUnsignedAccounts)
            }
            android.util.Log.d("AltudeTxBuilder", "  [$index] ${pk.toBase58()} (signer=$isSigner, writable=$isWritable)")
        }
        android.util.Log.d("AltudeTxBuilder", "Compiled instructions (${compiledInstructions.size}):")
        compiledInstructions.forEachIndexed { index, instr ->
            android.util.Log.d("AltudeTxBuilder", "  [instr $index] programIdIndex=${instr.programIdIndex}, accounts=${instr.accounts}")
        }
        android.util.Log.d("AltudeTxBuilder", "=== END SUMMARY ===")
        
        // 4. Return Message
        return (when (transacionVersion) {
            TransactionVersion.V0 ->
                VersionedMessage(
                version = VERSION_BIT.toByte(),
                signatureCount = numRequiredSignatures.toUByte(),
                readOnlyAccounts = numReadonlySignedAccounts.toUByte(),
                readOnlyNonSigners = numReadonlyUnsignedAccounts.toUByte(),
                accounts = accountKeys,
                blockhash = recentBlockhash,
                instructions = compiledInstructions,
                addressTableLookups = addressTableLookups
            )

            TransactionVersion.Legacy ->
                // Use custom LegacyMessage for consistent serialization
                LegacyMessage(
                    header = MessageHeader().apply {
                        this.numRequiredSignatures = numRequiredSignatures.toByte()
                        this.numReadonlySignedAccounts = numReadonlySignedAccounts.toByte()
                        this.numReadonlyUnsignedAccounts = numReadonlyUnsignedAccounts.toByte()
                    },
                    accountKeys = accountKeys,
                    recentBlockhash = recentBlockhash,
                    instructions = compiledInstructions
                )
        })
    }

    fun getMessageV0(): Message = versioned.message
}
