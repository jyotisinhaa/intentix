package com.gaee.tools

import android.content.Context
import com.gaee.engine.ReminderScheduler
import com.gaee.model.ToolResult
import java.util.Calendar

/**
 * Sets gentle, message-forward reminders (notification + spoken message), one-shot or recurring.
 * Unlike AlarmTool this does NOT create a loud Clock alarm — see ReminderScheduler.
 *
 * args: { time: "HH:MM" (first fire), message: "what to do", repeatMinutes: "0|N", action: "cancel" }
 */
class ReminderTool(private val context: Context) : BaseTool {
    override val name = "ReminderTool"

    override suspend fun execute(args: Map<String, String>): ToolResult {
        if (args["action"] == "cancel") {
            ReminderScheduler(context).cancelAll()
            return ToolResult(true, "Okay, I have turned off your reminders.")
        }

        val timeStr = args["time"] ?: return ToolResult(false, "I need a time for the reminder.")
        val message = args["message"]?.trim().ifNullOrBlank("your reminder")
        val repeatMinutes = args["repeatMinutes"]?.toIntOrNull() ?: 0

        return try {
            val firstFire = nextFireMillis(timeStr)
            ReminderScheduler(context).schedule(message, firstFire, repeatMinutes)
            ToolResult(true, "Okay, I will remind you to $message ${whenPhrase(timeStr, repeatMinutes)}.")
        } catch (e: Exception) {
            ToolResult(false, "I could not set the reminder. Please try again.", e.message)
        }
    }

    /** Next occurrence of HH:MM today, or tomorrow if that time already passed. */
    private fun nextFireMillis(timeStr: String): Long {
        val parts = timeStr.split(":")
        val hour = parts[0].trim().toInt()
        val minute = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    private fun whenPhrase(timeStr: String, repeatMinutes: Int): String = when {
        repeatMinutes <= 0 -> "at ${displayTime(timeStr)}"
        repeatMinutes == 1440 -> "every day at ${displayTime(timeStr)}"
        repeatMinutes % 60 == 0 -> {
            val h = repeatMinutes / 60
            "every $h ${if (h == 1) "hour" else "hours"}"
        }
        else -> "every $repeatMinutes ${if (repeatMinutes == 1) "minute" else "minutes"}"
    }

    private fun displayTime(timeStr: String): String {
        val parts = timeStr.split(":")
        val hour = parts[0].trim().toIntOrNull() ?: return timeStr
        val minute = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        val amPm = if (hour < 12) "AM" else "PM"
        val h12 = when { hour == 0 -> 12; hour > 12 -> hour - 12; else -> hour }
        return "$h12:${minute.toString().padStart(2, '0')} $amPm"
    }

    private fun String?.ifNullOrBlank(default: String) =
        if (this.isNullOrBlank()) default else this
}
