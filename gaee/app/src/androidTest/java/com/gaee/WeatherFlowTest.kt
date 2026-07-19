package com.gaee

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gaee.engine.ExecutionEngine
import com.gaee.engine.LlmPlanner
import com.gaee.model.IntentResult
import com.gaee.tools.WeatherTool
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for the weather path. Run with:  gradlew connectedDebugAndroidTest
 *
 * A mock location is injected because an instrumented test has no foreground activity, and
 * Android withholds real GPS from a background process. The real app gets a live fix while
 * MainActivity is in the foreground — this just exercises the fetch/parse/speak path deterministically.
 */
@RunWith(AndroidJUnit4::class)
class WeatherFlowTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tag = "WeatherFlowTest"
    private val mockProvider = LocationManager.GPS_PROVIDER
    private lateinit var lm: LocationManager
    private var mockLocationReady = false

    @Before
    fun setUpMockLocation() {
        lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // Best-effort mock location. Some devices/harnesses won't grant MOCK_LOCATION to an
        // instrumented package; when that happens the location-dependent tests skip (Assume)
        // rather than fail — real GPS works in the foreground app.
        mockLocationReady = runCatching {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.adoptShellPermissionIdentity()
            automation.executeShellCommand("appops set ${ctx.packageName} android:mock_location allow")
                .let { pfd -> java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() } }
            runCatching { lm.removeTestProvider(mockProvider) }
            @Suppress("DEPRECATION")
            lm.addTestProvider(mockProvider, false, false, false, false, true, true, true, 1, 1)
            lm.setTestProviderEnabled(mockProvider, true)
            lm.setTestProviderLocation(mockProvider, Location(mockProvider).apply {
                latitude = 28.6139   // New Delhi
                longitude = 77.2090
                accuracy = 10f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })
        }.isSuccess
    }

    @After
    fun tearDown() {
        if (mockLocationReady) runCatching { lm.removeTestProvider(mockProvider) }
    }

    /** Layer 1: WeatherTool fetches, parses, and returns a real weather line. */
    @Test
    fun weatherTool_returnsWeather() = runBlocking {
        org.junit.Assume.assumeTrue("mock location unavailable in this harness", mockLocationReady)
        val result = WeatherTool(ctx).execute(emptyMap())
        Log.i(tag, "WeatherTool success=${result.success} speak=\"${result.speakAfter}\" err=${result.error}")
        assertTrue(
            "WeatherTool failed: \"${result.speakAfter}\" / err=${result.error}",
            result.success && result.speakAfter.contains("degree", ignoreCase = true)
        )
    }

    /** Layer 2: the planner produces a plan that actually calls WeatherTool (no junk TtsTool). */
    @Test
    fun planner_forGetWeather_includesWeatherTool() = runBlocking {
        val planner = LlmPlanner(BuildConfig.CLAUDE_API_KEY)
        val (steps, _) = planner.generatePlan(
            IntentResult("get_weather", 1f, emptyMap(), false, "")
        )
        Log.i(tag, "plan = ${steps.map { it.toolName + it.args }}")
        assertTrue("plan had no WeatherTool: ${steps.map { it.toolName }}",
            steps.any { it.toolName == "WeatherTool" })
        assertTrue("plan should be WeatherTool-only (no trailing TtsTool to flush speech): " +
            "${steps.map { it.toolName }}", steps.none { it.toolName == "TtsTool" })
    }

    /** Layer 3: full plan → execute yields a spoken weather line. */
    @Test
    fun fullFlow_speaksWeather() = runBlocking {
        org.junit.Assume.assumeTrue("mock location unavailable in this harness", mockLocationReady)
        val planner = LlmPlanner(BuildConfig.CLAUDE_API_KEY)
        val engine = ExecutionEngine(ctx)
        val intent = IntentResult("get_weather", 1f, emptyMap(), false, "")
        val (steps, _) = planner.generatePlan(intent)
        val results = engine.execute(steps, intent, planner)
        results.forEachIndexed { i, r -> Log.i(tag, "result[$i] success=${r.success} speak=\"${r.speakAfter}\"") }
        assertTrue(
            "No successful weather result. steps=${steps.map { it.toolName }}, " +
                "results=${results.map { it.speakAfter }}",
            results.any { it.success && it.speakAfter.contains("degree", ignoreCase = true) }
        )
    }
}
