package com.gaee.engine

import com.gaee.model.IntentResult
import com.gaee.model.ToolCall

class ActionPlanner(
    private val cache: ActionCache,
    private val embeddingEngine: EmbeddingEngine,
    private val llmPlanner: LlmPlanner
) {

    // id of the last cache entry that was a hit, used to record outcome
    var lastCacheHitId: Long? = null

    suspend fun plan(intent: IntentResult, transcript: String): List<ToolCall> {
        lastCacheHitId = null

        if (intent.unknown) {
            // Never a dead-end: hand anything without a built-in action to the conversational brain,
            // which answers, explains, or guides instead of a flat "I didn't understand".
            return listOf(ToolCall("AnswerTool", mapOf("question" to transcript)))
        }

        // Attempt cache lookup when embedding engine is ready
        if (embeddingEngine.isReady) {
            val embedding = embeddingEngine.embed(transcript)
            if (embedding != null) {
                val hit = cache.findBestMatch(intent.intent, embedding, embeddingEngine)
                if (hit != null) {
                    val (entry, steps) = hit
                    lastCacheHitId = entry.id
                    return cache.fillSlots(steps, intent.args)
                }
                // Cache miss → generate via LLM, then store
                val (steps, slots) = llmPlanner.generatePlan(intent)
                if (steps.isNotEmpty()) {
                    cache.insert(intent.intent, embedding, steps, slots, embeddingEngine)
                }
                return cache.fillSlots(steps, intent.args)
            }
        }

        // Embedding engine not ready (model still downloading) → LLM plan, no caching
        val (steps, _) = llmPlanner.generatePlan(intent)
        return cache.fillSlots(steps, intent.args)
    }
}
