package com.gaee.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        store(sbn)
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

    private fun store(sbn: StatusBarNotification) {
        // Skip system noise and this app's own notifications
        if (sbn.packageName == "com.gaee" || sbn.isOngoing) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: return
        val text  = extras.getCharSequence("android.text")?.toString()  ?: ""
        if (text.isBlank() && title.isBlank()) return

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
    }
}
