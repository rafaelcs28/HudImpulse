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

/**
 * Fetches road speed limits from HERE Roads API v1 using GPS position.
 * Queries only when the car has moved ≥ MIN_DISTANCE_M since the last call
 * to avoid burning through the 250k/month free-tier quota.
 *
 * API key: add `here.api.key=XXX` to local.properties (dev) or set the
 * HERE_API_KEY environment variable for CI/release builds.
 * Register at developer.here.com → Projects → API Keys.
 */
object SpeedLimitFetcher {

    private const val TAG           = "SpeedLimit"
    private const val MIN_DISTANCE  = 100f      // metres between queries
    private const val MIN_TIME_MS   = 15_000L   // minimum 15 s between GPS callbacks
    private const val TIMEOUT_MS    = 6_000

    // HERE Roads v1 — returns speedLimit in km/h for Brazil
    private const val BASE_URL = "https://roads.ls.hereapi.com/roads/v1/speedlimits"

    private var appContext: Context? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = query(loc)
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {
            Log.w(TAG, "GPS provider disabled: $p")
        }
    }

    fun start(context: Context) {
        if (BuildConfig.HERE_API_KEY.isEmpty()) {
            Log.w(TAG, "HERE_API_KEY not set — speed limit via GPS disabled")
            return
        }
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "ACCESS_FINE_LOCATION not granted — speed limit via GPS disabled")
            return
        }
        appContext = context.applicationContext
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_TIME_MS,
                MIN_DISTANCE,
                locationListener
            )
            Log.i(TAG, "GPS listener registered (min ${MIN_DISTANCE.toInt()}m / ${MIN_TIME_MS/1000}s)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not register GPS listener: ${e.message}")
        }
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
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout    = TIMEOUT_MS
                conn.requestMethod  = "GET"

                val code = conn.responseCode
                if (code != 200) {
                    Log.w(TAG, "HERE HTTP $code at (${loc.latitude}, ${loc.longitude})")
                    conn.disconnect()
                    return@Thread
                }

                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                Log.d(TAG, "HERE response: $body")

                val limits = JSONObject(body).getJSONArray("speedLimits")
                if (limits.length() == 0) {
                    Log.d(TAG, "No speed limit data at (${loc.latitude}, ${loc.longitude})")
                    return@Thread
                }

                val kmh = limits.getJSONObject(0).getInt("speedLimit")
                Log.i(TAG, "Speed limit: $kmh km/h")

                appContext?.sendBroadcast(
                    Intent(CarDataService.ACTION_CAR_DATA).putExtra(
                        CarDataService.EXTRA_SPEED_LIMIT_KMH, kmh
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "HERE query failed: ${e.message}")
            }
        }.start()
    }
}
