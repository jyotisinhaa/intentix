package com.gaee.tools

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.gaee.model.ToolResult

class WifiTool(private val context: Context) : BaseTool {
    override val name = "WifiTool"

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val state = args["state"] ?: return ToolResult(false, "Please say WiFi on or WiFi off.")

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ restricts programmatic WiFi toggle; open settings panel instead
                val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                val stateWord = if (state == "on") "turn it on" else "turn it off"
                ToolResult(true, "I opened WiFi settings. Please $stateWord.")
            } else {
                @Suppress("DEPRECATION")
                val wifiManager = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = (state == "on")
                val stateWord = if (state == "on") "on" else "off"
                ToolResult(true, "WiFi turned $stateWord.")
            }
        } catch (e: Exception) {
            ToolResult(false, "I could not change the WiFi. Please try again.", e.message)
        }
    }
}
