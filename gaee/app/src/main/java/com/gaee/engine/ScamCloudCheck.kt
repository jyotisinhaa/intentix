package com.gaee.engine

import android.util.Log
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
 * Tier 2 of scam detection — the "smart second opinion". When the on-device [ScamDetector] (Tier 1)
 * is *unsure* (verdict SUSPICIOUS), this asks Claude to actually read the message and judge it.
 * Claude understands meaning, so it tells "your account is blocked, click here" (scam) apart from
 * "123456 is your OTP, do not share" (a genuine bank text) far better than any keyword list — which
 * is how we push down BOTH misses and false alarms at once.
 *
 * Privacy: the caller only invokes this when the user has opted in, and the message text is passed
 * through [ScreenRedactor] first so PIN / OTP / card digits are already `[hidden]` before anything
 * leaves the device. Claude judges the *shape* of the message, not the secret inside it.
 *
 * Network plumbing mirrors [GaeeBackgroundWorker]: one-shot OkHttp call to the Anthropic Messages
 * API, cheap model ([MODEL]), no web search. Returns null on any failure so the caller can safely
 * fall back to silence (never a false alarm from a network hiccup).
 */
class ScamCloudCheck(private val apiKey: String) {

    companion object {
        private const val MODEL = "claude-haiku-4-5"
        private const val URL = "https://api.anthropic.com/v1/messages"
        private const val PLACEHOLDER_KEY = "YOUR_CLAUDE_API_KEY"

        private val SYSTEM_PROMPT =
            "You are a fraud-protection filter for an elderly person's phone. You are shown one " +
            "incoming message (secrets like OTP or card numbers are already hidden as [hidden]). " +
            "Decide if it is a scam / phishing / fraud attempt aimed at a vulnerable user. " +
            "Common scams: fake bank or KYC alerts, 'account blocked' threats, lottery or prize " +
            "wins, UPI collect-request fraud, fake delivery or electricity-bill links, 'digital " +
            "arrest' threats, requests to share a PIN/OTP or tap a link. " +
            "Be careful NOT to alarm the user about genuine messages (real OTP deliveries, real " +
            "delivery updates, normal personal messages). Only say dangerous when you are confident. " +
            "Reply with ONLY a compact JSON object and nothing else: " +
            "{\"verdict\":\"safe|suspicious|dangerous\",\"reason\":\"<one short plain reason>\"}"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Adjudicates a single message. [rawMessage] is redacted internally before the network call.
     * Returns a [ScamDetector.Result] (score is not meaningful here) or null if the check could not
     * run (no key, network/parse failure).
     */
    suspend fun adjudicate(rawMessage: String): ScamDetector.Result? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || apiKey == PLACEHOLDER_KEY) return@withContext null
        val redacted = ScreenRedactor.redact(rawMessage)
        if (redacted.isBlank()) return@withContext null

        try {
            val body = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", 150)
                put("system", SYSTEM_PROMPT)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Incoming message:\n\"$redacted\"")
                }))
            }

            val req = Request.Builder()
                .url(URL)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(req).execute()
            val raw = response.body?.string() ?: return@withContext null
            if (!response.isSuccessful) {
                Log.e("ScamCloudCheck", "http=${response.code} body=${raw.take(200)}")
                return@withContext null
            }

            val text = JSONObject(raw).optJSONArray("content")
                ?.let { c -> buildString {
                    for (i in 0 until c.length()) {
                        val block = c.getJSONObject(i)
                        if (block.optString("type") == "text") append(block.optString("text"))
                    }
                } }
                ?.trim()
                ?: return@withContext null

            parseVerdict(text)
        } catch (e: Exception) {
            Log.e("ScamCloudCheck", "adjudicate failed", e)
            null
        }
    }

    /** Parses Claude's reply into a verdict. Tries strict JSON first, then a lenient keyword scan. */
    private fun parseVerdict(text: String): ScamDetector.Result? {
        val (verdictStr, reason) = try {
            val json = JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
            json.optString("verdict").lowercase() to json.optString("reason").ifBlank { null }
        } catch (_: Exception) {
            val lower = text.lowercase()
            val v = when {
                "dangerous" in lower -> "dangerous"
                "suspicious" in lower -> "suspicious"
                "safe" in lower -> "safe"
                else -> return null
            }
            v to null
        }

        val verdict = when (verdictStr) {
            "dangerous" -> ScamDetector.Verdict.DANGEROUS
            "suspicious" -> ScamDetector.Verdict.SUSPICIOUS
            "safe" -> ScamDetector.Verdict.SAFE
            else -> return null
        }
        return ScamDetector.Result(verdict, score = 0, reasons = listOfNotNull(reason))
    }
}
