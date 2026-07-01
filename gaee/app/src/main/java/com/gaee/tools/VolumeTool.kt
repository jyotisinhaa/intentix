package com.gaee.tools

import android.content.Context
import android.media.AudioManager
import com.gaee.model.ToolResult

class VolumeTool(private val context: Context) : BaseTool {
    override val name = "VolumeTool"

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val direction = args["direction"]
        val levelStr = args["level"]

        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            when {
                levelStr != null -> {
                    val level = levelStr.toIntOrNull()
                        ?: return ToolResult(false, "I did not understand that volume level.")
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val targetVolume = (level / 100.0 * maxVolume).toInt().coerceIn(0, maxVolume)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
                    ToolResult(true, "Volume set to $level percent.")
                }
                direction == "up" -> {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE,
                        AudioManager.FLAG_SHOW_UI
                    )
                    ToolResult(true, "Volume turned up.")
                }
                direction == "down" -> {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI
                    )
                    ToolResult(true, "Volume turned down.")
                }
                else -> ToolResult(false, "Please say volume up or volume down.")
            }
        } catch (e: Exception) {
            ToolResult(false, "I could not change the volume. Please try again.", e.message)
        }
    }
}
