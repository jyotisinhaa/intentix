package com.gaee.tools

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import com.gaee.model.ToolResult
import kotlinx.coroutines.delay

class MediaControllerTool(private val context: Context) : BaseTool {
    override val name = "MediaControllerTool"

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val action = args["action"] ?: return ToolResult(false, "No action specified.")
        val app = args["app"]?.lowercase() ?: ""

        return try {
            if (app.isNotEmpty()) launchMediaApp(app)

            when (action) {
                "play" -> {
                    sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                    ToolResult(true, "Playing.")
                }
                "pause" -> {
                    sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                    ToolResult(true, "Paused.")
                }
                "next" -> {
                    sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                    ToolResult(true, "Next track.")
                }
                "previous" -> {
                    sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    ToolResult(true, "Previous track.")
                }
                "stop" -> {
                    sendMediaKey(KeyEvent.KEYCODE_MEDIA_STOP)
                    ToolResult(true, "Stopped.")
                }
                else -> ToolResult(false, "Unknown media action: $action")
            }
        } catch (e: Exception) {
            ToolResult(false, "I could not control the media player. Please try again.", e.message)
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(down)
        audioManager.dispatchMediaKeyEvent(up)
    }

    private suspend fun launchMediaApp(app: String) {
        val pm = context.packageManager
        val q = app.lowercase().trim()
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(launcherIntent, 0)
            .firstOrNull { ri ->
                val label = ri.loadLabel(pm).toString().lowercase()
                label.contains(q) || label.replace(" ", "").contains(q.replace(" ", ""))
            } ?: return
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        } ?: return
        context.startActivity(intent)
        delay(1500)
    }
}
