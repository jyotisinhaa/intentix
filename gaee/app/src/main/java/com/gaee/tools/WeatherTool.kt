package com.gaee.tools

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import com.gaee.model.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.coroutines.resume

class WeatherTool(private val context: Context) : BaseTool {
    override val name = "WeatherTool"

    // Loaded from gaee/.env at build time (see BuildConfig / build.gradle.kts)
    private val apiKey = com.gaee.BuildConfig.OPENWEATHERMAP_API_KEY
    private val client = OkHttpClient()

    override suspend fun execute(args: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        try {
            val location = resolveLocation()
            Log.d("WeatherTool", "location=$location keyLen=${apiKey.length}")
            if (location == null) {
                return@withContext ToolResult(false, "I could not find your location. Please check your location settings.")
            }

            val url = "https://api.openweathermap.org/data/2.5/weather" +
                    "?lat=${location.latitude}&lon=${location.longitude}" +
                    "&appid=$apiKey&units=metric"

            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string()
            Log.d("WeatherTool", "http=${response.code} body=${bodyStr?.take(300)}")

            if (!response.isSuccessful) {
                return@withContext ToolResult(false, "I could not get the weather right now. Please check again in a moment.")
            }

            val body = bodyStr
                ?: return@withContext ToolResult(false, "I could not get the weather right now. Please check again in a moment.")

            val json = JSONObject(body)
            val city = json.getString("name")
            val temp = json.getJSONObject("main").getDouble("temp").toInt()
            val description = json.getJSONArray("weather")
                .getJSONObject(0).getString("description")

            ToolResult(true, "Today in $city: $description, $temp degrees Celsius.")
        } catch (e: Exception) {
            Log.e("WeatherTool", "weather failed", e)
            ToolResult(false, "I could not get the weather right now. Please check again in a moment.", e.message)
        }
    }

    /**
     * Returns a location. Prefers a cached fix, but modern Android often withholds a cached fix
     * from an app that hasn't actively requested location — so we fall back to getCurrentLocation
     * (API 30+, uses the fused/GMS provider) or a single active update on older devices. Bounded
     * by a timeout so it never hangs.
     */
    private suspend fun resolveLocation(): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }

        // 1. Best cached fix across providers (may be null even when one exists — see above).
        try {
            providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.let { return it }
        } catch (e: SecurityException) {
            return null
        }

        // 2. Actively fetch a fresh fix.
        val provider = providers.firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return null

        return withTimeoutOrNull(8000L) {
            suspendCancellableCoroutine<Location?> { cont ->
                try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        val cancel = CancellationSignal()
                        lm.getCurrentLocation(provider, cancel, context.mainExecutor) { loc ->
                            if (cont.isActive) cont.resume(loc)
                        }
                        cont.invokeOnCancellation { cancel.cancel() }
                    } else {
                        val listener = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                lm.removeUpdates(this)
                                if (cont.isActive) cont.resume(location)
                            }
                            override fun onProviderDisabled(provider: String) {}
                            override fun onProviderEnabled(provider: String) {}
                            @Deprecated("deprecated in API 29")
                            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        }
                        lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                        cont.invokeOnCancellation { lm.removeUpdates(listener) }
                    }
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }
}
