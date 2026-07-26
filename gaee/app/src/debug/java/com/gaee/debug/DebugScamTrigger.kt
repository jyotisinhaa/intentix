package com.gaee.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gaee.engine.ProactiveNotifier
import com.gaee.engine.ScamDetector

/**
 * DEBUG-ONLY test hook. Lives in `src/debug`, so it is compiled into debug builds only and never
 * ships in a release build.
 *
 * It lets us exercise the real F1 detection + F2 delivery (heads-up banner + spoken warning +
 * in-app history card) on a phone that has no SIM and no live incoming message, by firing an adb
 * broadcast:
 *
 *   adb shell am broadcast -a com.gaee.DEBUG_SCAM \
 *     --es text "You won a lottery! Click http://bit.ly/x to claim your prize"
 *
 * It runs the SAME [ScamDetector] + [ProactiveNotifier] the notification listener uses — only the OS
 * notification-capture step is bypassed. A DANGEROUS verdict warns (exactly as the on-device path
 * does); SAFE/SUSPICIOUS stay silent, so a genuine message produces no false alarm.
 */
class DebugScamTrigger : BroadcastReceiver() {

    companion object {
        // Retained so its TtsTool survives across broadcasts and can warm up (matches how the real
        // GaeeNotificationService keeps one long-lived notifier).
        private var notifier: ProactiveNotifier? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text")
            ?: "You won a lottery! Click http://bit.ly/x to claim your prize"
        val app = intent.getStringExtra("app") ?: "Test message"

        val result = ScamDetector.assess(sender = "unknown", text = text)
        Log.i("DebugScamTrigger", "verdict=${result.verdict} reasons=${result.reasons} text=\"$text\"")

        if (result.verdict == ScamDetector.Verdict.DANGEROUS) {
            val n = notifier
                ?: ProactiveNotifier(context.applicationContext).also { notifier = it }
            n.alertScam(
                appLabel = app,
                spokenMessage = ScamDetector.spokenWarning(result),
                dedupeKey = text
            )
        }
    }
}
