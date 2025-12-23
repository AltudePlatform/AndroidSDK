package com.altude.core.model

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.metaplex.signer.Signer
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AddressTableLookup
import com.solana.transaction.Instruction
import com.solana.transaction.LegacyMessage
import com.solana.transaction.Message
import com.solana.transaction.VersionedMessage
import foundation.metaplex.solana.transactions.AccountMeta
import foundation.metaplex.solana.transactions.Blockhash
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
        val signedKeys = message.accounts.slice(
            0 until message.signatureCount.toByte()
        )

        if (this.signatures.count() == signedKeys.count()) {
            var valid = true
            this.signatures.forEachIndexed { index, pair ->
                if (!signedKeys[index].equals(toSolanaPublicKey(pair.publicKey))) {
                    valid = false
                    return@forEachIndexed
                }
            }
            if (valid) return message
        }

        this.signatures = signedKeys.map { publicKey ->
            SignaturePubkeyPair(
                signature = null,
                publicKey = PublicKey(publicKey.bytes)
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
            val match = versioned.signatures.firstOrNull {
                it.publicKey == signer.publicKey
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
        return out

//        val signatureCount = Shortvec.encodeLength(versioned.signatures.count())
//        val transactionLength = signatureCount.count() + versioned.signatures.count() * 64 + signData.count()
    }

    override fun serializeMessage(): SerializedTransactionMessage =
        compile().serialize()

    override fun addSignature(pubkey: PublicKey, signature: TransactionSignature) {
        compile() // Ensure signatures array is populated
        _addSignature(pubkey, signature)
    }

    override fun compileMessage(): foundation.metaplex.solana.transactions.Message {
        TODO("Not yet implemented")
    }

    private fun _addSignature(pubkey: PublicKey, signature: TransactionSignature) {
        require(signature.count() == 64)

        val index = versioned.signatures.indexOfFirst { sigpair ->
            pubkey.equals(sigpair.publicKey)
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

        val programIds = mutableSetOf<PublicKey>()
        val accountMetas = mutableListOf<AccountMeta>()
        for (instruction in instructions) {
            for (accountMeta in instruction.keys) {
                accountMetas.add(accountMeta)
            }
            programIds.add(instruction.programId)
        }

        // Append programID account metas
        for (programId in programIds) {
            accountMetas.add(
                AccountMeta(
                    publicKey = programId,
                    isSigner = false,
                    isWritable = false
                )
            )
        }

        // Cull duplicate account metas
        val uniqueMetas = mutableListOf<AccountMeta>()
        for (accountMeta in accountMetas) {
            val pubkeyString = accountMeta.publicKey.toBase58()
            val uniqueIndex = uniqueMetas.indexOfFirst { it.publicKey.toBase58() == pubkeyString }
            if (uniqueIndex > -1) {
                uniqueMetas[uniqueIndex].isWritable =
                    uniqueMetas[uniqueIndex].isWritable || accountMeta.isWritable
            } else {
                uniqueMetas.add(accountMeta)
            }
        }

        // Sort. Prioritizing first by signer, then by writable
        uniqueMetas.sortWith { x, y ->
            if (x.isSigner != y.isSigner) {
                // Signers always come before non-signers
                return@sortWith if (x.isSigner) -1 else  1
            }
            if (x.isWritable != y.isWritable) {
                // Writable accounts always come before read-only accounts
                return@sortWith if (x.isWritable) -1 else 1
            }
            // Otherwise, sort by pubkey, stringwise.
            return@sortWith x.publicKey.toBase58().compareTo(y.publicKey.toBase58())
        }

        // Move fee payer to the front
        val feePayerIndex = uniqueMetas.indexOfFirst { it.publicKey.equals(feePayer) }
        if (feePayerIndex > -1) {
            val payerMeta = uniqueMetas.removeAt(feePayerIndex)
            payerMeta.isSigner = true
            payerMeta.isWritable = true
            uniqueMetas.add(0, payerMeta)
        } else {
            uniqueMetas.add(
                index = 0,
                element = AccountMeta(
                    publicKey = feePayer,
                    isSigner = true,
                    isWritable = true
                )
            )
        }

        // Disallow unknown signers
        for (signature in signatures) {
            val uniqueIndex = uniqueMetas.indexOfFirst { it.publicKey.equals(signature.publicKey) }
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
        val signedKeys = mutableListOf<SolanaPublicKey>()
        val unsignedKeys = mutableListOf<SolanaPublicKey>()
        uniqueMetas.forEach {
            if (it.isSigner) {
                signedKeys.add(toSolanaPublicKey( it.publicKey))
                numRequiredSignatures += 1
                if (!it.isWritable) {
                    numReadonlySignedAccounts += 1
                }
            } else {
                unsignedKeys.add(toSolanaPublicKey( it.publicKey))
                if (!it.isWritable) {
                    numReadonlyUnsignedAccounts += 1
                }
            }
        }

        val accountKeys = signedKeys.plus(unsignedKeys)
        val instructions: List<Instruction> = instructions.map { instruction ->
            val (programId, _, data) = instruction
            Instruction(
                programIdIndex = accountKeys.indexOf(SolanaPublicKey.from(programId.toBase58())).toUByte(),
                accountIndices = instruction.keys.map { meta ->
                    accountKeys.indexOf(SolanaPublicKey.from(meta.publicKey.toBase58())).toByte()
                }.toByteArray(),
                data = data
            )
        }

        for (instruction in instructions) {
            require(instruction.programIdIndex >= 0.toUByte())
            instruction.accountIndices.forEach { keyIndex -> require(keyIndex >= 0) }
        }
        // 4. Return Message
        return (when (transactionVersion) {
            TransactionVersion.V0 -> VersionedMessage(
                version = VERSION_BIT.toByte(),
                signatureCount = numRequiredSignatures.toUByte(),
                readOnlyAccounts = numReadonlySignedAccounts.toUByte(),
                readOnlyNonSigners = numReadonlyUnsignedAccounts.toUByte(),
                accounts = accountKeys,
                blockhash = SolanaPublicKey.from(recentBlockhash),
                instructions = instructions,
                addressTableLookups = addressTableLookups
            )
            TransactionVersion.Legacy -> return LegacyMessage(
                numRequiredSignatures.toUByte() ,
                numReadonlySignedAccounts.toUByte(),
                numReadonlyUnsignedAccounts.toUByte(),
                accountKeys.toList(),
                SolanaPublicKey.from(recentBlockhash),
                instructions
            )
        })
    }

    fun getMessageV0(): Message = versioned.message
}
