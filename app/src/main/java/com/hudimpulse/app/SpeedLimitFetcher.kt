package com.hudimpulse.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object SpeedLimitFetcher {

    private const val TAG          = "SpeedLimit"
    private const val MIN_DISTANCE = 100f
    private const val MIN_TIME_MS  = 15_000L
    private const val TIMEOUT_MS   = 6_000

    private const val BASE_URL = "https://roads.ls.hereapi.com/roads/v1/speedlimits"

    private var appContext: Context? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = query(loc)
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) { LogForwarder.w(TAG, "provider disabled: $p") }
    }

    fun start(context: Context) {
        val keyLen = BuildConfig.HERE_API_KEY.length
        LogForwarder.i(TAG, "HERE_API_KEY length=$keyLen")

        if (keyLen == 0) {
            LogForwarder.w(TAG, "HERE_API_KEY not set — speed limit via GPS disabled")
            return
        }
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            LogForwarder.w(TAG, "ACCESS_FINE_LOCATION not granted")
            return
        }

        appContext = context.applicationContext
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val providers = lm.getProviders(true)
        LogForwarder.i(TAG, "Available providers: $providers")

        // Consulta imediata com última posição conhecida
        val last = providers.mapNotNull { p ->
            try { lm.getLastKnownLocation(p) } catch (_: Exception) { null }
        }.maxByOrNull { it.time }

        if (last != null) {
            LogForwarder.i(TAG, "Last known: ${last.latitude},${last.longitude} via ${last.provider}")
            query(last)
        } else {
            LogForwarder.w(TAG, "No last known location available")
        }

        // Registra em GPS + NETWORK
        var registered = 0
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (lm.isProviderEnabled(provider)) {
                try {
                    lm.requestLocationUpdates(provider, MIN_TIME_MS, MIN_DISTANCE, locationListener)
                    LogForwarder.i(TAG, "Listener registered: $provider")
                    registered++
                } catch (e: Exception) {
                    LogForwarder.w(TAG, "Cannot register $provider: ${e.message}")
                }
            } else {
                LogForwarder.w(TAG, "Provider disabled: $provider")
            }
        }
        if (registered == 0) LogForwarder.e(TAG, "No location provider available")
    }

    fun stop(context: Context) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.removeUpdates(locationListener)
        appContext = null
    }

    private fun query(loc: Location) {
        val key = BuildConfig.HERE_API_KEY
        Thread {
            try {
                val url = "$BASE_URL?path=${loc.latitude},${loc.longitude}&apiKey=$key"
                LogForwarder.d(TAG, "GET $url")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout    = TIMEOUT_MS
                conn.requestMethod  = "GET"

                val code = conn.responseCode
                if (code != 200) {
                    val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                    LogForwarder.w(TAG, "HTTP $code — $err")
                    conn.disconnect()
                    return@Thread
                }

                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                LogForwarder.i(TAG, "Response: $body")

                val limits = JSONObject(body).getJSONArray("speedLimits")
                if (limits.length() == 0) {
                    LogForwarder.d(TAG, "No speed limit at ${loc.latitude},${loc.longitude}")
                    return@Thread
                }

                val kmh = limits.getJSONObject(0).getInt("speedLimit")
                LogForwarder.i(TAG, "Speed limit: $kmh km/h")

                appContext?.sendBroadcast(
                    Intent(CarDataService.ACTION_CAR_DATA)
                        .putExtra(CarDataService.EXTRA_SPEED_LIMIT_KMH, kmh)
                        .putExtra(CarDataService.EXTRA_SPEED_LIMIT_SOURCE, CarDataService.SOURCE_HERE)
                )
            } catch (e: Exception) {
                LogForwarder.w(TAG, "Query failed: ${e.message}")
            }
        }.start()
    }
}
