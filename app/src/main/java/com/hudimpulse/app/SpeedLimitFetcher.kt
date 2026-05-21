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
    private const val BASE_URL     = "https://roads.ls.hereapi.com/roads/v1/speedlimits"

    private var appContext: Context? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = query(loc)
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) { LogForwarder.w(TAG, "provider disabled: $p") }
    }

    private fun broadcastStatus(ctx: Context, status: String) {
        LogForwarder.i(TAG, "status=$status")
        ctx.sendBroadcast(
            Intent(CarDataService.ACTION_CAR_DATA)
                .putExtra(CarDataService.EXTRA_HERE_STATUS, status)
        )
    }

    fun start(context: Context) {
        val keyLen = BuildConfig.HERE_API_KEY.length
        LogForwarder.i(TAG, "HERE_API_KEY length=$keyLen")

        if (keyLen == 0) {
            broadcastStatus(context, "SEM CHAVE")
            return
        }
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            broadcastStatus(context, "SEM PERMISSÃO")
            return
        }

        appContext = context.applicationContext
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        LogForwarder.i(TAG, "providers=$providers")

        if (providers.isEmpty()) {
            broadcastStatus(context, "SEM GPS")
            return
        }

        broadcastStatus(context, "GPS OK...")

        // Consulta imediata com última posição conhecida
        val last = providers.mapNotNull { p ->
            try { lm.getLastKnownLocation(p) } catch (_: Exception) { null }
        }.maxByOrNull { it.time }

        if (last != null) {
            LogForwarder.i(TAG, "last known: ${last.latitude},${last.longitude} via ${last.provider}")
            query(last)
        } else {
            LogForwarder.w(TAG, "no last known location")
            broadcastStatus(context, "AGUARDANDO GPS")
        }

        // Registra em todos os providers disponíveis
        var registered = 0
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (lm.isProviderEnabled(provider)) {
                try {
                    lm.requestLocationUpdates(provider, MIN_TIME_MS, MIN_DISTANCE, locationListener)
                    LogForwarder.i(TAG, "registered: $provider")
                    registered++
                } catch (e: Exception) {
                    LogForwarder.w(TAG, "cannot register $provider: ${e.message}")
                }
            }
        }
        if (registered == 0) broadcastStatus(context, "SEM PROVIDER")
    }

    fun stop(context: Context) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.removeUpdates(locationListener)
        appContext = null
    }

    private fun query(loc: Location) {
        val key = BuildConfig.HERE_API_KEY
        val ctx = appContext ?: return
        Thread {
            try {
                val url = "$BASE_URL?path=${loc.latitude},${loc.longitude}&apiKey=$key"
                LogForwarder.d(TAG, "GET lat=${loc.latitude} lon=${loc.longitude}")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout    = TIMEOUT_MS
                conn.requestMethod  = "GET"

                val code = conn.responseCode
                if (code != 200) {
                    val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { "" }
                    LogForwarder.w(TAG, "HTTP $code — $err")
                    broadcastStatus(ctx, "HERE HTTP $code")
                    conn.disconnect()
                    return@Thread
                }

                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                LogForwarder.i(TAG, "response: $body")

                val limits = JSONObject(body).getJSONArray("speedLimits")
                if (limits.length() == 0) {
                    broadcastStatus(ctx, "HERE: sem dado")
                    return@Thread
                }

                val kmh = limits.getJSONObject(0).getInt("speedLimit")
                LogForwarder.i(TAG, "limit: $kmh km/h")
                // Limpa o status — placa vai aparecer
                broadcastStatus(ctx, "")
                ctx.sendBroadcast(
                    Intent(CarDataService.ACTION_CAR_DATA)
                        .putExtra(CarDataService.EXTRA_SPEED_LIMIT_KMH, kmh)
                        .putExtra(CarDataService.EXTRA_SPEED_LIMIT_SOURCE, CarDataService.SOURCE_HERE)
                )
            } catch (e: Exception) {
                LogForwarder.w(TAG, "query failed: ${e.message}")
                broadcastStatus(ctx, "HERE: erro rede")
            }
        }.start()
    }
}
