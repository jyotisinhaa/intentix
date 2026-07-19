package com.gaee.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.gaee.model.IntentResult
import com.gaee.model.ModelTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class IntentClassifier(private val context: Context) {

    // Loaded from gaee/.env at build time (see BuildConfig / build.gradle.kts)
    val claudeApiKey = com.gaee.BuildConfig.CLAUDE_API_KEY
    private val claudeModel = "claude-haiku-4-5"
    private val claudeApiUrl = "https://api.anthropic.com/v1/messages"

    private val prefs = context.getSharedPreferences("gaee", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    var currentTier: ModelTier = ModelTier.KEYWORD
        private set

    suspend fun detectTier(): ModelTier {
        val stored = prefs.getString("model_tier", null)
        if (stored != null) {
            currentTier = ModelTier.valueOf(stored)
            return currentTier
        }

        // Tier 1: check for on-device model (Gemini Nano via ML Kit)
        val onDeviceAvailable = checkOnDeviceModel()
        if (onDeviceAvailable) {
            prefs.edit().putString("model_tier", ModelTier.ON_DEVICE.name).apply()
            currentTier = ModelTier.ON_DEVICE
            return currentTier
        }

        // Tier 2: cloud previously approved?
        if (prefs.getBoolean("cloud_enabled", false)) {
            currentTier = ModelTier.CLOUD
            return currentTier
        }

        // Tier 2: user must approve — handled by UI via callback
        currentTier = ModelTier.KEYWORD
        return currentTier
    }

    fun approveCloud() {
        prefs.edit().putBoolean("cloud_enabled", true).apply()
        currentTier = ModelTier.CLOUD
    }

    fun declineCloud() {
        currentTier = ModelTier.KEYWORD
    }

    suspend fun classify(transcript: String): IntentResult {
        val hasInternet = isNetworkAvailable()
        val raw = when {
            currentTier == ModelTier.ON_DEVICE -> classifyOnDevice(transcript)
            currentTier == ModelTier.CLOUD && hasInternet -> {
                // Keyword-first: an unambiguous device command (alarm, reminder, call, weather, …)
                // is matched locally and does NOT go to the cloud — faster, and it stops the LLM
                // from misrouting a clear command to Q&A. Only ambiguous input / real questions
                // (which the keyword matcher leaves at low confidence) fall through to Claude.
                val kw = classifyKeywords(transcript)
                if (kw.confidence >= 0.85f) kw else classifyCloud(transcript)
            }
            else -> classifyKeywords(transcript)
        }
        android.util.Log.d("IntentClassifier", "tier=$currentTier intent=${raw.intent} conf=${raw.confidence}")
        return normalizeArgs(raw, transcript)
    }

    // Post-process every result regardless of tier — fixes AM/PM, tomorrow flag, and app name prefix
    private fun normalizeArgs(result: IntentResult, transcript: String): IntentResult {
        if (result.intent == "open_app") {
            val raw = result.args["app_name"] ?: return result
            val clean = cleanAppName(raw)
            return result.copy(
                args = result.args + mapOf("app_name" to clean),
                speakBefore = "Opening $clean."
            )
        }
        if (result.intent != "set_alarm" && result.intent != "set_reminder") return result
        val lower = transcript.lowercase()
        val args = result.args.toMutableMap()

        // Resolve relative times ("in 10 minutes", "10 mins", "in 2 hours") to an absolute HH:MM.
        // The cloud classifier can't do this — it is never told the current time — so compute it
        // deterministically here. This overrides whatever time the classifier guessed.
        extractRelativeTime(lower)?.let {
            args["time"] = it
            return result.copy(args = args) // relative time is exact — skip AM/PM guessing below
        }

        // Correct AM/PM when LLM returns wrong hour
        val time = args["time"]
        if (time != null) {
            val parts = time.split(":")
            val hour = parts[0].toIntOrNull()
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (hour != null) {
                val needsPm = lower.contains("pm") || lower.contains("evening") ||
                              lower.contains("night") || lower.contains("afternoon")
                val needsAm = lower.contains("am") || lower.contains("morning")
                val corrected = when {
                    needsPm && hour < 12 -> hour + 12
                    needsAm && hour == 12 -> 0
                    else -> hour
                }
                if (corrected != hour) args["time"] = "%02d:%02d".format(corrected, minute)
            }
        }

        // Propagate tomorrow flag (cloud classifier doesn't know about this)
        if (lower.contains("tomorrow") && args["tomorrow"] == null) {
            args["tomorrow"] = "true"
        }

        return result.copy(args = args)
    }

    private fun checkOnDeviceModel(): Boolean {
        // ML Kit AIManager availability check
        return try {
            val clazz = Class.forName("com.google.mlkit.nl.languageid.LanguageIdentification")
            // If ML Kit is available, check for Gemini Nano
            // For Phase 1, we rely on the play-services-mlkit-language-id dependency presence
            // Real Gemini Nano check requires GenerativeModel from google-ai-client-android
            false // Conservative: default to cloud for broader device support
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    private suspend fun classifyCloud(transcript: String): IntentResult = withContext(Dispatchers.IO) {
        try {
            val systemPrompt = buildSystemPrompt()
            val userMessage = transcript

            val body = JSONObject().apply {
                put("model", claudeModel)
                put("max_tokens", 300)
                put("system", systemPrompt)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userMessage)
                    })
                })
            }

            val request = Request.Builder()
                .url(claudeApiUrl)
                .addHeader("x-api-key", claudeApiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext classifyKeywords(transcript)
            }

            val responseBody = response.body?.string() ?: return@withContext classifyKeywords(transcript)
            val json = JSONObject(responseBody)
            val content = json.getJSONArray("content").getJSONObject(0).getString("text").trim()

            parseIntentJson(content)
        } catch (e: Exception) {
            classifyKeywords(transcript)
        }
    }

    private fun classifyOnDevice(transcript: String): IntentResult {
        // Phase 1: on-device Gemini Nano not yet integrated; fall through to keywords
        return classifyKeywords(transcript)
    }

    private fun classifyKeywords(transcript: String): IntentResult {
        val lower = transcript.lowercase().trim()

        return when {
            // "open X app" must be caught before specific intent keywords fire
            (lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("start ")) && lower.trimEnd().endsWith(" app") -> {
                val appName = cleanAppName(lower)
                IntentResult(
                    intent = "open_app",
                    confidence = 0.90f,
                    args = mapOf("app_name" to appName),
                    confirmationRequired = false,
                    speakBefore = "Opening $appName."
                )
            }
            (lower.contains("cancel") || lower.contains("delete") || lower.contains("remove") || lower.contains("dismiss")) && lower.contains("alarm") -> {
                val specificTime = extractTime(lower)
                val cancelAll = lower.contains("all") || lower.contains("every")
                val (mode, speakText, args) = when {
                    specificTime != null -> Triple(
                        "time",
                        "Cancelling your alarm for $specificTime.",
                        mapOf("mode" to "time", "time" to specificTime)
                    )
                    cancelAll -> Triple(
                        "all",
                        "Cancelling all your alarms.",
                        mapOf("mode" to "all")
                    )
                    else -> Triple(
                        "next",
                        "Cancelling your next alarm.",
                        mapOf("mode" to "next")
                    )
                }
                IntentResult(
                    intent = "cancel_alarm",
                    confidence = 0.90f,
                    args = args,
                    confirmationRequired = false,
                    speakBefore = speakText
                )
            }
            lower.contains("alarm") || lower.contains("wake me") -> {
                val time = extractTime(lower) ?: "07:00"
                val tomorrow = lower.contains("tomorrow")
                val days = extractDays(lower)
                val args = buildMap {
                    put("time", time)
                    if (tomorrow) put("tomorrow", "true")
                    if (days.isNotEmpty()) put("days", days)
                }
                val speak = if (tomorrow) "Opening the alarm for tomorrow at $time. Please tap tomorrow and then save."
                            else "Setting your alarm for $time."
                IntentResult(
                    intent = "set_alarm",
                    confidence = 0.85f,
                    args = args,
                    confirmationRequired = false,
                    speakBefore = speak
                )
            }
            lower.contains("remind") -> {
                val time = extractTime(lower) ?: "09:00"
                val repeatMinutes = extractRepeatMinutes(lower)
                val message = cleanReminderMessage(lower)
                IntentResult(
                    intent = "set_reminder",
                    confidence = 0.85f,
                    args = mapOf(
                        "time" to time,
                        "message" to message.ifEmpty { "your reminder" },
                        "repeatMinutes" to repeatMinutes.toString()
                    ),
                    confirmationRequired = false,
                    speakBefore = "Setting your reminder."
                )
            }
            lower.contains("call") && !lower.contains("who called") -> {
                val name = extractNameAfterKeyword(lower, "call")
                IntentResult(
                    intent = "call_contact",
                    confidence = 0.85f,
                    args = mapOf("name" to name),
                    confirmationRequired = true,
                    speakBefore = "I will call $name now. Is that okay?"
                )
            }
            // "do I have any messages / notifications / whatsapp" — must come before send_sms
            isReadNotificationQuery(lower) -> {
                val app = extractNotificationAppFilter(lower)
                IntentResult(
                    intent = "read_notifications",
                    confidence = 0.90f,
                    args = if (app != null) mapOf("action" to "read", "app" to app)
                           else mapOf("action" to "read"),
                    confirmationRequired = false,
                    speakBefore = "Let me check your notifications."
                )
            }
            (lower.contains("dismiss") || lower.contains("clear") || lower.contains("delete"))
                && (lower.contains("notification") || lower.contains("message")) -> {
                val app = extractNotificationAppFilter(lower)
                IntentResult(
                    intent = "dismiss_notifications",
                    confidence = 0.90f,
                    args = if (app != null) mapOf("action" to "dismiss", "app" to app)
                           else mapOf("action" to "dismiss"),
                    confirmationRequired = false,
                    speakBefore = "Clearing your notifications."
                )
            }
            lower.contains("whatsapp") &&
                (lower.contains("send") || lower.contains("message") || lower.contains("text") || lower.contains("say")) -> {
                val cleaned = lower.replace("on whatsapp", " ").replace("whatsapp", " ")
                    .replace(Regex("\\s+"), " ").trim()
                val (name, msgContent) = extractSmsNameAndMessage(cleaned)
                IntentResult(
                    intent = "send_whatsapp",
                    confidence = 0.85f,
                    args = mapOf("name" to name, "message" to msgContent),
                    confirmationRequired = true,
                    speakBefore = "I will send a WhatsApp to $name saying: $msgContent. Is that okay?"
                )
            }
            lower.contains("message") || lower.contains("text") || lower.contains("send") -> {
                val (name, msgContent) = extractSmsNameAndMessage(lower)
                IntentResult(
                    intent = "send_sms",
                    confidence = 0.80f,
                    args = mapOf("name" to name, "message" to msgContent),
                    confirmationRequired = true,
                    speakBefore = "I will send a message to $name saying: $msgContent. Is that okay?"
                )
            }
            lower.contains("weather") || lower.contains("temperature") || lower.contains("forecast") -> {
                IntentResult(
                    intent = "get_weather",
                    confidence = 0.90f,
                    args = emptyMap(),
                    confirmationRequired = false,
                    speakBefore = "Getting the weather for you."
                )
            }
            lower.contains("volume") || lower.contains("louder") || lower.contains("quieter") || lower.contains("turn up") || lower.contains("turn down") -> {
                val direction = when {
                    lower.contains("up") || lower.contains("louder") || lower.contains("increase") -> "up"
                    else -> "down"
                }
                IntentResult(
                    intent = "set_volume",
                    confidence = 0.85f,
                    args = mapOf("direction" to direction),
                    confirmationRequired = false,
                    speakBefore = "Turning volume $direction."
                )
            }
            lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("internet") -> {
                val state = if (lower.contains("on") || lower.contains("enable") || lower.contains("connect")) "on" else "off"
                IntentResult(
                    intent = "toggle_wifi",
                    confidence = 0.85f,
                    args = mapOf("state" to state),
                    confirmationRequired = false,
                    speakBefore = "Turning WiFi $state."
                )
            }
            lower.contains("camera") || lower.contains("photo") || lower.contains("picture") || lower.contains("selfie") -> {
                IntentResult(
                    intent = "open_camera",
                    confidence = 0.90f,
                    args = emptyMap(),
                    confirmationRequired = false,
                    speakBefore = "Opening the camera."
                )
            }
            deviceControlAction(lower) != null -> {
                IntentResult(
                    intent = "device_control",
                    confidence = 0.88f,
                    args = mapOf("action" to deviceControlAction(lower)!!),
                    confirmationRequired = false,
                    speakBefore = "Okay."
                )
            }
            lower.contains("what does this say") || lower.contains("read the screen") ||
                lower.contains("read this") || lower.contains("what is on the screen") ||
                lower.contains("what's on the screen") -> {
                IntentResult(
                    intent = "read_screen",
                    confidence = 0.90f,
                    args = emptyMap(),
                    confirmationRequired = false,
                    speakBefore = "Let me read that for you."
                )
            }
            lower == "go back" || lower == "back" || lower.contains("go back") ||
                lower.contains("cancel that") || lower.contains("previous screen") -> {
                IntentResult(
                    intent = "go_back",
                    confidence = 0.90f,
                    args = emptyMap(),
                    confirmationRequired = false,
                    speakBefore = "Going back."
                )
            }
            (lower.startsWith("pause") || lower.startsWith("resume") || lower.contains("next song") ||
                lower.contains("previous song") || lower.contains("next track") ||
                lower.contains("stop the music") || lower.contains("stop music")) -> {
                val action = when {
                    lower.contains("pause") -> "pause"
                    lower.contains("resume") || lower.startsWith("play") -> "play"
                    lower.contains("next") -> "next"
                    lower.contains("previous") || lower.contains("back") -> "previous"
                    else -> "stop"
                }
                IntentResult(
                    intent = "control_media",
                    confidence = 0.85f,
                    args = mapOf("action" to action),
                    confirmationRequired = false,
                    speakBefore = "Okay."
                )
            }
            lower.startsWith("play ") -> {
                val app = when {
                    lower.contains("youtube") -> "youtube"
                    lower.contains("spotify") -> "spotify"
                    else -> "phone"
                }
                val query = lower.removePrefix("play ")
                    .replace("on youtube", "").replace("on spotify", "").trim()
                IntentResult(
                    intent = "play_media",
                    confidence = 0.80f,
                    args = mapOf("query" to query, "app" to app),
                    confirmationRequired = false,
                    speakBefore = "Playing $query."
                )
            }
            (lower.startsWith("plan ") || lower.contains("research ") || lower.startsWith("book me") ||
                lower.contains("look into") || lower.contains("figure out")) -> {
                val time = extractTime(lower)
                val args = if (time != null) mapOf("task" to transcript, "time" to time)
                           else mapOf("task" to transcript)
                IntentResult(
                    intent = "schedule_task",
                    confidence = 0.70f,
                    args = args,
                    confirmationRequired = false,
                    speakBefore = "Okay, I will work on that and let you know."
                )
            }
            isQuestionLike(lower) -> {
                IntentResult(
                    intent = "ask_question",
                    confidence = 0.75f,
                    args = mapOf("question" to transcript),
                    confirmationRequired = false,
                    speakBefore = "Let me check."
                )
            }
            else -> {
                val appName = cleanAppName(transcript)
                IntentResult(
                    intent = "open_app",
                    confidence = 0.60f,
                    args = mapOf("app_name" to appName),
                    confirmationRequired = false,
                    speakBefore = "Opening $appName."
                )
            }
        }
    }

    private fun parseIntentJson(raw: String): IntentResult {
        return try {
            val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val json = JSONObject(cleaned)

            if (json.optBoolean("unknown", false)) {
                return IntentResult(
                    intent = "unknown",
                    confidence = 0f,
                    args = emptyMap(),
                    confirmationRequired = false,
                    speakBefore = "Let me check.",
                    unknown = true
                )
            }

            val argsJson = json.optJSONObject("args") ?: JSONObject()
            val args = mutableMapOf<String, String>()
            argsJson.keys().forEach { key ->
                args[key] = argsJson.getString(key)
            }

            IntentResult(
                intent = json.getString("intent"),
                confidence = json.getDouble("confidence").toFloat(),
                args = args,
                confirmationRequired = json.getBoolean("confirmation_required"),
                speakBefore = json.getString("speak_before"),
                unknown = false
            )
        } catch (e: Exception) {
            IntentResult(
                intent = "unknown",
                confidence = 0f,
                args = emptyMap(),
                confirmationRequired = false,
                speakBefore = "Let me check.",
                unknown = true
            )
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // Maps a phrase to a device-control action Android actually allows, or null.
    private fun deviceControlAction(text: String): String? = when {
        (text.contains("close") || text.contains("clear") || text.contains("kill")) &&
            (text.contains("background") || text.contains("running") || text.contains("recent")) &&
            text.contains("app") -> "recents"
        text.contains("recent apps") || text.contains("recents") -> "recents"
        text.contains("go home") || text.contains("home screen") -> "home"
        text.contains("lock") && (text.contains("phone") || text.contains("screen") || text.contains("device")) -> "lock"
        text.contains("screenshot") || text.contains("screen shot") -> "screenshot"
        text.contains("quick settings") || text.contains("quick setting") -> "quick_settings"
        text.contains("open settings") || text.contains("phone settings") ||
            text.contains("go to settings") || text.contains("device settings") -> "settings"
        else -> null
    }

    // Recurrence for alarms → comma list of MON..SUN (empty = one-shot).
    private fun extractDays(text: String): String {
        if (text.contains("weekday")) return "MON,TUE,WED,THU,FRI"
        if (text.contains("weekend")) return "SAT,SUN"
        if (text.contains("every day") || text.contains("everyday") || text.contains("daily")) {
            return "MON,TUE,WED,THU,FRI,SAT,SUN"
        }
        if (!text.contains("every")) return ""
        val map = linkedMapOf(
            "monday" to "MON", "tuesday" to "TUE", "wednesday" to "WED", "thursday" to "THU",
            "friday" to "FRI", "saturday" to "SAT", "sunday" to "SUN"
        )
        return map.filterKeys { text.contains(it) }.values.joinToString(",")
    }

    // Recurrence for reminders → repeat interval in minutes (0 = one-shot; 1440 = daily).
    private fun extractRepeatMinutes(text: String): Int {
        Regex("""every\s+(\d+)\s*min(?:ute)?s?""").find(text)?.let {
            return it.groupValues[1].toIntOrNull() ?: 0
        }
        Regex("""every\s+(\d+)\s*hours?""").find(text)?.let {
            return (it.groupValues[1].toIntOrNull() ?: 0) * 60
        }
        if (Regex("""every\s+minute""").containsMatchIn(text)) return 1
        if (Regex("""every\s+hour""").containsMatchIn(text)) return 60
        if (Regex("""every\s+(day|morning|evening|night|afternoon)""").containsMatchIn(text) ||
            text.contains("daily") || text.contains("each day")) return 1440
        return 0
    }

    // Strip command words, recurrence phrases, and time phrases to leave just the reminder content.
    private fun cleanReminderMessage(text: String): String {
        return text
            .replace(Regex("""\b(set|create|add|make)\s+(a|an|the)?\s*reminder\s*(to|that|for)?\b"""), " ")
            .replace(Regex("""\bremind(er)?\s*(me)?\s*(to|that|for)?\b"""), " ")
            .replace(Regex("""\bevery\s+\d+\s*(minutes?|mins?|hours?)\b"""), " ")
            .replace(Regex("""\bevery\s+(minute|hour|day|morning|evening|night|afternoon)\b"""), " ")
            .replace(Regex("""\b(daily|each day|everyday)\b"""), " ")
            .replace(Regex("""\b(at|in)\s+\d{1,2}(:\d{2})?\s*(am|pm|o'?clock)?\b"""), " ")
            .replace(Regex("""\bin\s+\d+\s*(minutes?|mins?|hours?)\b"""), " ")
            .replace(Regex("""^\s*(to|that|for)\s+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    // Resolves ONLY relative expressions ("in 10 minutes", "10 mins", "in 2 hours") to an absolute
    // HH:MM based on the current time. Returns null if the text has no relative time.
    private fun extractRelativeTime(text: String): String? {
        Regex("""(?:in\s+)?(\d+)\s*min(?:ute)?s?""").find(text)?.let { m ->
            val mins = m.groupValues[1].toIntOrNull() ?: return null
            val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MINUTE, mins) }
            return "%02d:%02d".format(
                cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)
            )
        }
        Regex("""(?:in\s+)?(\d+)\s*hours?""").find(text)?.let { m ->
            val hrs = m.groupValues[1].toIntOrNull() ?: return null
            val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.HOUR_OF_DAY, hrs) }
            return "%02d:%02d".format(
                cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)
            )
        }
        return null
    }

    private fun extractTime(text: String): String? {
        // Relative: "in 5 minutes", "5 min", "30 minutes", "2 hours"
        val relMin = Regex("""(?:in\s+)?(\d+)\s*min(?:ute)?s?""").find(text)
        if (relMin != null) {
            val mins = relMin.groupValues[1].toIntOrNull() ?: 0
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.MINUTE, mins)
            return "%02d:%02d".format(
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE)
            )
        }
        val relHr = Regex("""(?:in\s+)?(\d+)\s*hours?""").find(text)
        if (relHr != null) {
            val hrs = relHr.groupValues[1].toIntOrNull() ?: 0
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.HOUR_OF_DAY, hrs)
            return "%02d:%02d".format(
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE)
            )
        }

        // Detect time-of-day words — map to am/pm bias
        val forcePm = text.contains("evening") || text.contains("night") ||
                      text.contains("afternoon") || text.contains("pm")
        val forceAm = text.contains("morning") || text.contains("am")

        // Absolute: "7:30", "7:30 pm", "7pm", "7 in the evening"
        val timeRegex = Regex("""(\d{1,2}):(\d{2})(?:\s*(?:am|pm))?|(\d{1,2})\s*(?:am|pm|o'?clock)?""")
        val match = timeRegex.find(text) ?: return null

        val hour: Int
        val minute: Int

        if (match.groupValues[2].isNotEmpty()) {
            // HH:MM format
            hour = match.groupValues[1].toIntOrNull() ?: return null
            minute = match.groupValues[2].toIntOrNull() ?: 0
        } else {
            hour = match.groupValues[3].toIntOrNull() ?: return null
            minute = 0
        }

        // Apply am/pm from explicit words or time-of-day context
        val adjustedHour = when {
            forcePm && hour < 12 -> hour + 12
            forceAm && hour == 12 -> 0
            // No explicit hint: assume PM for hours 1-6 (ambiguous like "6 o'clock"),
            // AM for hours 7-11 (unlikely to mean 7pm for an alarm)
            !forcePm && !forceAm && hour in 1..6 -> hour + 12
            else -> hour
        }

        return "%02d:%02d".format(adjustedHour, minute)
    }

    private fun extractNameAfterKeyword(text: String, keyword: String): String {
        val idx = text.indexOf(keyword)
        if (idx == -1) return "unknown"
        return text.substring(idx + keyword.length).trim()
            .split(Regex("\\s+")).take(3).joinToString(" ").trim()
            .ifEmpty { "unknown" }
    }

    private fun extractNameAfterKeyword(text: String, keywords: List<String>): String {
        for (kw in keywords) {
            val idx = text.indexOf(kw)
            if (idx != -1) {
                val after = text.substring(idx + kw.length).trim()
                if (after.isNotEmpty()) return after.split(Regex("\\s+")).take(3).joinToString(" ")
            }
        }
        return "unknown"
    }

    private fun extractSmsNameAndMessage(text: String): Pair<String, String> {
        // Split at message content markers
        val msgMarkers = listOf(" saying ", " says ", " that says ", " with message ", " the message ")
        for (marker in msgMarkers) {
            val idx = text.indexOf(marker)
            if (idx != -1) {
                val message = text.substring(idx + marker.length).trim()
                val beforeMarker = text.substring(0, idx)
                val name = extractNameFromSmsPrefix(beforeMarker)
                if (name.isNotEmpty() && message.isNotEmpty()) return name to message
            }
        }
        // No marker — best effort: name only, no message body
        return extractNameFromSmsPrefix(text) to ""
    }

    private fun extractNameFromSmsPrefix(text: String): String {
        // Strip command words, keep the contact name
        val cleaned = text
            .replace(Regex("^(send\\s+a\\s+message\\s+to|send\\s+message\\s+to|send\\s+an?\\s+sms\\s+to|send\\s+sms\\s+to|send\\s+an?\\s+text\\s+to|send\\s+text\\s+to|text\\s+a\\s+message\\s+to|message\\s+to|text\\s+to|send\\s+to|text|send)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:an?\\s+)?(?:sms|text|message)\\s+to\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^to\\s+", RegexOption.IGNORE_CASE), "")
            .trim()
        // Take max 2 words as the name
        return cleaned.split(Regex("\\s+")).take(2).joinToString(" ").trim()
    }

    // Heuristic: does this look like a free-form question rather than an app name / command?
    // Only used in offline keyword tier — the cloud classifier decides directly.
    private fun isQuestionLike(lower: String): Boolean {
        if (lower.contains("?")) return true
        val starters = listOf(
            "what", "why", "how", "who", "whom", "whose", "when", "where", "which",
            "will", "is", "are", "am", "was", "were", "can", "could", "should", "would",
            "do", "does", "did", "tell me", "explain", "define", "calculate"
        )
        val first = lower.trim().substringBefore(' ')
        if (starters.any { lower.trim().startsWith("$it ") } || first in starters) return true
        // A multi-word sentence is far more likely a question than an app name.
        return lower.trim().split(Regex("\\s+")).size >= 4
    }

    private fun isReadNotificationQuery(lower: String): Boolean {
        val readWords = listOf("do i have", "any messages", "any notification", "check message",
            "check notification", "read my", "show my", "what are my", "what notification",
            "new message", "new notification", "unread", "inbox", "missed")
        return readWords.any { lower.contains(it) }
    }

    private fun extractNotificationAppFilter(lower: String): String? {
        val apps = listOf("whatsapp", "telegram", "instagram", "gmail", "email",
                          "facebook", "messages", "sms")
        return apps.firstOrNull { lower.contains(it) }
    }

    private fun cleanAppName(transcript: String): String =
        transcript.lowercase()
            .removePrefix("open ").removePrefix("launch ")
            .removePrefix("start ").removePrefix("run ")
            .removePrefix("can you open ").removePrefix("please open ")
            .trim()
            .replaceFirstChar { it.uppercase() }

    private fun extractMessageContent(text: String): String {
        val markers = listOf("saying", "says", "that says", "with", "message")
        for (marker in markers) {
            val idx = text.indexOf(marker)
            if (idx != -1) {
                val content = text.substring(idx + marker.length).trim()
                if (content.isNotEmpty()) return content
            }
        }
        return text
    }

    private fun buildSystemPrompt(): String = """
You are an intent classifier for an Android phone assistant designed for elderly users. The user will speak a command in natural language. Your job is to classify it.

You must respond with ONLY a valid JSON object. No explanation. No markdown. No extra text.

JSON format:
{
  "intent": "<intent_name>",
  "confidence": <0.0 to 1.0>,
  "args": { <key>: <value> },
  "confirmation_required": <true|false>,
  "speak_before": "<what to say to user before confirming>",
  "unknown": <true|false>
}

Valid intents and their args:
  cancel_alarm     → args: {}
  set_alarm        → args: { time: "HH:MM 24-hour e.g. 20:00 for 8pm", label: "string or null", days: "comma list of MON,TUE,WED,THU,FRI,SAT,SUN for a repeating alarm, or empty for one-time" }
  set_reminder     → args: { time: "HH:MM first time", message: "what to do, no time/repeat words", repeatMinutes: "0 for once, N for every N minutes, 60 hourly, 1440 daily" }
  call_contact     → args: { name: "string" }
  send_sms         → args: { name: "string", message: "string" }
  send_whatsapp    → args: { name: "string", message: "string" }
  open_app         → args: { app_name: "app name only, no prefix — e.g. whatsapp not open whatsapp" }
  get_weather      → args: {}
  set_volume       → args: { direction: "up|down", level: 0-100 or null }
  toggle_wifi      → args: { state: "on|off" }
  open_camera      → args: {}
  read_notifications  → args: { app: "whatsapp|gmail|messages or null for all" }
  dismiss_notifications → args: { app: "string or null for all" }
  read_screen      → args: {}
  go_back          → args: {}
  play_media       → args: { query: "string", app: "youtube|spotify|phone" }
  control_media    → args: { action: "play|pause|next|previous|stop" }
  schedule_task    → args: { task: "plain-English task", time: "HH:MM or null" }
  ask_question     → args: { question: "the user's full question, word for word" }
  device_control   → args: { action: "recents|home|quick_settings|notifications|settings|lock|screenshot" }

Rules:
  - Use device_control for phone controls: "close background apps"/"clear recent apps"→recents, "go home"→home, "quick settings"→quick_settings, "open settings"→settings, "lock the phone"→lock, "take a screenshot"→screenshot.
  - Use ask_question for ANY general question or request for information that is not one of the device actions above — e.g. "will it rain today", "how far is the moon", "who won the match", "what is 15 percent of 200", "how do I make dal". Put the user's exact words in the question arg.
  - confirmation_required must be true for: call_contact, send_sms, send_whatsapp
  - confirmation_required is false for: cancel_alarm, set_alarm, set_reminder, get_weather, set_volume, toggle_wifi, open_app, open_camera, read_notifications, dismiss_notifications, read_screen, go_back, play_media, control_media, schedule_task, ask_question, device_control
  - Use send_whatsapp (not send_sms) when the user says "whatsapp", "on whatsapp", or "whatsapp message"
  - If you cannot match any intent with confidence >= 0.6, set unknown: true and intent: "unknown"
  - speak_before must be simple plain English an elderly person can understand
  - speak_before example for call_contact: "I will call Maria now. Is that okay?"
  - speak_before example for set_alarm: "Setting your alarm for 7am."
""".trimIndent()
}
