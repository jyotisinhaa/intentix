package com.gaee.engine

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Runs heavy / deferred tasks in the background (queued by SchedulerTool, typically
 * overnight while charging). Calls Claude Sonnet with the task description, stores the
 * spoken result in SharedPreferences. MainViewModel speaks it on the next app open.
 */
class GaeeBackgroundWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_TASK = "task"
        const val PREFS = "gaee"
        const val PENDING_RESULT = "pending_background_result"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val task = inputData.getString(KEY_TASK) ?: return@withContext Result.success()
        val apiKey = IntentClassifier(applicationContext).claudeApiKey
        if (apiKey == "YOUR_CLAUDE_API_KEY") return@withContext Result.success()

        try {
            val prompt = "You are a helpful assistant for an elderly user. " +
                "Complete this task and give a short, clear spoken answer they can understand: \"$task\""

            val body = JSONObject().apply {
                put("model", "claude-sonnet-4-6")
                put("max_tokens", 800)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }

            val req = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(req).execute()
            if (!response.isSuccessful) return@withContext Result.retry()

            val text = JSONObject(response.body?.string() ?: "")
                .getJSONArray("content").getJSONObject(0).getString("text").trim()

            applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(PENDING_RESULT, "About \"$task\": $text")
                .apply()

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
