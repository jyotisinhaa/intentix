package com.gaee.tools

import com.gaee.model.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class WebFetcherTool : BaseTool {
    override val name = "WebFetcherTool"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val url = args["url"] ?: return@withContext ToolResult(false, "No URL provided.")
        val method = args["method"]?.uppercase() ?: "GET"
        val body = args["body"] ?: ""

        try {
            val request = Request.Builder().url(url).apply {
                when (method) {
                    "POST" -> post(body.toRequestBody("application/json".toMediaType()))
                    "PUT" -> put(body.toRequestBody("application/json".toMediaType()))
                    else -> get()
                }
            }.build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                ToolResult(true, responseBody)
            } else {
                ToolResult(false, "Request failed with status ${response.code}.")
            }
        } catch (e: Exception) {
            ToolResult(false, "I could not reach the internet right now. Please try again.", e.message)
        }
    }
}
