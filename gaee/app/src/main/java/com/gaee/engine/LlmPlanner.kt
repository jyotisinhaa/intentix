package com.gaee.engine

import com.gaee.model.IntentResult
import com.gaee.model.ToolCall
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LlmPlanner(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    // Guardrail 3c — the recovery re-planner may ONLY emit these navigation/observation tools.
    // Anything else (CallTool, SmsTool, WebFetcherTool, AppLauncherTool, SchedulerTool, …) would let
    // the re-planner initiate a sensitive action mid-task, bypassing the front-door confirmation gate.
    private val replanAllowedTools = setOf("UINavigator", "ScreenReaderTool", "TtsTool")

    // Complex, multi-step tasks need stronger reasoning → route to Sonnet.
    private val complexIntents = setOf(
        "send_whatsapp", "send_whatsapp_media", "book_uber", "order_food",
        "trip_plan", "multi_app_task", "schedule_task"
    )

    // Simple, single-tool device actions don't need the LLM. Asking it risks a redundant
    // generic TtsTool step that talks over a tool's own spoken output (e.g. weather). Use the
    // deterministic fallback plan for these instead.
    private val deterministicIntents = setOf(
        "get_weather", "open_camera", "open_app", "set_volume", "toggle_wifi",
        "go_back", "read_screen", "control_media", "ask_question",
        "set_alarm", "set_reminder", "cancel_alarm", "device_control"
    )

    private fun chooseModel(intent: IntentResult): String =
        if (intent.intent in complexIntents || intent.args.size > 3)
            "claude-sonnet-4-6"
        else
            "claude-haiku-4-5"

    suspend fun generatePlan(
        intent: IntentResult,
        screenState: String? = null
    ): Pair<List<ToolCall>, List<String>> =
        withContext(Dispatchers.IO) {
            if (apiKey == "YOUR_CLAUDE_API_KEY") return@withContext fallbackPlan(intent)
            if (intent.intent in deterministicIntents) return@withContext fallbackPlan(intent)

            try {
                val argsJson = gson.toJson(intent.args)
                val prompt = buildPrompt(intent.intent, argsJson, screenState)

                val body = JSONObject().apply {
                    put("model", chooseModel(intent))
                    put("max_tokens", 600)
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
                if (!response.isSuccessful) return@withContext fallbackPlan(intent)

                val rawBody = response.body?.string() ?: ""
                android.util.Log.d("LlmPlanner", "intent=${intent.intent} raw=${rawBody.take(500)}")
                val text = JSONObject(rawBody)
                    .getJSONArray("content").getJSONObject(0).getString("text").trim()
                    .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

                val json = gson.fromJson(text, JsonObject::class.java)
                val stepsType = object : TypeToken<List<ToolCall>>() {}.type
                val slotsType = object : TypeToken<List<String>>() {}.type
                val steps: List<ToolCall> = gson.fromJson(json.getAsJsonArray("steps"), stepsType)
                val slots: List<String> = gson.fromJson(json.getAsJsonArray("args_slots"), slotsType)
                android.util.Log.d("LlmPlanner", "plan steps=${steps.map { it.toolName + it.args }}")
                steps to slots
            } catch (e: Exception) {
                android.util.Log.e("LlmPlanner", "generatePlan failed → fallback", e)
                fallbackPlan(intent)
            }
        }

    private fun fallbackPlan(intent: IntentResult): Pair<List<ToolCall>, List<String>> {
        val steps = when (intent.intent) {
            "cancel_alarm" -> {
                val mode = intent.args["mode"] ?: "next"
                val time = intent.args["time"] ?: ""
                val toolArgs = mutableMapOf("action" to "cancel", "mode" to mode)
                if (time.isNotEmpty()) toolArgs["time"] = time
                listOf(ToolCall("AlarmTool", toolArgs)) to emptyList()  // AlarmTool speaks the result
            }
            "set_alarm" -> listOf(
                ToolCall("AlarmTool", mapOf("time" to "{time}", "label" to "{label}", "days" to "{days}"))
            ) to listOf("time", "label", "days")
            "set_reminder" -> listOf(
                ToolCall("ReminderTool", mapOf(
                    "time" to "{time}", "message" to "{message}", "repeatMinutes" to "{repeatMinutes}"
                ))
            ) to listOf("time", "message", "repeatMinutes")
            "call_contact" -> listOf(
                ToolCall("ContactResolverTool", mapOf("query" to "{name}")),
                ToolCall("CallTool", mapOf("name" to "{resolvedName}", "phone" to "{phone}")),
                ToolCall("TtsTool", mapOf("text" to "Calling {resolvedName} now."))
            ) to listOf("name")
            "send_sms" -> listOf(
                ToolCall("ContactResolverTool", mapOf("query" to "{name}")),
                ToolCall("SmsTool", mapOf("name" to "{resolvedName}", "phone" to "{phone}", "message" to "{message}")),
                ToolCall("TtsTool", mapOf("text" to "Message sent to {resolvedName}."))
            ) to listOf("name", "message")
            "open_app" -> listOf(
                ToolCall("AppLauncherTool", mapOf("app_name" to "{app_name}"))
            ) to listOf("app_name")
            "get_weather" -> listOf(
                ToolCall("WeatherTool", emptyMap())
            ) to emptyList()
            "set_volume" -> listOf(
                ToolCall("VolumeTool", mapOf("direction" to "{direction}")),
                ToolCall("TtsTool", mapOf("text" to "Volume turned {direction}."))
            ) to listOf("direction")
            "toggle_wifi" -> listOf(
                ToolCall("WifiTool", mapOf("state" to "{state}")),
                ToolCall("TtsTool", mapOf("text" to "WiFi turned {state}."))
            ) to listOf("state")
            "open_camera" -> listOf(
                ToolCall("CameraTool", emptyMap())
            ) to emptyList()
            "read_notifications" -> listOf(
                ToolCall("NotificationReaderTool", mapOf("action" to "read", "app" to "{app}"))
            ) to listOf("app")
            "dismiss_notifications" -> listOf(
                ToolCall("NotificationReaderTool", mapOf("action" to "dismiss", "app" to "{app}"))
            ) to listOf("app")
            "send_whatsapp" -> listOf(
                ToolCall("ContactResolverTool", mapOf("query" to "{name}")),
                ToolCall("AppLauncherTool", mapOf("app_name" to "whatsapp")),
                ToolCall("UINavigator", mapOf("action" to "wait", "target" to "Search", "timeout" to "6000")),
                ToolCall("UINavigator", mapOf("action" to "tap", "target" to "Search")),
                ToolCall("UINavigator", mapOf("action" to "type", "text" to "{resolvedName}")),
                ToolCall("UINavigator", mapOf("action" to "tap", "target" to "{resolvedName}")),
                ToolCall("UINavigator", mapOf("action" to "tap", "target" to "Message")),
                ToolCall("UINavigator", mapOf("action" to "type", "text" to "{message}")),
                ToolCall("UINavigator", mapOf("action" to "tap", "target" to "Send")),
                ToolCall("TtsTool", mapOf("text" to "Message sent to {resolvedName} on WhatsApp."))
            ) to listOf("name", "message")
            "read_screen" -> listOf(
                ToolCall("ScreenReaderTool", emptyMap())
            ) to emptyList()
            "go_back" -> listOf(
                ToolCall("UINavigator", mapOf("action" to "back"))
            ) to emptyList()
            "play_media" -> listOf(
                ToolCall("MediaControllerTool", mapOf("action" to "play", "app" to "{app}"))
            ) to listOf("app")
            "control_media" -> listOf(
                ToolCall("MediaControllerTool", mapOf("action" to "{action}", "app" to "phone"))
            ) to listOf("action")
            "schedule_task" -> listOf(
                ToolCall("SchedulerTool", mapOf("task" to "{task}", "time" to "{time}"))
            ) to listOf("task", "time")
            "ask_question" -> listOf(
                ToolCall("AnswerTool", mapOf("question" to "{question}"))
            ) to listOf("question")
            "device_control" -> listOf(
                ToolCall("DeviceControlTool", mapOf("action" to "{action}"))
            ) to listOf("action")
            else -> listOf(
                ToolCall("TtsTool", mapOf("text" to "Sorry, I could not figure out how to do that."))
            ) to emptyList()
        }
        return steps
    }

    // Shared tool reference used by both the initial planner and the recovery re-planner.
    private val toolCatalog = """
Available tools:
- ContactResolverTool: {query:"name, nickname, or relationship like 'my daughter' or 'mom'"}
  → outputs {resolvedName, phone} which are available as {resolvedName} and {phone} in later steps
- AlarmTool: {time:"HH:MM", label:"string"}
- ReminderTool: {time:"HH:MM", message:"string"}
- CallTool: {name:"string", phone:"{phone} if ContactResolverTool ran before"}
- SmsTool: {name:"string", phone:"{phone} if ContactResolverTool ran before", message:"string"}
- AppLauncherTool: {app_name:"string"}
- VolumeTool: {direction:"up|down"}
- WifiTool: {state:"on|off"}
- WeatherTool: {}
- CameraTool: {}
- TtsTool: {text:"string"}
- UINavigator: {action:"tap|type|scroll|swipe|back|find|read|wait|tap_at", target:"element text (or scroll/swipe direction up|down|left|right)", text:"text to type", timeout:"ms for wait", x:"px", y:"px"}
  → action "read" returns {screenText, appName}; "wait" blocks until target text appears (use after launching an app)
- ScreenReaderTool: {} → returns {screenText, appName}; use for "what does this say" or to verify a step worked
- WebFetcherTool: {url:"string", method:"GET|POST", body:"string or empty"}
- MediaControllerTool: {action:"play|pause|next|previous|stop", app:"spotify|youtube|phone"}
- NotificationReaderTool: {action:"read|dismiss", app:"whatsapp|gmail|instagram|messages or empty for all"}
- SchedulerTool: {task:"plain-English task to run later", time:"HH:MM or empty"} → defers heavy work to the background

WhatsApp / messaging via UINavigator (no API needed): launch the app, then drive its real UI.
Example "send Priya a WhatsApp saying I'm home":
  1. ContactResolverTool {query:"Priya"}
  2. AppLauncherTool {app_name:"whatsapp"}
  3. UINavigator {action:"wait", target:"Search", timeout:"6000"}
  4. UINavigator {action:"tap", target:"Search"}
  5. UINavigator {action:"type", text:"{resolvedName}"}
  6. UINavigator {action:"tap", target:"{resolvedName}"}
  7. UINavigator {action:"tap", target:"Message"}
  8. UINavigator {action:"type", text:"I am home safe"}
  9. UINavigator {action:"tap", target:"Send"}
  10. TtsTool {text:"Message sent to {resolvedName} on WhatsApp."}
""".trimIndent()

    private fun buildPrompt(intent: String, argsJson: String, screenState: String? = null): String {
        val screenBlock = if (!screenState.isNullOrBlank())
            "\nWhat is currently on the screen (use this to decide what to tap next):\n${ScreenRedactor.redact(screenState)}\n"
        else ""
        return """
You are an action planner for an Android phone assistant. Given an intent and its args, output a minimal sequence of tool calls.

$toolCatalog

Rules:
- When the name could be a nickname or relationship word (mom, doctor, my daughter), always run ContactResolverTool first, then use {phone} and {resolvedName} in subsequent steps
- For app-UI flows, add a UINavigator "wait" step after AppLauncherTool so the app has time to open
- Use {slot_name} in args values to reference dynamic arg values from intent args OR from prior tool outputs
- Always end with TtsTool to confirm what was done (unless WeatherTool already speaks)
- Keep plans minimal — fewest steps that complete the task
$screenBlock
Intent: $intent
Args: $argsJson

Respond ONLY with this JSON (no markdown, no explanation):
{
  "steps": [{"toolName":"ToolName","args":{"key":"value or {slot}"}}],
  "args_slots": ["slot1","slot2"]
}
""".trimIndent()
    }

    /**
     * Recovery re-planner — the "eyes". Called mid-execution when a UI step fails: given the
     * REAL screen text now on display, the original goal, and the data gathered so far, it asks
     * Claude (Sonnet — this is hard reasoning) for the next steps to finish the task. Returns the
     * remaining steps, or an empty list if it cannot help (caller then gives up gracefully).
     */
    suspend fun replan(
        intent: IntentResult,
        screenState: String,
        failedStep: ToolCall,
        context: Map<String, String>
    ): List<ToolCall> = withContext(Dispatchers.IO) {
        if (apiKey == "YOUR_CLAUDE_API_KEY") return@withContext emptyList()
        try {
            val prompt = buildReplanPrompt(intent, screenState, failedStep, context)

            val body = JSONObject().apply {
                put("model", "claude-sonnet-4-6")
                put("max_tokens", 600)
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
            if (!response.isSuccessful) return@withContext emptyList()

            val text = JSONObject(response.body?.string() ?: "")
                .getJSONArray("content").getJSONObject(0).getString("text").trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            val json = gson.fromJson(text, JsonObject::class.java)
            val stepsType = object : TypeToken<List<ToolCall>>() {}.type
            val steps = gson.fromJson<List<ToolCall>>(json.getAsJsonArray("steps"), stepsType)
                ?: emptyList()
            // Guardrail 3c: recovery may only navigate/observe — never initiate a call, SMS, web
            // POST, app-switch, or scheduled job. One forbidden tool discards the whole recovery
            // (ExecutionEngine then falls back to its graceful apology + stop).
            if (steps.any { it.toolName !in replanAllowedTools }) emptyList() else steps
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun buildReplanPrompt(
        intent: IntentResult,
        screenState: String,
        failedStep: ToolCall,
        context: Map<String, String>
    ): String {
        val argsJson = gson.toJson(intent.args)
        // Redact screen-harvested data (context may hold a prior screenText dump and phone numbers)
        // before it leaves the device for the cloud re-planner.
        val contextJson = ScreenRedactor.redact(gson.toJson(context))
        val failedJson = gson.toJson(failedStep)
        val screen = ScreenRedactor.redact(screenState).ifBlank { "(the screen appears empty or unreadable)" }
        return """
You are the recovery planner for an Android phone assistant for elderly users. A step in an ongoing task just FAILED. Look at what is ACTUALLY on the screen now and produce the next steps to recover and finish the user's goal.

$toolCatalog

The user's original goal — intent: ${intent.intent}, args: $argsJson
Data gathered from earlier steps (reference these as {slot}): $contextJson
The step that just failed: $failedJson

What is ACTUALLY on the screen right now (tap labels that truly exist here — they may differ from the original plan):
$screen

Rules:
- You may use ONLY these tools: UINavigator, ScreenReaderTool, TtsTool. Never use CallTool, SmsTool, WebFetcherTool, AppLauncherTool, SchedulerTool or any other tool. If finishing the goal would require one of those, instead return a single TtsTool step telling the user to say the command again so it can be confirmed safely.
- Output ONLY the remaining steps needed from here to finish the goal. Do not repeat steps already completed.
- Match the real on-screen text above. If a label differs (e.g. "Send message" instead of "Send"), use what is actually shown.
- If you cannot find the target, try UINavigator "scroll"/"swipe" to reveal it, or UINavigator "find" to confirm presence.
- If the goal already looks complete on screen, return a single TtsTool step confirming success.
- If the goal is impossible from this screen, return a single TtsTool step apologising briefly in simple words.
- Use {slot} to reference the gathered data. End with a TtsTool confirmation when the goal is done.

Respond ONLY with this JSON (no markdown, no explanation):
{
  "steps": [{"toolName":"ToolName","args":{"key":"value or {slot}"}}]
}
""".trimIndent()
    }
}
