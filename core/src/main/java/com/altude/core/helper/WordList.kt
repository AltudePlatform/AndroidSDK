package com.altude.core.helper


import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

import kotlinx.coroutines.runBlocking
import java.util.BitSet

interface IWordlistSource {
    /**
     * Load the wordlist.
     *
     * @param name The name of the wordlist.
     * @return The loaded WordList.
     */
    suspend fun load(name: String): WordList
}
class WordList(
    private val words: List<String>,
    val space: Char,
    private val name: String
) {
    companion object {
        private val loadedLists = ConcurrentHashMap<String, WordList>()
        private val wordlistSource: IWordlistSource = HardcodedWordlistSource()

        private val _english = AtomicReference<WordList?>()
        private val _japanese = AtomicReference<WordList?>()
        private val _spanish = AtomicReference<WordList?>()
        private val _chineseSimplified = AtomicReference<WordList?>()
        private val _chineseTraditional = AtomicReference<WordList?>()
        private val _french = AtomicReference<WordList?>()
        private val _portugueseBrazil = AtomicReference<WordList?>()
        private val _czech = AtomicReference<WordList?>()

        val English: WordList get() = _english.updateAndGet { it ?: runBlocking { loadWordList(Language.ENGLISH) } }!!
        val Japanese: WordList get() = _japanese.updateAndGet { it ?: runBlocking { loadWordList(Language.JAPANESE) } }!!
        val Spanish: WordList get() = _spanish.updateAndGet { it ?: runBlocking { loadWordList(Language.SPANISH) } }!!
        val ChineseSimplified: WordList get() = _chineseSimplified.updateAndGet { it ?: runBlocking { loadWordList(Language.ChineseSimplified) } }!!
        val ChineseTraditional: WordList get() = _chineseTraditional.updateAndGet { it ?: runBlocking { loadWordList(Language.ChineseTraditional) } }!!
        val French: WordList get() = _french.updateAndGet { it ?: runBlocking { loadWordList(Language.FRENCH) } }!!
        val PortugueseBrazil: WordList get() = _portugueseBrazil.updateAndGet { it ?: runBlocking { loadWordList(Language.PortugueseBrazil) } }!!
        val Czech: WordList get() = _czech.updateAndGet { it ?: runBlocking { loadWordList(Language.CZECH) } }!!

        private fun getLanguageFileName(language: Language): String = when (language) {
            Language.ChineseTraditional -> "chinesetraditional"
            Language.ChineseSimplified -> "chinesesimplified"
            Language.ENGLISH -> "english"
            Language.JAPANESE -> "japanese"
            Language.SPANISH -> "spanish"
            Language.FRENCH -> "french"
            Language.PortugueseBrazil -> "portuguesebrazil"
            Language.CZECH -> "czech"
            else -> throw NotImplementedError("Language ${language.name} not supported")
        }

        suspend fun loadWordList(language: Language): WordList {
            return loadWordList(getLanguageFileName(language))
        }

        suspend fun loadWordList(name: String): WordList {
            loadedLists[name]?.let { return it }

            val wl = wordlistSource.load(name)

            loadedLists[name] = wl
            return wl
        }

        fun autoDetect(sentence: String): WordList {
            return runBlocking {
                loadWordList(autoDetectLanguage(sentence))
            }
        }

        fun autoDetectLanguage(sentence: String): Language {
            return autoDetectLanguage(sentence.split(' ', '\u3000'))
        }

        fun autoDetectLanguage(words: List<String>): Language {
            val counts = IntArray(8)

            words.forEach { word ->
                if (English.wordExists(word)) counts[0]++
                if (Japanese.wordExists(word)) counts[1]++
                if (Spanish.wordExists(word)) counts[2]++
                if (ChineseSimplified.wordExists(word)) counts[3]++
                if (ChineseTraditional.wordExists(word) && !ChineseSimplified.wordExists(word)) counts[4]++
                if (French.wordExists(word)) counts[5]++
                if (PortugueseBrazil.wordExists(word)) counts[6]++
                if (Czech.wordExists(word)) counts[7]++
            }

            if (counts.maxOrNull() == 0) return Language.UNKNOWN

            return when (counts.indices.maxByOrNull { counts[it] }) {
                0 -> Language.ENGLISH
                1 -> Language.JAPANESE
                2 -> Language.SPANISH
                3 -> if (counts[4] > 0) Language.ChineseTraditional else Language.ChineseSimplified
                4 -> Language.ChineseTraditional
                5 -> Language.FRENCH
                6 -> Language.PortugueseBrazil
                7 -> Language.CZECH
                else -> Language.UNKNOWN
            }
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

    override fun toString(): String = name

}

