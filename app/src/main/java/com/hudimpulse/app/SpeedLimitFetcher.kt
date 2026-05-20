package com.hudimpulse.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
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
        override fun onProviderDisabled(p: String) { Log.w(TAG, "provider disabled: $p") }
    }

    fun start(context: Context) {
        val keyLen = BuildConfig.HERE_API_KEY.length
        Log.i(TAG, "HERE_API_KEY length=$keyLen")
        if (keyLen == 0) {
            Log.w(TAG, "HERE_API_KEY not set — speed limit via GPS disabled")
            return
        }
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "ACCESS_FINE_LOCATION not granted")
            return
        }

        appContext = context.applicationContext
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Log provedores disponíveis para diagnóstico
        val providers = lm.getProviders(true)
        Log.i(TAG, "Available location providers: $providers")

        // Consulta imediata com última posição conhecida (não espera 100m)
        val last = providers.mapNotNull { p ->
            try { lm.getLastKnownLocation(p) } catch (_: Exception) { null }
        }.maxByOrNull { it.time }

        if (last != null) {
            Log.i(TAG, "Using last known location: ${last.latitude},${last.longitude} provider=${last.provider}")
            query(last)
        } else {
            Log.w(TAG, "No last known location available")
        }

        // Registra listener em todos os provedores disponíveis (GPS + rede)
        var registered = 0
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (lm.isProviderEnabled(provider)) {
                try {
                    lm.requestLocationUpdates(provider, MIN_TIME_MS, MIN_DISTANCE, locationListener)
                    Log.i(TAG, "Listener registered on $provider (min ${MIN_DISTANCE.toInt()}m / ${MIN_TIME_MS / 1000}s)")
                    registered++
                } catch (e: Exception) {
                    Log.w(TAG, "Could not register on $provider: ${e.message}")
                }
            } else {
                Log.w(TAG, "Provider not enabled: $provider")
            }
        }
        if (registered == 0) Log.e(TAG, "No location provider available — sign will not update")
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
                Log.d(TAG, "Querying: $url")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout    = TIMEOUT_MS
                conn.requestMethod  = "GET"

                val code = conn.responseCode
                if (code != 200) {
                    val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                    Log.w(TAG, "HERE HTTP $code — body: $err")
                    conn.disconnect()
                    return@Thread
                }

                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                Log.i(TAG, "HERE response: $body")

                val limits = JSONObject(body).getJSONArray("speedLimits")
                if (limits.length() == 0) {
                    Log.d(TAG, "No speed limit at (${loc.latitude}, ${loc.longitude})")
                    return@Thread
                }

                val kmh = limits.getJSONObject(0).getInt("speedLimit")
                Log.i(TAG, "Speed limit: $kmh km/h")

                appContext?.sendBroadcast(
                    Intent(CarDataService.ACTION_CAR_DATA)
                        .putExtra(CarDataService.EXTRA_SPEED_LIMIT_KMH, kmh)
                        .putExtra(CarDataService.EXTRA_SPEED_LIMIT_SOURCE, CarDataService.SOURCE_HERE)
                )
            } catch (e: Exception) {
                Log.w(TAG, "HERE query failed: ${e.message}")
            }
        }.start()
    }
}
