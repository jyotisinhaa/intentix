package com.gaee.tools

import android.content.Context
import android.location.Location
import android.location.LocationManager
import com.gaee.model.ToolResult
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class WeatherTool(private val context: Context) : BaseTool {
    override val name = "WeatherTool"

    // Replace with real OpenWeatherMap API key
    private val apiKey = "YOUR_OPENWEATHERMAP_API_KEY"
    private val client = OkHttpClient()

    override suspend fun execute(args: Map<String, String>): ToolResult {
        return try {
            val location = getLastKnownLocation()
                ?: return ToolResult(false, "I could not find your location. Please check your location settings.")

            val url = "https://api.openweathermap.org/data/2.5/weather" +
                    "?lat=${location.latitude}&lon=${location.longitude}" +
                    "&appid=$apiKey&units=metric"

            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return ToolResult(false, "I could not get the weather right now. Please check again in a moment.")
            }

            val body = response.body?.string()
                ?: return ToolResult(false, "I could not get the weather right now. Please check again in a moment.")

            val json = JSONObject(body)
            val city = json.getString("name")
            val temp = json.getJSONObject("main").getDouble("temp").toInt()
            val description = json.getJSONArray("weather")
                .getJSONObject(0).getString("description")

            ToolResult(true, "Today in $city: $description, $temp degrees Celsius.")
        } catch (e: Exception) {
            ToolResult(false, "I could not get the weather right now. Please check again in a moment.", e.message)
        }
    }

    private fun getLastKnownLocation(): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        }
    }
}
