package com.gaee.engine

import android.content.Context
import com.gaee.model.CacheEntry
import com.gaee.model.ToolCall
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ActionCache(context: Context) {

    private val dao = ActionCacheDatabase.getInstance(context).dao()
    private val gson = Gson()

    companion object {
        const val HIT_THRESHOLD = 0.78f
    }

    suspend fun findBestMatch(
        intent: String,
        embedding: FloatArray,
        embeddingEngine: EmbeddingEngine
    ): Pair<CacheEntry, List<ToolCall>>? {
        val candidates = dao.getByIntent(intent)
        if (candidates.isEmpty()) return null

        var bestScore = HIT_THRESHOLD
        var bestEntry: CacheEntry? = null

        for (entry in candidates) {
            val cached = embeddingEngine.fromBytes(entry.embedding)
            val score = embeddingEngine.cosine(embedding, cached)
            if (score > bestScore) {
                bestScore = score
                bestEntry = entry
            }
        }

        val entry = bestEntry ?: return null
        dao.incrementHitCount(entry.id)
        return entry to parseSteps(entry.stepsJson)
    }

    suspend fun insert(
        intent: String,
        embedding: FloatArray,
        steps: List<ToolCall>,
        argSlots: List<String>,
        embeddingEngine: EmbeddingEngine
    ): Long {
        val entry = CacheEntry(
            intent = intent,
            embedding = embeddingEngine.toBytes(embedding),
            stepsJson = gson.toJson(steps),
            argsSlots = gson.toJson(argSlots)
        )
        return dao.insert(entry)
    }

    suspend fun recordOutcome(entryId: Long, success: Boolean) {
        dao.recordOutcome(entryId, if (success) 1f else 0f)
    }

    fun fillSlots(steps: List<ToolCall>, args: Map<String, String>): List<ToolCall> {
        return steps.map { step ->
            val filledArgs = step.args.mapValues { (_, v) ->
                var value = v
                args.forEach { (key, arg) -> value = value.replace("{$key}", arg) }
                value
            }
            step.copy(args = filledArgs)
        }
    }

    private fun parseSteps(json: String): List<ToolCall> {
        val type = object : TypeToken<List<ToolCall>>() {}.type
        return try {
            gson.fromJson(json, type)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
