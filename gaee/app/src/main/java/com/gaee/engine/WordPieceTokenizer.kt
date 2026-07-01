package com.gaee.engine

import java.io.File

class WordPieceTokenizer(vocabFile: File) {

    private val vocab: Map<String, Int>

    companion object {
        const val PAD_ID = 0
        const val UNK_ID = 100
        const val CLS_ID = 101
        const val SEP_ID = 102
        const val MAX_SEQ_LEN = 128
    }

    init {
        val v = mutableMapOf<String, Int>()
        var idx = 0
        vocabFile.forEachLine { line ->
            val token = line.trim()
            if (token.isNotEmpty()) v[token] = idx++
        }
        vocab = v
    }

    data class Encoding(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray,
        val seqLen: Int
    )

    fun encode(text: String): Encoding {
        val wordPieces = wordPieceTokenize(text.lowercase().trim())
        val truncated = if (wordPieces.size > MAX_SEQ_LEN - 2)
            wordPieces.subList(0, MAX_SEQ_LEN - 2)
        else wordPieces

        val ids = mutableListOf<Long>()
        ids.add(CLS_ID.toLong())
        truncated.forEach { ids.add((vocab[it] ?: UNK_ID).toLong()) }
        ids.add(SEP_ID.toLong())

        val seqLen = ids.size
        repeat(MAX_SEQ_LEN - seqLen) { ids.add(PAD_ID.toLong()) }

        return Encoding(
            inputIds = ids.toLongArray(),
            attentionMask = LongArray(MAX_SEQ_LEN) { i -> if (i < seqLen) 1L else 0L },
            tokenTypeIds = LongArray(MAX_SEQ_LEN) { 0L },
            seqLen = seqLen
        )
    }

    private fun wordPieceTokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        for (word in basicTokenize(text)) {
            tokens.addAll(tokenizeWord(word))
        }
        return tokens
    }

    private fun basicTokenize(text: String): List<String> {
        val words = mutableListOf<String>()
        val cur = StringBuilder()
        for (ch in text) {
            when {
                ch.isWhitespace() -> {
                    if (cur.isNotEmpty()) { words.add(cur.toString()); cur.clear() }
                }
                isPunctuation(ch) -> {
                    if (cur.isNotEmpty()) { words.add(cur.toString()); cur.clear() }
                    words.add(ch.toString())
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) words.add(cur.toString())
        return words
    }

    private fun tokenizeWord(word: String): List<String> {
        if (word.length > 200) return listOf("[UNK]")
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: String? = null
            while (start < end) {
                val sub = if (start == 0) word.substring(start, end) else "##${word.substring(start, end)}"
                if (vocab.containsKey(sub)) { found = sub; break }
                end--
            }
            if (found == null) return listOf("[UNK]")
            pieces.add(found)
            start = end
        }
        return pieces
    }

    private fun isPunctuation(ch: Char): Boolean {
        val cp = ch.code
        return cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126
    }
}
