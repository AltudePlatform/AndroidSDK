package com.altude.core.helper

import com.altude.core.helper.WordList
import com.altude.core.model.KeyPair
import com.altude.core.model.SolanaKeypair
import diglol.crypto.Ed25519
import foundation.metaplex.base58.encodeToBase58String
import foundation.metaplex.solanaeddsa.SolanaEddsa
import kotlinx.serialization.encoding.Encoder
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.random.Random

class Mnemonic (
    var seedPhrase: String = "",
    var passphrase: String = "",
    var wordList: WordList = WordList.English
){



    companion object {
        private var MsArray = arrayOf(12, 15, 18, 21, 24)
        private val CsArray = arrayOf(4, 5, 6, 7, 8)
        private val EntArray = arrayOf(128, 160, 192, 224, 256)
        private val utf8NoBom = StandardCharsets.UTF_8


        //lateinit var wordList: WordList
        lateinit var indices: IntArray
        lateinit var words: List<String>
        //private lateinit var mnemonic: String
        private var isValidChecksum: Boolean? = null
        private var supportOsNormalization: Boolean? = null
        fun normalizeString(word: String): String {
            return if (isOsNormalizationSupported()) {
                Normalizer.normalize(word as CharSequence?, Normalizer.Form.NFKD)
            } else {
                // fallback if needed
                Normalizer.normalize(word as CharSequence?, Normalizer.Form.NFKD)
            }
        }

        private fun isOsNormalizationSupported(): Boolean {
            if (supportOsNormalization != null) return supportOsNormalization!!
            return try {
                val normalized = Normalizer.normalize("あおぞら", Normalizer.Form.NFKD)
                supportOsNormalization = normalized == "あおそ\u3099ら"
                supportOsNormalization!!
            } catch (e: Exception) {
                supportOsNormalization = false
                false
            }
        }
        private fun encode(entropy: ByteArray, wordList: WordList = WordList.English): String {
            val checksumBits = sha256(entropy)[0].toInt() and 0xFF
            val entBits = entropy.toBits()
            val checksumLen = entropy.size * 8 / 32
            val checksumStr = checksumBits.toString(2).padStart(8, '0').substring(0, checksumLen)

            val bits = entBits + checksumStr
            val wordIndexes = bits.chunked(11).map { it.toInt(2) }.toIntArray()
            return wordList.getSentence(wordIndexes)
        }
        fun sha256(input: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(input)

        }

        private fun ByteArray.toBits(): String = this.joinToString("") {
            it.toInt().and(0xFF).toString(2).padStart(8, '0')
        }
        private fun generateEntropy(wordCount: Int): ByteArray {
            val index = MsArray.indexOf(wordCount)
            if (index == -1) throw IllegalArgumentException("Word count must be 12, 15, 18, 21, or 24.")

            val byteLength = EntArray[index] / 8
            val entropy = ByteArray(byteLength)
            SecureRandom().nextBytes(entropy)
            return entropy
        }

        fun generateMnemonic(wordCount: Int = 12, wordList: WordList = WordList.English): String {
            val entropy = generateEntropy(wordCount)
            return encode(entropy)
        }


    }

    private fun concat(a: ByteArray, b: ByteArray): ByteArray {
        return a + b
    }

    private fun normalizeBytes(str: String): ByteArray {
        return normalizeString(str).toByteArray(utf8NoBom)
    }

    private fun correctWordCount(count: Int): Boolean {
        return MsArray.contains(count)
    }

    private fun generateSeed(password: ByteArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(
            String(password, utf8NoBom).toCharArray(),
            salt,
            2048,
            512
        )
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return skf.generateSecret(spec).encoded
    }


//    fun create(wordList: WordList, wordCount: Int) {// not tested this yet
//        val entropy = generateEntropy(wordCount)
//        mnemonic(wordList, entropy)
//    }
    suspend fun getKeyPair(): SolanaKeypair {
        if(seedPhrase == "")
           throw Error("Please enter a seed phrase; Ex. val mnemonic =  Mnemonic(\"word word word word word word word word word word word word\")")
        mnemonic(seedPhrase, wordList)
        //val bip32 = HMac(SHA512Digest())
        val (privateKey, _) = Ed25519Bip32(deriveSeed(passphrase)).derivePath("m/44'/501'/0'/0'")
        val solanaKeypair = KeyPair.solanaKeyPairFromPrivateKey(privateKey)
        return  solanaKeypair
    }

    private fun mnemonic(wordList: WordList = WordList.English, entropy: ByteArray? = null) {
        val actualEntropy = entropy ?: Random.nextBytes(32)

        val entBits = actualEntropy.size * 8
        val index = EntArray.indexOf(entBits)
        if (index == -1) {
            throw IllegalArgumentException("The length for entropy should be ${EntArray.joinToString()} bits")
        }

        val checksumBits = CsArray[index]
        val hash = sha256(actualEntropy)

        val bitWriter = BitWriter()
        bitWriter.write(actualEntropy)
        bitWriter.write(hash, checksumBits)
        indices = bitWriter.toIntegers()
        words = wordList.getWords(indices)
        seedPhrase = wordList.getSentence(indices)
    }


    // Construct from mnemonic string
    private fun mnemonic(mnemonic: String, wordList: WordList = WordList.English) {
        require(mnemonic.isNotBlank()) { "Mnemonic cannot be null or blank." }

        val list = wordList
        val split = normalizeString(mnemonic).split("\\s+".toRegex()).filter { it.isNotBlank() }

        if (!correctWordCount(split.size)) {
            throw IllegalArgumentException("Word count must be 12, 15, 18, 21 or 24.")
        }

        words = split
        this.wordList = list
        indices = list.toIndices(split)
        this.seedPhrase = words.joinToString(list.space.toString())
    }

    // Construct from entropy


    fun isValidChecksum(): Boolean {
        if (isValidChecksum != null) return isValidChecksum!!

        val wordCountIndex = MsArray.indexOf(indices.size)
        val entropyBitsLen = EntArray[wordCountIndex]
        val checksumBitsLen = CsArray[wordCountIndex]

        val bits = indices.joinToString("") { it.toString(2).padStart(11, '0') }
        val entropyBits = bits.substring(0, entropyBitsLen)
        val checksumBits = bits.substring(entropyBitsLen)

        val entropyBytes = entropyBits.chunked(8).map { it.toInt(2).toByte() }.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(entropyBytes)

        val expectedChecksumBits = hash[0].toInt().ushr(8 - checksumBitsLen)
            .toString(2)
            .padStart(checksumBitsLen, '0')
        //SolanaEddsa.createKeypairFromSeed()
        isValidChecksum = (checksumBits == expectedChecksumBits)
        return isValidChecksum!!
    }

    fun deriveSeed(passphrase: String = ""): ByteArray {
        val salt = concat("mnemonic".toByteArray(utf8NoBom), normalizeBytes(passphrase))
        return generateSeed(normalizeBytes(seedPhrase), salt)
    }

    override fun toString(): String {
        return seedPhrase
    }

}
class Ed25519Bip32(seed: ByteArray) {
    private val masterKey: ByteArray
    private val chainCode: ByteArray

