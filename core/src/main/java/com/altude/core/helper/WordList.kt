package com.altude.core.helper


import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

import kotlinx.coroutines.runBlocking
import java.util.BitSet

interface IWordlistSource {
    suspend fun load(): WordList
}
class WordList(
    private val words: List<String>,
    val space: Char,
) {
    companion object {
        private val wordlistSource: IWordlistSource = HardcodedWordlistSource()

        private val _english = AtomicReference<WordList?>()
        private val _japanese = AtomicReference<WordList?>()
        private val _spanish = AtomicReference<WordList?>()
        private val _chineseSimplified = AtomicReference<WordList?>()
        private val _chineseTraditional = AtomicReference<WordList?>()
        private val _french = AtomicReference<WordList?>()
        private val _portugueseBrazil = AtomicReference<WordList?>()
        private val _czech = AtomicReference<WordList?>()

        val English: WordList get() = _english.updateAndGet { it ?: runBlocking { loadWordList() } }!!
        suspend fun loadWordList(): WordList {

            val wl = wordlistSource.load()

            return wl
        }

        fun toBits(values: IntArray): BitSet {
            if (values.any { it >= 2048 }) {
                throw IllegalArgumentException("values should be between 0 and 2048")
            }

            val bitSet = BitSet(values.size * 11)
            var bitIndex = 0

            for (value in values) {
                for (i in 10 downTo 0) {
                    bitSet.set(bitIndex++, value and (1 shl i) != 0)
                }
            }

            return bitSet
        }
    }

    val wordCount: Int get() = words.size

    fun wordExists(word: String): Boolean = wordExists(word, null)

    fun wordExists(word: String, indexOut: IntArray?): Boolean {
        val normalized = Mnemonic.normalizeString(word)
        val index = words.indexOf(normalized)
        if (index != -1) {
            indexOut?.set(0, index)
            return true
        }
        indexOut?.set(0, -1)
        return false
    }

    private fun getWordAtIndex(index: Int): String = words[index]

    fun getWords(): List<String> = words

    fun getWords(indices: IntArray): List<String> = indices.map(::getWordAtIndex)

    fun getSentence(indices: IntArray): String = getWords(indices).joinToString(space.toString())

    fun toIndices(words: List<String>): IntArray {
        return words.map {
            val index = this.words.indexOf(Mnemonic.normalizeString(it))
            if (index == -1) {
                throw IllegalArgumentException("Word \"$it\" is not in the wordlist for this language")
            }
            index
        }.toIntArray()
    }
    fun fromIndices(indices: IntArray): List<String> {
        return indices.map {
            if (it !in words.indices) {
                throw IndexOutOfBoundsException("Index $it is out of bounds")
            }
            words[it]
        }
    }
}

