package com.gaee.tools

import android.content.Context
import android.location.LocationManager
import android.util.Log
import com.gaee.model.ToolResult
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
 * Conversational "ask anything" tool. Sends the user's question to Claude (with web search for live
 * facts) and returns a short, TTS-friendly spoken answer. Keeps a small rolling history so
 * follow-ups ("what about tomorrow?") have context.
 *
 * args: { question: "the user's full question" }
 */
class AnswerTool(private val context: Context) : BaseTool {
    override val name = "AnswerTool"

    private val apiKey = com.gaee.BuildConfig.CLAUDE_API_KEY
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    companion object {
        // Rolling multi-turn memory: last MAX_HISTORY messages (user/assistant), shared across calls.
        private const val MAX_HISTORY = 6
        private val history = ArrayList<Pair<String, String>>() // role to text
    }

    override suspend fun execute(args: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val question = args["question"]?.trim().orEmpty()
        if (question.isBlank()) {
            return@withContext ToolResult(false, "I didn't catch your question. Please ask again.")
        }
        if (apiKey == "YOUR_CLAUDE_API_KEY") {
            return@withContext ToolResult(false, "I need to be connected to answer questions. Please set up the assistant first.")
        }

        try {
            val messages = JSONArray().apply {
                synchronized(history) { history.forEach { (role, text) -> put(msg(role, text)) } }
                put(msg("user", question))
            }

            val body = JSONObject().apply {
                put("model", "claude-sonnet-4-6")
                put("max_tokens", 700)
                put("system", SYSTEM_PROMPT + "\n\n" + contextPreamble())
                put("tools", JSONArray().put(JSONObject().apply {
                    put("type", "web_search_20260209")
                    put("name", "web_search")
                    put("max_uses", 3)
                }))
                put("messages", messages)
            }

            var answer = requestAnswer(body, messages)
                ?: return@withContext ToolResult(false, "I could not find an answer right now. Please try again in a moment.")

            answer = answer.trim()
            if (answer.isBlank()) {
                return@withContext ToolResult(false, "I could not find an answer right now. Please try again in a moment.")
            }

            // Update rolling memory.
            synchronized(history) {
                history.add("user" to question)
                history.add("assistant" to answer)
                while (history.size > MAX_HISTORY) history.removeAt(0)
            }

            ToolResult(true, answer)
        } catch (e: Exception) {
            Log.e("AnswerTool", "answer failed", e)
            ToolResult(false, "I could not find an answer right now. Please try again in a moment.", e.message)
        }
    }

    /** Runs the request, resuming through server-tool pauses, and returns concatenated text blocks. */
    private fun requestAnswer(body: JSONObject, messages: JSONArray): String? {
        var continuations = 0
        while (true) {
            val req = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(req).execute()
            val raw = response.body?.string() ?: return null
            if (!response.isSuccessful) {
                Log.e("AnswerTool", "http=${response.code} body=${raw.take(300)}")
                return null
            }

            val json = JSONObject(raw)
            val content = json.optJSONArray("content") ?: return null
            val text = buildString {
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    if (block.optString("type") == "text") append(block.optString("text")).append(' ')
                }
            }.trim()

            // Server-side web search may pause the turn; resume by echoing the assistant content back.
            if (json.optString("stop_reason") == "pause_turn" && continuations < 2) {
                continuations++
                messages.put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", content)
                })
                body.put("messages", messages)
                continue
            }
            return text
        }
    }

    private fun msg(role: String, text: String) = JSONObject().apply {
        put("role", role)
        put("content", text)
    }

    /** Grounds the model in the phone's current date/time and approximate location. */
    private fun contextPreamble(): String {
        val now = ZonedDateTime.now()
        val stamp = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy, h:mm a z"))
        val sb = StringBuilder("Current context — today is $stamp.")
        lastKnownLatLon()?.let { (lat, lon) ->
            sb.append(" The user's approximate location is latitude ${"%.4f".format(lat)}, ")
                .append("longitude ${"%.4f".format(lon)}.")
        }
        sb.append(" Use this to ground time- or place-relative questions like \"today\", ")
            .append("\"this year\", \"near me\", or local weather.")
        return sb.toString()
    }

    private fun lastKnownLatLon(): Pair<Double, Double>? = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.allProviders
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { it.latitude to it.longitude }
    } catch (e: SecurityException) {
        null
    }
}

private const val SYSTEM_PROMPT =
    "You are Hello, a friendly voice assistant that is the brain of an elderly user's phone. " +
    "You can already do these on the phone: set alarms and reminders, make calls, send SMS and " +
    "WhatsApp messages, check the weather, open apps, control volume and Wi-Fi, read notifications, " +
    "and open recent apps / settings. Answer the user directly in 1 to 3 short spoken sentences with " +
    "plain, simple words — no markdown, lists, emojis, or symbols, because this is read aloud. " +
    "For current facts (weather, news, prices, sports) use web search. " +
    "If the user asks you to do something on the phone that you cannot do, do not just refuse — " +
    "kindly explain in one sentence and suggest the simplest real way to do it (for example, if they " +
    "ask to close background apps, tell them you can open the recent apps screen so they can swipe " +
    "them away). If you are unsure, say so briefly and kindly."
