package com.gaee.tools

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.gaee.model.ToolResult
import com.gaee.service.GaeeAccessibilityService

class UINavigator(private val context: Context) : BaseTool {
    override val name = "UINavigator"

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val service = GaeeAccessibilityService.instance
            ?: return ToolResult(
                false,
                "To control apps by voice, please enable the Hello accessibility service in Settings.",
                "service_not_enabled"
            ).also { openAccessibilitySettings() }

        val action = args["action"] ?: return ToolResult(false, "No action specified.")
        val target = args["target"] ?: ""
        val text = args["text"] ?: ""

        return when (action) {
            "tap" -> service.tapElement(target)
            "type" -> service.typeText(text)
            "scroll" -> service.scroll(target)
            "swipe" -> service.swipe(target)
            "back" -> service.pressBack()
            "find" -> service.findElement(target)
            "read" -> service.readScreen()
            "wait" -> {
                val timeout = args["timeout"]?.toLongOrNull() ?: 5000L
                service.waitForScreen(target, timeout)
            }
            "tap_at" -> {
                val x = args["x"]?.toFloatOrNull()
                val y = args["y"]?.toFloatOrNull()
                if (x == null || y == null) ToolResult(false, "tap_at needs x and y coordinates.")
                else service.tapAt(x, y)
            }
            else -> ToolResult(false, "Unknown action: $action")
        }
    }

    private fun openAccessibilitySettings() {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }
}
