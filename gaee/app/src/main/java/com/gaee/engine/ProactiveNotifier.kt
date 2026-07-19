package com.gaee.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.gaee.R
import com.gaee.tools.TtsTool

/**
 * Phase 3 Feature 2 — the assistant's "speak up first" channel. Turns a proactive alert (today: a
 * flagged scam message) into the surfaces that actually reach an elderly user:
 *  - a **heads-up notification banner** — the only surface that shows *over another app* (the user
 *    is in WhatsApp/SMS when a scam arrives, not in this app), and
 *  - a **spoken warning** — kept as a safety-net for users who can't read the banner or miss it.
 * Every alert is also recorded in [ProactiveAlertLog] so the in-app card can show a history.
 *
 * Reusable: [alert] takes an [AlertLevel] so later features (medicine reminders, etc.) can raise
 * alerts through the same component with the same never-nag priority rules.
 *
 * Owns a [TtsTool]; call [shutdown] when the hosting service is destroyed.
 */
class ProactiveNotifier(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "gaee_scam_alerts"
        private const val CHANNEL_NAME = "Scam alerts"
        // Namespaced away from reminder notification IDs (which use the reminder's own small int id).
        private const val NOTIF_ID_BASE = 2_000_000

        /** How an alert of a given level is delivered. Pure — the never-nag rules, unit-testable. */
        data class Delivery(val speak: Boolean, val notify: Boolean)

        fun deliveryFor(level: AlertLevel): Delivery = when (level) {
            AlertLevel.DANGEROUS -> Delivery(speak = true, notify = true)
            AlertLevel.IMPORTANT -> Delivery(speak = false, notify = true)
            AlertLevel.SILENT -> Delivery(speak = false, notify = false)
        }
    }

    private var tts: TtsTool? = null

    /** Highest-priority scam warning: speak + heads-up banner + history. */
    fun alertScam(appLabel: String, spokenMessage: String, dedupeKey: String) {
        alert(
            level = AlertLevel.DANGEROUS,
            appLabel = appLabel,
            title = "Possible scam message",
            body = spokenMessage,
            spoken = spokenMessage,
            dedupeKey = dedupeKey
        )
    }

    /**
     * Delivers an alert according to [level]:
     *  - DANGEROUS → speak + heads-up banner + log
     *  - IMPORTANT → heads-up banner + log (no voice)
     *  - SILENT    → log only (never nags)
     */
    fun alert(
        level: AlertLevel,
        appLabel: String,
        title: String,
        body: String,
        spoken: String,
        dedupeKey: String
    ) {
        ProactiveAlertLog.add(ProactiveAlert(appLabel, body, System.currentTimeMillis(), level))

        val delivery = deliveryFor(level)
        if (delivery.notify) postNotification(title, body, dedupeKey)
        if (delivery.speak && spoken.isNotBlank()) speak(spoken)
    }

    private fun postNotification(title: String, body: String, dedupeKey: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Warnings about scam or fraud messages" }
            nm.createNotificationChannel(channel)
        }

        // Tapping the banner opens this app (where the history card lives).
        val contentPi = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
            ?.let {
                PendingIntent.getActivity(
                    context, 0, it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
        if (contentPi != null) builder.setContentIntent(contentPi)

        try {
            nm.notify(NOTIF_ID_BASE + (dedupeKey.hashCode() and 0xFFFF), builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted (Android 13+): the spoken warning still covers it.
        }
    }

    private fun speak(message: String) {
        val speaker = tts ?: TtsTool(context.applicationContext).also { tts = it }
        speaker.speak(message)
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}
