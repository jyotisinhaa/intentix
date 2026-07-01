package com.gaee.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.gaee.model.ToolResult

class ReminderTool(private val context: Context) : BaseTool {
    override val name = "ReminderTool"

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val timeStr = args["time"] ?: return ToolResult(false, "I need a time for the reminder.")
        val message = args["message"] ?: "Reminder"

        return try {
            val parts = timeStr.split(":")
            val hour = parts[0].trim().toInt()
            val minute = if (parts.size > 1) parts[1].trim().toInt() else 0

            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)

            ToolResult(true, "Reminder set for $timeStr. I will remind you to $message.")
        } catch (e: Exception) {
            ToolResult(false, "I could not set the reminder. Please try again.", e.message)
        }
    }
}