    init {
        val (key, code) = getMasterKeyFromSeed(seed)
        masterKey = key
        chainCode = code
    }
    fun getMasterKeyFromSeed(seed: ByteArray): Pair<ByteArray, ByteArray> {
        val key = "ed25519 seed".toByteArray(Charsets.UTF_8)
        return hmacSha512(key, seed)
    }
    fun hmacSha512(keyBuffer: ByteArray, data: ByteArray): Pair<ByteArray, ByteArray> {
        val hmac = HMac(SHA512Digest())
        hmac.init(KeyParameter(keyBuffer))
        hmac.update(data, 0, data.size)

        val fullHash = ByteArray(64)
        hmac.doFinal(fullHash, 0)

        val key = fullHash.copyOfRange(0, 32)
        val chainCode = fullHash.copyOfRange(32, 64)

        return Pair(key, chainCode)
    }
//    fun derive(index: Int): Pair<ByteArray, ByteArray> {
//        val data = ByteArray(1 + 32 + 4)
//        data[0] = 0
//        masterKey.copyInto(data, 1)
//        val hardenedIndex = index or 0x80000000.toInt()
//        data[33] = ((hardenedIndex shr 24) and 0xFF).toByte()
//        data[34] = ((hardenedIndex shr 16) and 0xFF).toByte()
//        data[35] = ((hardenedIndex shr 8) and 0xFF).toByte()
//        data[36] = (hardenedIndex and 0xFF).toByte()
//
//        val hmac = HMac(SHA512Digest())
//        hmac.init(KeyParameter(chainCode))
//        hmac.update(data, 0, data.size)
//        val out = ByteArray(64)
//        hmac.doFinal(out, 0)
//        val newKey = out.copyOfRange(0, 32)
//        val newChainCode = out.copyOfRange(32, 64)
//        return newKey to newChainCode
//    }
    fun derivePath(path: String): Pair<ByteArray, ByteArray> {
        if (!path.startsWith("m/")) throw IllegalArgumentException("Path must start with m/")
        val segments = path.removePrefix("m/").split("/")

        var key = masterKey
        var code = chainCode

        for (segment in segments) {
            val hardened = segment.endsWith("'")
            val index = segment.removeSuffix("'").toInt()
            val realIndex = if (hardened) index or 0x80000000.toInt() else index

            val data = ByteArray(1 + 32 + 4)
            data[0] = 0
            key.copyInto(data, 1)
            data[33] = ((realIndex shr 24) and 0xFF).toByte()
            data[34] = ((realIndex shr 16) and 0xFF).toByte()
            data[35] = ((realIndex shr 8) and 0xFF).toByte()
            data[36] = (realIndex and 0xFF).toByte()

            val hmac = HMac(SHA512Digest())
            hmac.init(KeyParameter(code))
            hmac.update(data, 0, data.size)
            val out = ByteArray(64)
            hmac.doFinal(out, 0)

            key = out.copyOfRange(0, 32)
            code = out.copyOfRange(32, 64)
        }

        return key to code
    }

}
