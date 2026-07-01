package com.gaee.tools

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.gaee.model.ToolResult
import com.gaee.service.GaeeAccessibilityService

/**
 * Gives the LLM "eyes": reads all visible text on the current screen and returns it,
 * both as TTS-speakable text and as structured data ({screenText}, {appName}) that
 * later plan steps and the verification loop can consume.
 *
 * Handles:
 *  - "What does this say?"  → action: "read"
 *  - Internal verification  → ExecutionEngine reads the screen after each UINavigator step
 */
class ScreenReaderTool(private val context: Context) : BaseTool {
    override val name = "ScreenReaderTool"

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val service = GaeeAccessibilityService.instance
            ?: return ToolResult(
                false,
                "To read the screen, please enable the Hello accessibility service in Settings.",
                "service_not_enabled"
            ).also { openAccessibilitySettings() }

        return service.readScreen()
    }

    private fun openAccessibilitySettings() {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }
}
