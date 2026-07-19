package com.gaee.service

import android.content.Context
import android.provider.ContactsContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.gaee.engine.ProactiveNotifier
import com.gaee.engine.ScamCloudCheck
import com.gaee.engine.ScamDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

data class CapturedNotification(
    val app: String,         // e.g. "WhatsApp", "Messages"
    val packageName: String, // e.g. "com.whatsapp"
    val title: String,
    val text: String,
    val key: String,         // used to dismiss this specific notification
    val timeMs: Long
)

class GaeeNotificationService : NotificationListenerService() {

    companion object {
        @Volatile var instance: GaeeNotificationService? = null
            private set

        private const val MAX_STORED = 50
        val notifications: CopyOnWriteArrayList<CapturedNotification> = CopyOnWriteArrayList()

        // Opt-in gate for the Tier-2 cloud scam check (2A). Default OFF — until a user / caregiver
        // turns "smart scam protection" on, screening stays fully on-device and nothing is sent out.
        private const val PREFS = "gaee"
        private const val KEY_CLOUD_ENABLED = "scam_cloud_enabled"

        fun isCloudScreeningEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_CLOUD_ENABLED, false)

        /** Called by the setup UI's "smart scam protection" toggle. */
        fun setCloudScreeningEnabled(context: Context, enabled: Boolean) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_CLOUD_ENABLED, enabled).apply()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        // Load notifications already in the tray when service first connects
        try {
            activeNotifications?.forEach { sbn -> store(sbn) }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        proactiveNotifier?.shutdown()
        proactiveNotifier = null
        scope.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val captured = store(sbn) ?: return
        screenForScam(captured)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        notifications.removeIf { it.key == sbn.key }
    }

    fun dismissAll() {
        try { cancelAllNotifications() } catch (_: Exception) {}
        notifications.clear()
    }

    fun dismissByApp(packageName: String) {
        val toRemove = notifications.filter { it.packageName == packageName }
        toRemove.forEach {
            try { cancelNotification(it.key) } catch (_: Exception) {}
        }
        notifications.removeIf { it.packageName == packageName }
    }

    private fun store(sbn: StatusBarNotification): CapturedNotification? {
        // Skip system noise and this app's own notifications
        if (sbn.packageName == "com.gaee" || sbn.isOngoing) return null

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: return null
        val text  = extras.getCharSequence("android.text")?.toString()  ?: ""
        if (text.isBlank() && title.isBlank()) return null

        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (_: Exception) { sbn.packageName }

        val captured = CapturedNotification(
            app = appName,
            packageName = sbn.packageName,
            title = title,
            text = text,
            key = sbn.key,
            timeMs = sbn.postTime
        )

        // Replace existing notification with same key
        notifications.removeIf { it.key == sbn.key }
        notifications.add(captured)

        // Keep only the most recent MAX_STORED
        while (notifications.size > MAX_STORED) {
            notifications.removeAt(0)
        }
        return captured
    }

    // --- Phase 3 F1: incoming-message scam screening (additive) -----------------------------

    // Packages whose notifications carry person-to-person messages worth screening. Screening
    // everything (games, news, system) would waste work and risk false alarms.
    private val messagingPackages = setOf(
        "com.whatsapp", "com.whatsapp.w4b",
        "com.google.android.apps.messaging",           // Google Messages (SMS)
        "com.samsung.android.messaging",               // Samsung Messages
        "org.telegram.messenger",
        "com.facebook.orca", "com.facebook.mlite",     // Messenger
        "com.google.android.gm"                        // Gmail
    )

    private var proactiveNotifier: ProactiveNotifier? = null
    private var cloudCheck: ScamCloudCheck? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // De-dupe: don't warn twice for the same sender+message within this window.
    private val recentWarnings = HashMap<String, Long>()
    private val dedupeWindowMs = 5 * 60 * 1000L

    /**
     * Screens a freshly-arrived message for scams. Storage behavior above is untouched — this only
     * *adds* a screening pass. Two tiers:
     *  - Tier 1 ([ScamDetector], on-device): DANGEROUS → warn immediately; SAFE → ignore.
     *  - Tier 2 ([ScamCloudCheck], Claude): only for the SUSPICIOUS middle, and only when the user
     *    has opted in — otherwise the message never leaves the phone.
     * Anything that throws is swallowed so screening can never crash the listener.
     */
    private fun screenForScam(captured: CapturedNotification) {
        try {
            if (captured.packageName !in messagingPackages) return

            val body = listOf(captured.title, captured.text)
                .filter { it.isNotBlank() }
                .joinToString(". ")

            // Trusted saved contact → don't screen at all (biggest false-alarm source).
            val knownContact = isKnownContact(captured.title)

            val result = ScamDetector.assess(
                sender = captured.title,
                text = body,
                senderIsKnownContact = knownContact
            )

            when (result.verdict) {
                ScamDetector.Verdict.DANGEROUS ->
                    warnOnce(captured, ScamDetector.spokenWarning(result))

                ScamDetector.Verdict.SUSPICIOUS ->
                    if (isCloudScreeningEnabled(applicationContext)) escalateToCloud(captured, body)
                    // else: opt-in off → stay on-device only, say nothing (no false alarm).

                ScamDetector.Verdict.SAFE -> { /* nothing */ }
            }
        } catch (_: Exception) {
            // Screening must never break notification capture.
        }
    }

    /** Tier 2: ask Claude to adjudicate the uncertain message; warn only on a confident scam. */
    private fun escalateToCloud(captured: CapturedNotification, rawBody: String) {
        val checker = cloudCheck
            ?: ScamCloudCheck(com.gaee.BuildConfig.CLAUDE_API_KEY).also { cloudCheck = it }
        scope.launch {
            val cloud = checker.adjudicate(rawBody) ?: return@launch
            if (cloud.verdict == ScamDetector.Verdict.DANGEROUS) {
                warnOnce(captured, ScamDetector.spokenWarning(cloud))
            }
        }
    }

    /**
     * Raises a scam warning unless the same message was already warned about very recently. Delivery
     * (spoken warning + heads-up banner + history entry) is handled by [ProactiveNotifier]; this
     * method owns only the 5-minute de-dupe gate, shared by Tier 1 and Tier 2.
     */
    private fun warnOnce(captured: CapturedNotification, message: String) {
        if (message.isBlank()) return
        val key = "${captured.packageName}|${captured.title}|${captured.text.hashCode()}"
        val now = System.currentTimeMillis()
        synchronized(recentWarnings) {
            recentWarnings[key]?.let { if (now - it < dedupeWindowMs) return }
            recentWarnings[key] = now
            recentWarnings.entries.removeAll { now - it.value > dedupeWindowMs }
        }
        val notifier = proactiveNotifier
            ?: ProactiveNotifier(applicationContext).also { proactiveNotifier = it }
        notifier.alertScam(appLabel = captured.app, spokenMessage = message, dedupeKey = key)
    }

    /** Best-effort: is this sender name a saved contact? Returns false if contacts are unreadable. */
    private fun isKnownContact(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return try {
            contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                "${ContactsContract.Contacts.DISPLAY_NAME} = ?",
                arrayOf(name),
                null
            )?.use { it.count > 0 } ?: false
        } catch (_: Exception) {
            false
        }
    }

}
