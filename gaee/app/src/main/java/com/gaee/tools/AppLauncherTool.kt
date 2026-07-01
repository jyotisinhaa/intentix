package com.gaee.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import com.gaee.model.ToolResult

class AppLauncherTool(private val context: Context) : BaseTool {
    override val name = "AppLauncherTool"

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val appName = args["app_name"] ?: return ToolResult(false, "I need an app name to open.")
        val cleanName = appName.lowercase()
            .removePrefix("open ").removePrefix("launch ")
            .removePrefix("start ").removePrefix("run ").trim()

        return try {
            val intent = findApp(cleanName)
                ?: return ToolResult(false, "I could not find ${cleanName.replaceFirstChar { it.uppercase() }} on your phone.")
            context.startActivity(intent)
            ToolResult(true, "Opening ${cleanName.replaceFirstChar { it.uppercase() }}.")
        } catch (e: Exception) {
            ToolResult(false, "I could not open $cleanName. Please try again.", e.message)
        }
    }

    private fun findApp(query: String): Intent? {
        val pm = context.packageManager
        val q = query.lowercase().trim()
        val qNoSpaces = q.replace(" ", "").replace("-", "")

        // Get every app that has a launcher icon — system and user both
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val allLaunchable: List<ResolveInfo> = pm.queryIntentActivities(launcherIntent, 0)

        data class Candidate(val label: String, val packageName: String, val score: Int)

        val candidates = allLaunchable.mapNotNull { ri ->
            val label = ri.loadLabel(pm).toString().lowercase().trim()
            val labelNoSpaces = label.replace(" ", "").replace("-", "")
            val pkg = ri.activityInfo.packageName

            val score = when {
                label == q                             -> 100  // exact
                labelNoSpaces == qNoSpaces             -> 95   // exact ignoring spaces
                label.contains(q)                      -> 80   // label contains full query
                labelNoSpaces.contains(qNoSpaces)      -> 75   // label contains query (no spaces)
                q.contains(label) && label.length > 3  -> 60   // query contains label word (min 4 chars to avoid "app", "ai" etc.)
                queryWordsMatchLabel(q, label)         -> 50   // individual words match
                else                                   -> 0
            }

            if (score > 0) Candidate(label, pkg, score) else null
        }

        val best = candidates.maxByOrNull { it.score } ?: return null
        if (best.score < 50) return null

        return pm.getLaunchIntentForPackage(best.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    // "play store" matches "Google Play Store" because all query words appear in label
    private fun queryWordsMatchLabel(query: String, label: String): Boolean {
        val words = query.split(" ").filter { it.length > 2 }
        return words.isNotEmpty() && words.all { label.contains(it) }
    }
}
