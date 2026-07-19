package com.gaee.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.gaee.receiver.ReminderReceiver

/**
 * Schedules gentle, message-forward reminders using exact alarms that fire a BroadcastReceiver.
 * Recurring reminders self-reschedule on each fire (supports any interval, including "every 2 min"
 * and daily). Persisted via ReminderStore so they can be re-armed after a reboot.
 */
class ReminderScheduler(private val context: Context) {

    private val store = ReminderStore(context)
    private val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        const val ACTION_FIRE = "com.gaee.action.REMINDER_FIRE"
        const val ACTION_STOP = "com.gaee.action.REMINDER_STOP"
        const val EXTRA_ID = "id"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_REPEAT = "repeatMinutes"
    }

    fun schedule(message: String, firstFireMillis: Long, repeatMinutes: Int): Reminder {
        val r = Reminder(store.nextId(), message, firstFireMillis, repeatMinutes)
        store.add(r)
        arm(r)
        return r
    }

    /** Called from the receiver on each fire of a recurring reminder to queue the next one. */
    fun scheduleNext(id: Int, message: String, repeatMinutes: Int) {
        if (repeatMinutes <= 0) { store.remove(id); return }
        val next = System.currentTimeMillis() + repeatMinutes * 60_000L
        val r = (store.get(id) ?: Reminder(id, message, next, repeatMinutes)).copy(nextFireMillis = next)
        store.update(r)
        arm(r)
    }

    fun cancel(id: Int) {
        pendingIntent(id, create = false)?.let { am.cancel(it); it.cancel() }
        store.remove(id)
    }

    fun cancelAll() {
        store.all().forEach { pendingIntent(it.id, create = false)?.let { pi -> am.cancel(pi); pi.cancel() } }
        store.clear()
    }

    fun rearmAll() = store.all().forEach { arm(it) }

    fun hasReminders(): Boolean = store.all().isNotEmpty()

    private fun arm(r: Reminder) {
        val pi = pendingIntent(r.id, create = true, message = r.message, repeatMinutes = r.repeatMinutes)!!
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.nextFireMillis, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.nextFireMillis, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.nextFireMillis, pi)
        }
    }

    private fun pendingIntent(
        id: Int,
        create: Boolean,
        message: String = "",
        repeatMinutes: Int = 0
    ): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_REPEAT, repeatMinutes)
        }
        val flags = (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
            PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, id, intent, flags)
    }
}
