package com.gaee.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.gaee.model.ToolResult

class AlarmTool(private val context: Context) : BaseTool {
    override val name = "AlarmTool"

    override suspend fun execute(args: Map<String, String>): ToolResult {
        if (args["action"] == "cancel") return cancelAlarm(args)
        val timeStr = args["time"] ?: return ToolResult(false, "I need a time to set the alarm.")
        val label = args["label"] ?: "Alarm"

        return try {
            val parts = timeStr.split(":")
            val hour = parts[0].trim().toInt()
            val minute = if (parts.size > 1) parts[1].trim().toInt() else 0
            val isTomorrow = args["tomorrow"] == "true"

            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                // Skip UI for normal alarms; show UI for tomorrow so user can confirm the date
                putExtra(AlarmClock.EXTRA_SKIP_UI, !isTomorrow)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)

            val displayTime = formatTime(hour, minute)
            val speakText = if (isTomorrow)
                "I have opened the alarm for $displayTime. Please tap tomorrow and then save."
            else
                "Your alarm is set for $displayTime."
            ToolResult(true, speakText)
        } catch (e: Exception) {
            ToolResult(false, "I could not set the alarm. Please try again.", e.message)
        }
    }

    private fun cancelAlarm(args: Map<String, String>): ToolResult {
        return try {
            val mode = args["mode"] ?: "next"

            if (mode == "all") {
                // Android has no reliable bulk-delete API — open the Clock app so user can delete manually
                val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return ToolResult(true, "I have opened your alarms. Swipe left on each one to delete it.")
            }

            val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                when (mode) {
                    "time" -> {
                        val timeStr = args["time"] ?: ""
                        val parts = timeStr.split(":")
                        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 0
                        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
                        putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    }
                    else -> putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
                }
            }
            context.startActivity(intent)
            val speakText = if (mode == "time") "Your ${args["time"]} alarm has been cancelled."
                            else "Your next alarm has been cancelled."
            ToolResult(true, speakText)
        } catch (e: Exception) {
            ToolResult(false, "I could not cancel the alarm. Please try again.", e.message)
        }
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val amPm = if (hour < 12) "AM" else "PM"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        val minuteStr = minute.toString().padStart(2, '0')
        return "$displayHour:$minuteStr $amPm"
    }
}
