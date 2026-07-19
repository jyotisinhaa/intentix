package com.gaee

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gaee.engine.ActionCache
import com.gaee.engine.ActionPlanner
import com.gaee.engine.EmbeddingEngine
import com.gaee.engine.IntentClassifier
import com.gaee.engine.LlmPlanner
import com.gaee.model.IntentResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Device-control classification + "never a dead-end" unknown routing. */
@RunWith(AndroidJUnit4::class)
class DeviceAndFallbackTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tag = "DeviceAndFallbackTest"

    @Test
    fun closeBackgroundApps_mapsToRecents() = runBlocking {
        val r = IntentClassifier(ctx).classify("close all background apps")
        Log.i(tag, "intent=${r.intent} args=${r.args}")
        assertEquals("device_control", r.intent)
        assertEquals("recents", r.args["action"])
    }

    @Test
    fun goHome_mapsToHome() = runBlocking {
        val r = IntentClassifier(ctx).classify("go home")
        assertEquals("device_control", r.intent)
        assertEquals("home", r.args["action"])
    }

    @Test
    fun lockPhone_mapsToLock() = runBlocking {
        val r = IntentClassifier(ctx).classify("lock the phone")
        assertEquals("device_control", r.intent)
        assertEquals("lock", r.args["action"])
    }

    /** An unknown intent is routed to the conversational brain, not a canned apology. */
    @Test
    fun unknownIntent_routesToAnswerTool() = runBlocking {
        val planner = ActionPlanner(
            ActionCache(ctx), EmbeddingEngine(ctx), LlmPlanner(BuildConfig.CLAUDE_API_KEY)
        )
        val unknown = IntentResult("unknown", 0f, emptyMap(), false, "", unknown = true)
        val steps = planner.plan(unknown, "tell me a joke about cats")
        Log.i(tag, "unknown plan = ${steps.map { it.toolName + it.args }}")
        assertEquals(1, steps.size)
        assertEquals("AnswerTool", steps.first().toolName)
        assertEquals("tell me a joke about cats", steps.first().args["question"])
    }
}
