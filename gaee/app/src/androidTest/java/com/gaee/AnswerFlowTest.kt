package com.gaee

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gaee.engine.LlmPlanner
import com.gaee.model.IntentResult
import com.gaee.tools.AnswerTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for the conversational "ask anything" path.
 * Run with:  gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class AnswerFlowTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tag = "AnswerFlowTest"

    /** Planner routes ask_question to a single deterministic AnswerTool step (no junk TtsTool). */
    @Test
    fun planner_forAskQuestion_usesAnswerTool() = runBlocking {
        val planner = LlmPlanner(BuildConfig.CLAUDE_API_KEY)
        val (steps, _) = planner.generatePlan(
            IntentResult("ask_question", 1f, mapOf("question" to "how far is the moon"), false, "")
        )
        Log.i(tag, "plan = ${steps.map { it.toolName + it.args }}")
        assertTrue("plan should be a single AnswerTool step: ${steps.map { it.toolName }}",
            steps.size == 1 && steps.first().toolName == "AnswerTool")
    }

    /** General-knowledge question returns a real spoken answer. */
    @Test
    fun answerTool_generalKnowledge() = runBlocking {
        val result = AnswerTool(ctx).execute(mapOf("question" to "How far is the moon from Earth?"))
        Log.i(tag, "success=${result.success} answer=\"${result.speakAfter}\" err=${result.error}")
        assertTrue("AnswerTool failed: \"${result.speakAfter}\" / err=${result.error}", result.success)
        assertTrue("answer was blank", result.speakAfter.isNotBlank())
    }

    /** Live-fact question (web search) returns a plausible answer mentioning the year. */
    @Test
    fun answerTool_liveFact() = runBlocking {
        val result = AnswerTool(ctx).execute(mapOf("question" to "What year is it right now?"))
        Log.i(tag, "success=${result.success} answer=\"${result.speakAfter}\"")
        assertTrue("live-fact answer failed: \"${result.speakAfter}\"",
            result.success && result.speakAfter.contains("202"))
    }
}
