package com.gaee.tools

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.gaee.engine.GaeeBackgroundWorker
import com.gaee.model.ToolResult
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Queues a heavy / deferred task to run in the background via WorkManager.
 * If a time is given, the job is delayed until then; otherwise it runs when the phone
 * is idle and charging (e.g. overnight). Result is spoken on the next app open.
 *
 * args: { task: "plain-English task", time: "HH:MM" (optional) }
 */
class SchedulerTool(private val context: Context) : BaseTool {
    override val name = "SchedulerTool"

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val task = args["task"]?.trim().orEmpty()
        if (task.isBlank()) return ToolResult(false, "I did not catch what to schedule.")

        val time = args["time"]?.trim().orEmpty()
        val delayMs = computeDelayMs(time)

        val constraints = Constraints.Builder()
            .apply { if (time.isBlank()) setRequiresCharging(true) }
            .build()

        val request = OneTimeWorkRequestBuilder<GaeeBackgroundWorker>()
            .setInputData(Data.Builder().putString(GaeeBackgroundWorker.KEY_TASK, task).build())
            .setConstraints(constraints)
            .apply { if (delayMs > 0) setInitialDelay(delayMs, TimeUnit.MILLISECONDS) }
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "gaee_task_${task.hashCode()}",
            ExistingWorkPolicy.REPLACE,
            request
        )

        val whenStr = if (time.isNotBlank()) "at $time" else "tonight while charging"
        return ToolResult(true, "Okay, I will take care of that $whenStr and tell you when it is ready.")
    }

    // Milliseconds from now until the next occurrence of HH:MM; 0 if no/invalid time.
    private fun computeDelayMs(time: String): Long {
        if (time.isBlank()) return 0
        val parts = time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return 0
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }
}
