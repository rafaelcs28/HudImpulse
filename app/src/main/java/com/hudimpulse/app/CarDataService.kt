package com.hudimpulse.app

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.beantechs.intelligentvehiclecontrol.IIntelligentVehicleControlService
import com.beantechs.intelligentvehiclecontrol.sdk.IListener
import rikka.shizuku.ShizukuBinderWrapper

/**
 * Background service that reads car speed, TSR speed limit and EV energy from the Beantechs
 * IIntelligentVehicleControlService (GWM/Haval proprietary AIDL, accessed via Shizuku).
 *
 * Broadcasts ACTION_CAR_DATA with EXTRA_SPEED_KMH, EXTRA_SPEED_LIMIT_KMH e/ou EXTRA_ENERGY_PERCENT.
 * EXTRA_SPEED_LIMIT_KMH: int, limit from car.map.tsr.nav_speed_limit (0 = unknown).
 * EXTRA_ENERGY_PERCENT: float, positivo = consumo %, negativo = regeneração %.
 * Retries connection every 5s until Beantechs service is available.
 */
class CarDataService : Service() {

    private var controlService: IIntelligentVehicleControlService? = null
    private var dataListener: IListener? = null
    private val retryHandler = Handler(Looper.getMainLooper())
    private var connected = false

    private val retryRunnable = Runnable { connectToBeantechs() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        connectToBeantechs()
        SpeedLimitFetcher.start(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        retryHandler.removeCallbacks(retryRunnable)
        SpeedLimitFetcher.stop(this)
        try {
            dataListener?.let { controlService?.unRegisterDataChangedListener(packageName, it) }
        } catch (_: RemoteException) {}
    }

    private fun connectToBeantechs() {
        if (connected) return
        try {
            val getService = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
            val rawBinder = getService.invoke(null, SERVICE_NAME) as? IBinder
                ?: throw Exception("Beantechs service not found")
            val binder = ShizukuBinderWrapper(rawBinder)
            if (!binder.pingBinder()) throw Exception("Beantechs binder not alive")

            controlService = IIntelligentVehicleControlService.Stub.asInterface(binder)

            val listener = object : IListener.Stub() {
                override fun onDataChanged(key: String, value: String) {
                    handleDataChanged(key, value)
                }
            }
            dataListener = listener

            controlService?.addListenerKey(packageName, arrayOf(KEY_SPEED))
            controlService?.addListenerKey(packageName, arrayOf(KEY_ENERGY))
            controlService?.addListenerKey(packageName, arrayOf(KEY_ENGINE_RPM))
            // Registra todos os candidatos TSR como listeners — logcat mostrará qual dispara
            TSR_CANDIDATES.forEach { controlService?.addListenerKey(packageName, arrayOf(it)) }
            controlService?.registerDataChangedListener(packageName, listener)
            connected = true
            Log.i(TAG, "Connected to Beantechs service")
            // Consulta imediata das chaves candidatas para ver quais têm valor cached
            discoverTsrKey()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot connect to Beantechs yet: ${e.message} — retrying in ${RETRY_MS}ms")
            retryHandler.postDelayed(retryRunnable, RETRY_MS)
        }
    }

    /**
     * Parseia valor TSR que pode vir como "60" ou "{60,-2147483648}".
     * Retorna null se todos os valores forem Int.MIN_VALUE (sentinela = sem dado).
     */
    private fun parseTsrValue(raw: String): Int? {
        val cleaned = raw.trim().removePrefix("{").removeSuffix("}")
        return cleaned.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .firstOrNull { it != Int.MIN_VALUE && it > 0 }
    }

    private fun discoverTsrKey() {
        Log.i(TAG, "=== TSR KEY DISCOVERY ===")
        TSR_CANDIDATES.forEach { key ->
            try {
                val v = controlService?.fetchData(key)
                val parsed = v?.let { parseTsrValue(it) }
                Log.i(TAG, "fetchData[$key] = $v  →  parsed=$parsed")
            } catch (e: Exception) {
                Log.w(TAG, "fetchData[$key] error: ${e.message}")
            }
        }
        Log.i(TAG, "=== END DISCOVERY ===")
    }

    private fun handleDataChanged(key: String, value: String) {
        Log.d(TAG, "onDataChanged key=$key value=$value")   // log tudo — ajuda debug
        val intent = Intent(ACTION_CAR_DATA)
        when (key) {
            KEY_SPEED -> {
                val speed = value.toFloatOrNull()?.toInt() ?: return
                intent.putExtra(EXTRA_SPEED_KMH, speed)
                sendBroadcast(intent)
            }
            KEY_ENERGY -> {
                // Positivo = consumo motor (%), negativo = regeneração (%)
                val percent = value.toFloatOrNull() ?: run {
                    Log.w(TAG, "Energy: cannot parse '$value'"); return
                }
                Log.i(TAG, "Energy output: $percent%")
                intent.putExtra(EXTRA_ENERGY_PERCENT, percent)
                sendBroadcast(intent)
            }
            KEY_ENGINE_RPM -> {
                // A chave retorna ex.: 2.0 (não 2000) — multiplica por 1000
                val rpm = ((value.toFloatOrNull() ?: return) * 1000).toInt()
                intent.putExtra(EXTRA_ENGINE_RPM, rpm)
                sendBroadcast(intent)
            }
            in TSR_CANDIDATES -> {
                Log.i(TAG, "TSR hit! key=$key value=$value")
                // Chaves de status/tipo/distância são informativas — não contêm limite
                if (key != "car.map.tsr.nav_speed_limit" &&
                    key != "car.map.tsr.nav_trafic_sign") return
                // Formato pode ser simples "60" ou par "{60,-2147483648}"
                // -2147483648 (Int.MIN_VALUE) = sentinela Beantechs para "sem dado"
                val limit = parseTsrValue(value) ?: run {
                    Log.d(TAG, "TSR: sem dado válido em '$value'"); return
                }
                Log.i(TAG, "Speed limit: $limit km/h via $key")
                intent.putExtra(EXTRA_SPEED_LIMIT_KMH, limit)
                sendBroadcast(intent)
            }
            else -> Log.i(TAG, "unknown key=$key value=$value")
        }
    }

    companion object {
        const val ACTION_CAR_DATA       = "com.hudimpulse.app.CAR_DATA"
        const val EXTRA_SPEED_KMH       = "speed_kmh"
        const val EXTRA_ENERGY_PERCENT  = "energy_percent"    // float: >0 consumo, <0 regen
        const val EXTRA_SPEED_LIMIT_KMH = "speed_limit_kmh"  // int: limite da via (0 = desconhecido)
        const val EXTRA_ENGINE_RPM      = "engine_rpm"        // int: RPM do motor ICE (0 = motor desligado)

        private const val KEY_SPEED       = "car.basic.vehicle_speed"
        private const val KEY_ENERGY      = "car.ev_info.energy_output_percentage"
        private const val KEY_ENGINE_RPM  = "car.basic.engine_speed"
        private const val SERVICE_NAME    = "com.beantechs.intelligentvehiclecontrol"
        private const val RETRY_MS        = 5_000L
        private const val TAG             = "CarDataService"

        // Chaves TSR confirmadas via CarConstants do carro.
        // nav_speed_limit é a chave principal; as demais são companheiras que
        // indicam status, tipo e sinal completo — todas monitoradas para diagnóstico.
        val TSR_CANDIDATES = arrayOf(
            "car.map.tsr.nav_speed_limit",
            "car.map.tsr.nav_speed_limit_sign_status",
            "car.map.tsr.nav_speed_limit_type",
            "car.map.tsr.nav_trafic_sign",
            "car.map.tsr.nav_to_traffic_eye_distance",
            "car.map.tsr.nav_road_type",
            "car.map.tsr.nav_contry_type"
        )
    }
}
