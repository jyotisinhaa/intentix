package com.gaee

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gaee.engine.IntentClassifier
import com.gaee.engine.ReminderScheduler
import com.gaee.engine.ReminderStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deterministic tests for alarm/reminder recurrence parsing and scheduling.
 * Uses the offline keyword classifier (no network / no Claude). Run:  gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ReminderFlowTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tag = "ReminderFlowTest"

    @Test
    fun recurringReminder_parsesIntervalAndMessage() = runBlocking {
        val r = IntentClassifier(ctx).classify("set a reminder to drink water every 2 minutes")
        Log.i(tag, "intent=${r.intent} args=${r.args}")
        assertEquals("set_reminder", r.intent)
        assertEquals("2", r.args["repeatMinutes"])
        assertEquals("drink water", r.args["message"]?.trim())
    }

    @Test
    fun dailyReminder_parsesAsEveryDay() = runBlocking {
        val r = IntentClassifier(ctx).classify("remind me to take medicine every morning")
        Log.i(tag, "intent=${r.intent} args=${r.args}")
        assertEquals("set_reminder", r.intent)
        assertEquals("1440", r.args["repeatMinutes"])
        assertTrue(r.args["message"]?.contains("take medicine", ignoreCase = true) == true)
    }

    @Test
    fun recurringAlarm_parsesWeekdays() = runBlocking {
        val r = IntentClassifier(ctx).classify("set an alarm for 7 am every weekday")
        Log.i(tag, "intent=${r.intent} args=${r.args}")
        assertEquals("set_alarm", r.intent)
        assertEquals("MON,TUE,WED,THU,FRI", r.args["days"])
    }

    @Test
    fun scheduler_scheduleThenCancelAll_clearsStore() {
        val scheduler = ReminderScheduler(ctx)
        scheduler.cancelAll()
        scheduler.schedule("test reminder", System.currentTimeMillis() + 3_600_000L, 0)
        assertTrue("reminder was not stored", ReminderStore(ctx).all().isNotEmpty())
        scheduler.cancelAll()
        assertTrue("store should be empty after cancelAll", ReminderStore(ctx).all().isEmpty())
    }
}
