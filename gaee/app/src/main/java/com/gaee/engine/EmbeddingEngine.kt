package com.gaee.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import kotlin.math.sqrt

class EmbeddingEngine(context: Context) {

    val modelFile = File(context.filesDir, "minilm_quantized.onnx")
    val vocabFile = File(context.filesDir, "vocab.txt")

    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var tokenizer: WordPieceTokenizer? = null

    val isReady: Boolean get() = session != null && tokenizer != null

    fun load() {
        if (!modelFile.exists() || !vocabFile.exists()) return
        try {
            tokenizer = WordPieceTokenizer(vocabFile)
            session = env.createSession(modelFile.absolutePath)
        } catch (_: Exception) {
            session = null
            tokenizer = null
        }
    }

    fun embed(text: String): FloatArray? {
        val tok = tokenizer ?: return null
        val sess = session ?: return null

        return try {
            val enc = tok.encode(text)
            val shape = longArrayOf(1L, WordPieceTokenizer.MAX_SEQ_LEN.toLong())

            val inputIds = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.inputIds), shape)
            val attMask = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.attentionMask), shape)
            val typeIds = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.tokenTypeIds), shape)

            val inputs = mapOf(
                "input_ids" to inputIds,
                "attention_mask" to attMask,
                "token_type_ids" to typeIds
            )

            val result = sess.run(inputs)

            // Try sentence_embedding output first (some exports), else mean-pool last_hidden_state
            val embedding = if (result.size() > 1) {
                val sentEmb = result[1].value
                if (sentEmb is Array<*> && sentEmb.isArrayOf<FloatArray>()) {
                    @Suppress("UNCHECKED_CAST")
                    (sentEmb as Array<FloatArray>)[0]
                } else {
                    meanPool(result, enc)
                }
            } else {
                meanPool(result, enc)
            }

            inputIds.close(); attMask.close(); typeIds.close(); result.close()

            l2Normalize(embedding)
        } catch (_: Exception) {
            null
        }
    }

    private fun meanPool(result: OrtSession.Result, enc: WordPieceTokenizer.Encoding): FloatArray {
        @Suppress("UNCHECKED_CAST")
        val hidden = result[0].value as Array<Array<FloatArray>>
        // hidden: [1, seqLen, 384]
        val hiddenSize = hidden[0][0].size
        val pooled = FloatArray(hiddenSize)
        var count = 0
        for (i in 0 until enc.seqLen) {
            if (enc.attentionMask[i] == 1L) {
                for (j in 0 until hiddenSize) pooled[j] += hidden[0][i][j]
                count++
            }
        }
        if (count > 0) {
            val countF = count.toFloat()
            for (j in pooled.indices) pooled[j] = pooled[j] / countF
        }
        return pooled
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        // Both vectors are L2-normalised so dot product = cosine similarity
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    fun toBytes(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { buf.putFloat(it) }
        return buf.array()
    }

    fun fromBytes(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buf.getFloat() }
    }

    fun shutdown() {
        session?.close()
        session = null
    }
}
