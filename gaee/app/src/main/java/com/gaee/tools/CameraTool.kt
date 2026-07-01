package com.gaee.tools

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import com.gaee.model.ToolResult

class CameraTool(private val context: Context) : BaseTool {
    override val name = "CameraTool"

    override suspend fun execute(args: Map<String, String>): ToolResult {
        return try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolResult(true, "Opening the camera.")
        } catch (e: Exception) {
            ToolResult(false, "I could not open the camera. Please try again.", e.message)
        }
    }
}
