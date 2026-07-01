package com.gaee.tools

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.gaee.model.ToolResult
import com.gaee.service.GaeeNotificationService

class NotificationReaderTool(private val context: Context) : BaseTool {
    override val name = "NotificationReaderTool"

    // App name fragments → package name prefix for filtering
    private val appAliases = mapOf(
        "whatsapp"  to "com.whatsapp",
        "messages"  to "com.google.android.apps.messaging",
        "sms"       to "com.google.android.apps.messaging",
        "gmail"     to "com.google.android.gm",
        "email"     to "com.google.android.gm",
        "instagram" to "com.instagram.android",
        "facebook"  to "com.facebook.katana",
        "telegram"  to "org.telegram.messenger"
    )

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val action = args["action"]?.lowercase() ?: "read"
        val appFilter = args["app"]?.lowercase()

        // Guide user to enable notification access if service isn't running
        if (GaeeNotificationService.instance == null) {
            openNotificationSettings()
            return ToolResult(
                success = false,
                speakAfter = "I need permission to read your notifications. " +
                    "I have opened Settings. Please find Hello in the list and turn it on. " +
                    "Then come back and ask me again."
            )
        }

        return when (action) {
            "dismiss", "clear" -> handleDismiss(appFilter)
            else               -> handleRead(appFilter)
        }
    }

    private fun handleRead(appFilter: String?): ToolResult {
        val all = GaeeNotificationService.notifications.toList()
            .sortedByDescending { it.timeMs }

        val filtered = if (appFilter != null) {
            val pkg = appAliases.entries
                .firstOrNull { appFilter.contains(it.key) || it.key.contains(appFilter) }
                ?.value
            if (pkg != null) all.filter { it.packageName.startsWith(pkg) }
            else all.filter { it.app.lowercase().contains(appFilter) }
        } else {
            all
        }

        if (filtered.isEmpty()) {
            val label = if (appFilter != null) "from $appFilter" else ""
            return ToolResult(
                success = true,
                speakAfter = "You have no new notifications $label.".trim()
            )
        }

        // Group by app and summarise — elderly users need short, clear summaries
        val summary = buildSummary(filtered, appFilter)
        return ToolResult(success = true, speakAfter = summary)
    }

    private fun handleDismiss(appFilter: String?): ToolResult {
        val service = GaeeNotificationService.instance!!
        return if (appFilter != null) {
            val pkg = appAliases.entries
                .firstOrNull { appFilter.contains(it.key) || it.key.contains(appFilter) }
                ?.value
            if (pkg != null) service.dismissByApp(pkg)
            else {
                val matchedPkg = GaeeNotificationService.notifications
                    .firstOrNull { it.app.lowercase().contains(appFilter) }?.packageName
                if (matchedPkg != null) service.dismissByApp(matchedPkg)
            }
            ToolResult(success = true, speakAfter = "Done. I cleared your $appFilter notifications.")
        } else {
            service.dismissAll()
            ToolResult(success = true, speakAfter = "Done. All notifications cleared.")
        }
    }

    private fun buildSummary(notifications: List<com.gaee.service.CapturedNotification>, appFilter: String?): String {
        // If filtered to one app, read up to 3 messages in detail
        if (appFilter != null || notifications.map { it.app }.distinct().size == 1) {
            val app = notifications.first().app
            val count = notifications.size
            val preview = notifications.take(3).joinToString(". ") { n ->
                if (n.title.isNotBlank() && n.title != app) "${n.title}: ${n.text}"
                else n.text
            }
            val more = if (count > 3) " And ${count - 3} more." else ""
            return "You have $count notification${if (count > 1) "s" else ""} from $app. $preview$more"
        }

        // Multiple apps — give a count per app overview
        val grouped = notifications.groupBy { it.app }
        return if (grouped.size == 1) {
            val (app, items) = grouped.entries.first()
            "You have ${items.size} notification${if (items.size > 1) "s" else ""} from $app."
        } else {
            val parts = grouped.entries
                .sortedByDescending { it.value.size }
                .take(4)
                .map { (app, items) -> "${items.size} from $app" }
            "You have notifications: ${parts.joinToString(", ")}."
        }
    }

    private fun openNotificationSettings() {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    }
}
