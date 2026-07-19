package com.gaee.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.gaee.R
import com.gaee.engine.ReminderScheduler
import java.util.Locale

/**
 * Fires when a reminder is due. Posts a gentle, message-forward heads-up notification (with a Stop
 * action to end recurrence) and speaks the message aloud. Reschedules the next occurrence for
 * recurring reminders.
 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "gaee_reminders"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ReminderScheduler.ACTION_STOP) {
            val id = intent.getIntExtra(ReminderScheduler.EXTRA_ID, -1)
            if (id >= 0) {
                ReminderScheduler(context).cancel(id)
                notificationManager(context).cancel(id)
            }
            return
        }

        val id = intent.getIntExtra(ReminderScheduler.EXTRA_ID, -1)
        val message = intent.getStringExtra(ReminderScheduler.EXTRA_MESSAGE) ?: "your reminder"
        val repeatMinutes = intent.getIntExtra(ReminderScheduler.EXTRA_REPEAT, 0)

        postNotification(context, id, message, repeatMinutes)

        // Queue the next occurrence for recurring reminders.
        if (repeatMinutes > 0) ReminderScheduler(context).scheduleNext(id, message, repeatMinutes)

        // Speak the message aloud (best-effort; the notification is the reliable channel).
        speak(context, "Reminder. $message")
    }

    private fun postNotification(context: Context, id: Int, message: String, repeatMinutes: Int) {
        val nm = notificationManager(context)
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Spoken reminders from Hello" }
            nm.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Reminder")
            .setContentText(message.replaceFirstChar { it.uppercase() })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)

        // A Stop action so a recurring reminder can be turned off from the notification.
        if (repeatMinutes > 0 && id >= 0) {
            val stopIntent = Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderScheduler.ACTION_STOP
                putExtra(ReminderScheduler.EXTRA_ID, id)
            }
            val stopPi = PendingIntent.getBroadcast(
                context, id + 1_000_000, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Stop reminding me", stopPi)
        }

        nm.notify(if (id >= 0) id else message.hashCode(), builder.build())
    }

    private fun notificationManager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Best-effort TTS from a receiver, kept alive via goAsync() with a hard timeout. */
    private fun speak(context: Context, text: String) {
        val pending = goAsync()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        var tts: TextToSpeech? = null

        fun done() {
            if (finished.compareAndSet(false, true)) {
                try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
                pending.finish()
            }
        }

        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(0.85f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) = done()
                    override fun onError(id: String?) = done()
                })
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gaee_reminder")
            } else {
                done()
            }
        }
        // Safety net: never hold the receiver open longer than ~9s.
        Handler(Looper.getMainLooper()).postDelayed({ done() }, 9000)
    }
}
