package com.gaee.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gaee.engine.ReminderScheduler

/** Re-arms all saved reminders after a reboot (AlarmManager alarms don't survive restart). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                ReminderScheduler(context).rearmAll()
            } catch (_: Exception) {
            }
        }
    }
}
