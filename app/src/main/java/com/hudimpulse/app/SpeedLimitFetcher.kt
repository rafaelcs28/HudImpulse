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
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Fetches road speed limits from HERE Routing API v8.
 * Endpoint: https://router.hereapi.com/v8/routes
 * Speed limit returned in m/s → converted to km/h.
 */
object SpeedLimitFetcher {

    private const val TAG          = "SpeedLimit"
    private const val MIN_DISTANCE = 50f
    private const val MIN_TIME_MS  = 15_000L
    private const val TIMEOUT_MS   = 8_000

    // HERE Routing v8 — substituto do roads.ls.hereapi.com (desativado)
    private const val BASE_URL = "https://router.hereapi.com/v8/routes"

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
            LogForwarder.i(TAG, "last known: ${last.latitude},${last.longitude}")
            query(last)
        } else {
            broadcastStatus(context, "AGUARDANDO GPS")
        }

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

    /** Consulta imediata ao trocar de rua — ignora o threshold de 100m. */
    fun forceQuery(context: Context) {
        if (BuildConfig.HERE_API_KEY.isEmpty()) return
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return
        appContext = appContext ?: context.applicationContext
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val last = lm.getProviders(true).mapNotNull { p ->
            try { lm.getLastKnownLocation(p) } catch (_: Exception) { null }
        }.maxByOrNull { it.time }
        if (last != null) {
            LogForwarder.i(TAG, "forceQuery on street change: ${last.latitude},${last.longitude}")
            query(last)
        }
    }

    private fun query(loc: Location) {
        val key = BuildConfig.HERE_API_KEY
        val ctx = appContext ?: return
        Thread {
            try {
                // Destino com pequeno offset norte (~110m) — Routing v8 exige dois pontos
                val dest = String.format(Locale.US, "%.6f,%.6f", loc.latitude + 0.001, loc.longitude)
                val url  = "$BASE_URL?transportMode=car" +
                           "&origin=${String.format(Locale.US, "%.6f,%.6f", loc.latitude, loc.longitude)}" +
                           "&destination=$dest" +
                           "&return=polyline&spans=speedLimit" +
                           "&apiKey=$key"

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

                val routes   = JSONObject(body).getJSONArray("routes")
                if (routes.length() == 0) { broadcastStatus(ctx, "HERE: sem rota"); return@Thread }
                val sections = routes.getJSONObject(0).getJSONArray("sections")
                if (sections.length() == 0) { broadcastStatus(ctx, "HERE: sem seção"); return@Thread }
                val spans    = sections.getJSONObject(0).getJSONArray("spans")
                if (spans.length() == 0) { broadcastStatus(ctx, "HERE: sem span"); return@Thread }

                val speedMs = spans.getJSONObject(0).optDouble("speedLimit", -1.0)
                if (speedMs <= 0) {
                    broadcastStatus(ctx, "HERE: sem limite")
                    return@Thread
                }

                val kmh = (speedMs * 3.6).roundToInt()
                LogForwarder.i(TAG, "speed limit: $kmh km/h (${speedMs} m/s)")
                broadcastStatus(ctx, "")   // limpa status — placa vai aparecer
                ctx.sendBroadcast(
                    Intent(CarDataService.ACTION_CAR_DATA)
                        .putExtra(CarDataService.EXTRA_SPEED_LIMIT_KMH, kmh)
                )
            } catch (e: Exception) {
                val msg = (e.message ?: e.javaClass.simpleName).take(28)
                LogForwarder.w(TAG, "query failed: $msg")
                broadcastStatus(ctx, msg)
            }
        }.start()
    }
}
