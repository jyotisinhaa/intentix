package com.gaee.tools

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.gaee.model.ToolResult
import com.gaee.service.GaeeAccessibilityService

/**
 * Performs the device-level controls Android actually permits a third-party app to do — via the
 * accessibility service's global actions. Notably, apps CANNOT force-close other apps, so
 * "close background apps" opens the Recent-apps screen for the user to swipe them away.
 *
 * args: { action: "recents|home|quick_settings|notifications|settings|lock|screenshot" }
 */
class DeviceControlTool(private val context: Context) : BaseTool {
    override val name = "DeviceControlTool"

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val action = args["action"]?.lowercase()?.trim()
            ?: return ToolResult(false, "What would you like me to do?")

        // Settings opens via a normal intent — no accessibility service needed.
        if (action == "settings") {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            return ToolResult(true, "Opening Settings.")
        }

        val service = GaeeAccessibilityService.instance
            ?: return ToolResult(
                false,
                "To control the phone by voice, please enable the Hello accessibility service in Settings.",
                "service_not_enabled"
            ).also { openAccessibilitySettings() }

        return when (action) {
            "recents" -> perform(service, AccessibilityService.GLOBAL_ACTION_RECENTS,
                "I have opened your recent apps. Swipe each one up to close it.")
            "home" -> perform(service, AccessibilityService.GLOBAL_ACTION_HOME,
                "Going to the home screen.")
            "quick_settings" -> perform(service, AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
                "Here are your quick settings.")
            "notifications" -> perform(service, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
                "Here are your notifications.")
            "lock" -> {
                if (Build.VERSION.SDK_INT >= 28) {
                    perform(service, AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Locking your phone.")
                } else {
                    ToolResult(false, "I can't lock the phone on this version of Android.")
                }
            }
            "screenshot" -> {
                if (Build.VERSION.SDK_INT >= 30) {
                    perform(service, AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Taking a screenshot.")
                } else {
                    ToolResult(false, "I can't take a screenshot on this version of Android.")
                }
            }
            else -> ToolResult(false, "I'm not sure how to do that on the phone.")
        }
    }

    private fun perform(service: GaeeAccessibilityService, action: Int, speak: String): ToolResult {
        val ok = service.performGlobalAction(action)
        return if (ok) ToolResult(true, speak)
        else ToolResult(false, "I couldn't do that just now. Please try again.")
    }

    private fun openAccessibilitySettings() {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }
}
