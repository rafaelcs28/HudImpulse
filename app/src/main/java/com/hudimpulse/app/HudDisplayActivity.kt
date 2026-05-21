package com.hudimpulse.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * HUD display activity — 480×240 px window, black background.
 * BLACK = invisible on HUD projectors, so only colored content shows on glass.
 *
 * Layout:
 *  ┌──────────────────────────┬──────────────────────────┐  480px
 *  │         LEFT (240px)     │        RIGHT (240px)     │
 *  │   ┌────────────────┐     │                          │
 *  │   │   seta grande  │     │           92             │
 *  │   │   (168px tall) │     │                          │
 *  │   └────────────────┘     │      km/h ╭──╮           │
 *  │       300 m              │           │80│           │
 *  │   Av. Marginal Pinheiros │           ╰──╯           │
 *  └──────────────────────────┴──────────────────────────┘  240px
 *
 * Right panel: velocidade centralizada (cor por % acima do limite) +
 * linha "km/h [placa]" logo abaixo. Arco de energia ao redor.
 */
class HudDisplayActivity : AppCompatActivity(), NavigationReceiver.NavigationListener {

    // ── Força 160 dpi independente do display — garante que 1px = 1px ──
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.densityDpi = 160
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    // ── Left panel views ──
    private lateinit var leftPanel: LinearLayout
    private lateinit var arrowView: ImageView
    private lateinit var arrivedText: TextView
    private lateinit var distanceText: TextView
    private lateinit var streetText: TextView

    // ── Right panel view ──
    private lateinit var speedPanel: SpeedPanelView

    private lateinit var prefs: HudPreferences

    private var currentNavData: NavData? = null
    private var lastRoad: String = ""

    // ── Timers ──
    private val clearHandler  = Handler(Looper.getMainLooper())
    private val clearRunnable = Runnable { leftPanel.visibility = View.GONE }

    // ── Size constants (pixels at 160dpi HUD — 1dp = 1px) ──
    private val ARROW_HEIGHT_NORMAL = 97
    private val ARROW_SIZE_NORMAL   = 85
    private val DISTANCE_PX_NORMAL  = 23f
    private val STREET_PX_NORMAL    = 16f
    private val CLEAR_TIMEOUT_MS    = 15_000L

    // ── Receivers ──
    private val navReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != NavigationReceiver.ACTION) return

            // Dump all extras so we can discover the speed-limit key name in logcat
            intent.extras?.keySet()?.forEach { k ->
                android.util.Log.i("HudNav", "extra: $k = ${intent.extras?.get(k)}")
            }

            val road = intent.getStringExtra("road") ?: ""
            val data = NavData(
                distanceMeters = intent.getIntExtra("distance_meters", -1),
                timeSeconds    = intent.getIntExtra("time_seconds", -1),
                road           = road,
                nextEventType  = intent.getIntExtra("next_event_type", 0),
                actionText     = intent.getStringExtra("action_text") ?: "",
                turnSide       = intent.getIntExtra("turn_side", 3),
                turnNumber     = intent.getIntExtra("turn_number", -1),
                turnAngle      = intent.getIntExtra("turn_angle", -1)
            )
            onNavigationUpdate(data)
            // Consulta imediata ao trocar de rua — não espera 100m
            if (road.isNotBlank() && road != lastRoad) {
                lastRoad = road
                SpeedLimitFetcher.forceQuery(this@HudDisplayActivity)
            }
            // Speed limit do Waze via headunit-revived (prioridade sobre Beantechs quando disponível)
            val navLimit = intent.getIntExtra("speed_limit_kmh", -1)
            if (navLimit in 1..300) speedPanel.limitKmh = navLimit
        }
    }

    private val carDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != CarDataService.ACTION_CAR_DATA) return
            if (intent.hasExtra(CarDataService.EXTRA_SPEED_KMH)) {
                val raw = intent.getIntExtra(CarDataService.EXTRA_SPEED_KMH, 0)
                // Cluster do carro mostra ~7% acima da velocidade real (GWM/Haval)
                val adjusted = raw * 1.07 - raw / 180.0 * 0.02
                speedPanel.speedKmh = adjusted.toInt()
            }
            if (intent.hasExtra(CarDataService.EXTRA_ENERGY_PERCENT)) {
                speedPanel.energyPercent = intent.getFloatExtra(CarDataService.EXTRA_ENERGY_PERCENT, 0f)
            }
            if (intent.hasExtra(CarDataService.EXTRA_SPEED_LIMIT_KMH)) {
                val limit = intent.getIntExtra(CarDataService.EXTRA_SPEED_LIMIT_KMH, 0)
                speedPanel.limitKmh = if (limit > 0) limit else -1
                speedPanel.limitSource = intent.getStringExtra(CarDataService.EXTRA_SPEED_LIMIT_SOURCE)
                    ?: CarDataService.SOURCE_HERE
            }
            if (intent.hasExtra(CarDataService.EXTRA_ENGINE_RPM)) {
                speedPanel.engineRpm = intent.getIntExtra(CarDataService.EXTRA_ENGINE_RPM, -1)
            }
            if (intent.hasExtra(CarDataService.EXTRA_HERE_STATUS)) {
                speedPanel.hereStatus = intent.getStringExtra(CarDataService.EXTRA_HERE_STATUS) ?: ""
            }
        }
    }

    // ── Lifecycle ──
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = HudPreferences(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setLayout(480, 240)
        buildUi()
        startService(Intent(this, CarDataService::class.java))
        UpdateManager.checkForUpdate(this)
    }

    override fun onStart() {
        super.onStart()
        NavigationReceiver.addListener(this)
        ContextCompat.registerReceiver(this, navReceiver,
            IntentFilter(NavigationReceiver.ACTION), ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(this, carDataReceiver,
            IntentFilter(CarDataService.ACTION_CAR_DATA), ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        NavigationReceiver.removeListener(this)
        try { unregisterReceiver(navReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(carDataReceiver) } catch (_: Exception) {}
        clearHandler.removeCallbacks(clearRunnable)
    }

    // ── UI ──
    private fun buildUi() {
        // Root: LinearLayout horizontal — sem overlay de barra
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
        }

        // ── Left panel: navigation arrow + text
        // topPadding=12 → sobe o bloco ~20px vs CENTER_VERTICAL puro (era ~32px do topo)
        leftPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            clipChildren = true
            clipToPadding = true
            setPadding(10, 12, 10, 0)
            setBackgroundColor(Color.BLACK)
        }

        arrowView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            // paddingTop empurra o bitmap (85px) até o fundo do view (97px)
            // garantindo 4px de gap visual entre seta e texto de distância
            setPadding(0, ARROW_HEIGHT_NORMAL - ARROW_SIZE_NORMAL, 0, 0)
            visibility = if (prefs.showArrow) View.VISIBLE else View.GONE
        }
        leftPanel.addView(arrowView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ARROW_HEIGHT_NORMAL
        ))

        arrivedText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        leftPanel.addView(arrivedText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        distanceText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, DISTANCE_PX_NORMAL)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            visibility = if (prefs.showDistance) View.VISIBLE else View.GONE
        }
        leftPanel.addView(distanceText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 4 })

        streetText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, STREET_PX_NORMAL)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.05f)  // espaçamento mínimo — evita overflow com 2 linhas
            visibility = if (prefs.showStreetName) View.VISIBLE else View.GONE
        }
        leftPanel.addView(streetText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 3 })

        leftPanel.visibility = View.GONE

        // ── Right panel: speed + limit sign + barra de energia vertical ──
        speedPanel = SpeedPanelView(this)

        // weight=1 em ambos: quando leftPanel está GONE, speedPanel ocupa os 480px
        root.addView(leftPanel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        root.addView(speedPanel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        setContentView(root)
    }

    // ── Navigation ──
    override fun onNavigationUpdate(data: NavData) {
        runOnUiThread {
            currentNavData = data

            leftPanel.visibility = View.VISIBLE
            clearHandler.removeCallbacks(clearRunnable)
            clearHandler.postDelayed(clearRunnable, CLEAR_TIMEOUT_MS)

            val isDestination = data.nextEventType == 17
            val hasArrived    = isDestination && data.distanceMeters in 0..30

            if (hasArrived) {
                arrowView.visibility    = View.GONE
                distanceText.visibility = View.GONE
                streetText.visibility   = View.GONE
                arrivedText.text        = "Chegou!"
                arrivedText.visibility  = View.VISIBLE
                return@runOnUiThread
            }

            arrivedText.visibility = View.GONE

            if (prefs.showArrow && data.nextEventType >= 0) {
                arrowView.setImageBitmap(ArrowDrawer.drawArrow(
                    eventType  = data.nextEventType,
                    turnSide   = data.turnSide,
                    size       = ARROW_SIZE_NORMAL,
                    color      = Color.WHITE,
                    turnAngle  = data.turnAngle,
                    turnNumber = data.turnNumber
                ))
                arrowView.visibility = View.VISIBLE
            }

            if (prefs.showDistance && data.distanceMeters >= 0) {
                distanceText.text       = formatDistance(data.distanceMeters)
                distanceText.visibility = View.VISIBLE
            }

            if (prefs.showStreetName && data.road.isNotBlank() && data.road != "—") {
                streetText.text       = data.road
                streetText.visibility = View.VISIBLE
            }

        }
    }

    private fun formatDistance(meters: Int): String {
        return when {
            meters >= 1000 -> {
                val km = meters / 1000f
                if (km >= 10) "${km.toInt()} km" else String.format("%.1f km", km)
            }
            else -> "$meters m"
        }
    }

    // ── Speed panel (right half: 240×240) — Layout C: duplo arco RPM+energia ──
    //
    //  Arco externo (maior raio): RPM do motor ICE — varre de 9h→3h pelo topo
    //  Arco interno (menor raio): energia EV — topo=consumo, baixo=regen
    //  Círculo fino estático: borda decorativa ao redor da velocidade
    //
    private inner class SpeedPanelView(context: Context) : View(context) {

        var speedKmh: Int = 0
            set(v) { field = v; invalidate() }
        var limitKmh: Int = -1
            set(v) { field = v; invalidate() }
        var limitSource: String = CarDataService.SOURCE_HERE
            set(v) { field = v; invalidate() }
        var energyPercent: Float = 0f
            set(v) { field = v.coerceIn(-100f, 100f); invalidate() }
        var engineRpm: Int = -1
            set(v) { field = v; invalidate() }
        var hereStatus: String = ""
            set(v) { field = v; invalidate() }

        // ── Paints ──
        private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface  = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color     = Color.argb(165, 255, 255, 255)
            typeface  = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
        private val circleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        }
        private val circleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC0000"); style = Paint.Style.STROKE
        }
        private val limitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        // Arco interno — energia EV
        private val energyArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap   = Paint.Cap.ROUND
        }
        // Labels pequenos nas extremidades dos arcos
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface  = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        private val energyArcRect = RectF()
        private val MAX_RPM = 6000

        init { setBackgroundColor(Color.BLACK) }

        override fun onDraw(canvas: Canvas) {
            val w  = width.toFloat()
            val h  = height.toFloat()
            val cx = w / 2f

            val over = if (limitKmh > 0) speedKmh - limitKmh else Int.MIN_VALUE

            // ── Centro visual dos arcos (ligeiramente acima do centro geométrico) ──
            val speedCenterY = h / 2f - 16f   // bloco sobe 8px a mais
            val regenCenterY = h / 2f - 8f    // arco de regen não sobe

            // ── Arco de energia EV — mesmo raio nos dois sentidos para abertura idêntica ──
            // Consumo (+): 9h+offset → topo → 3h  (horário)
            // Regen  (-): 9h-offset → baixo → 3h  (anti-horário)
            // Com RPM visível: offset=30°, sweep=150° (arco começa mais baixo, dá espaço ao label)
            // Sem RPM:         offset=15°, sweep=165°
            val energyArcR = h * 0.30f   // usado só para posição do label %
            val arcR = h * 0.28f         // mesmo raio para consumo e regen
            if (energyPercent != 0f) {
                val abs      = kotlin.math.abs(energyPercent)
                val offset   = if (engineRpm > 0) 30f else 15f
                val maxSweep = if (engineRpm > 0) 150f else 165f
                val sweep    = (abs / 100f) * maxSweep
                val energyColor = when {
                    energyPercent < 0f  -> Color.parseColor("#52B788")
                    energyPercent > 75f -> Color.parseColor("#C05555")
                    energyPercent > 50f -> Color.parseColor("#C87941")
                    energyPercent > 30f -> Color.parseColor("#C9A84C")
                    else                -> Color.parseColor("#4A86C8")
                }
                energyArcPaint.color = energyColor
                energyArcPaint.alpha = 210
                if (energyPercent > 0f) {
                    energyArcRect.set(cx - arcR, speedCenterY - arcR, cx + arcR, speedCenterY + arcR)
                    canvas.drawArc(energyArcRect, 180f + offset, sweep, false, energyArcPaint)
                } else {
                    energyArcRect.set(cx - arcR, regenCenterY - arcR, cx + arcR, regenCenterY + arcR)
                    canvas.drawArc(energyArcRect, 180f - offset, -sweep, false, energyArcPaint)
                }

                // % energia na posição 9h
                labelPaint.textSize  = h * 0.085f
                labelPaint.color     = energyColor
                labelPaint.alpha     = 190
                labelPaint.textAlign = Paint.Align.RIGHT
                val eLabelFm  = labelPaint.fontMetrics
                val eLabelStr = if (energyPercent > 0f) "▲ ${abs.toInt()}%" else "▼ ${abs.toInt()}%"
                canvas.drawText(eLabelStr,
                    cx - energyArcR + 4f,
                    speedCenterY - 2f - (eLabelFm.ascent + eLabelFm.descent) / 2f,
                    labelPaint)
                labelPaint.textAlign = Paint.Align.CENTER
            }

            // ── RPM label empilhado abaixo de energia % na posição 9h ──
            if (engineRpm > 0) {
                val rpmFraction = (engineRpm.coerceIn(0, MAX_RPM) / MAX_RPM.toFloat())
                val rpmColor = when {
                    rpmFraction > 0.83f -> Color.parseColor("#C05555")
                    rpmFraction > 0.50f -> Color.parseColor("#C87941")
                    else                -> Color.parseColor("#4A86C8")
                }
                labelPaint.textSize  = h * 0.070f
                labelPaint.color     = rpmColor
                labelPaint.alpha     = 190
                labelPaint.textAlign = Paint.Align.RIGHT
                val rpmFm  = labelPaint.fontMetrics
                val rpmStr = String.format("%.1f rpm", engineRpm / 1000f)
                canvas.drawText(rpmStr,
                    cx - energyArcR + 4f,
                    speedCenterY + h * 0.11f - 2f - (rpmFm.ascent + rpmFm.descent) / 2f,
                    labelPaint)
                labelPaint.textAlign = Paint.Align.CENTER
            }

            // ── Velocidade ──
            val speedTextCenterY = speedCenterY - 16f  // só velocidade + placa sobem 16px a mais
            speedPaint.color = when {
                over > 7  -> Color.RED
                over > 0  -> Color.YELLOW
                over >= 0 -> Color.parseColor("#4CAF50")  // verde: dentro do limite
                else      -> Color.WHITE                  // sem placa
            }
            speedPaint.textSize = if (speedKmh >= 100) h * 0.24f else h * 0.29f - 2f
            val speedFm = speedPaint.fontMetrics
            val speedY  = speedTextCenterY - (speedFm.ascent + speedFm.descent) / 2f
            canvas.drawText(speedKmh.toString(), cx, speedY, speedPaint)

            // ── Linha "km/h" + placa — posição fixa para não deslocar ao trocar de dígitos ──
            val signR = 15f
            val rowY  = speedTextCenterY + h * 0.195f
            unitPaint.textSize  = h * 0.074f
            unitPaint.textAlign = Paint.Align.LEFT
            val unitFm = unitPaint.fontMetrics
            val unitY  = rowY - (unitFm.ascent + unitFm.descent) / 2f

            if (limitKmh > 0) {
                // Bloco [placa] [km/h] centralizado em cx — funciona com e sem navegação
                val gap    = h * 0.022f
                val kmhW   = unitPaint.measureText("km/h")
                val groupW = signR * 2f + gap + kmhW
                val groupX = cx - groupW / 2f
                val signCx = groupX + signR
                canvas.drawCircle(signCx, rowY, signR, circleFill)
                circleBorder.strokeWidth = signR * 0.25f
                circleBorder.color = if (limitSource == CarDataService.SOURCE_TSR)
                    Color.parseColor("#CC0000")
                else
                    Color.parseColor("#4A86C8")
                canvas.drawCircle(signCx, rowY, signR, circleBorder)
                limitTextPaint.textSize = if (limitKmh >= 100) 14f else 16f
                val fm    = limitTextPaint.fontMetrics
                val textY = rowY - (fm.ascent + fm.descent) / 2f
                canvas.drawText(limitKmh.toString(), signCx, textY, limitTextPaint)
                canvas.drawText("km/h", groupX + signR * 2f + gap, unitY, unitPaint)
            } else {
                unitPaint.textAlign = Paint.Align.CENTER
                canvas.drawText("km/h", cx, unitY, unitPaint)
            }

            // ── Status HERE (diagnóstico, só quando sem placa) ──
            if (limitKmh <= 0 && hereStatus.isNotEmpty()) {
                labelPaint.textSize  = h * 0.055f
                labelPaint.textAlign = Paint.Align.CENTER
                labelPaint.color     = Color.parseColor("#FFCC00")
                labelPaint.alpha     = 200
                val statusFm = labelPaint.fontMetrics
                val statusY  = rowY + h * 0.075f - (statusFm.ascent + statusFm.descent) / 2f
                canvas.drawText(hereStatus, cx, statusY, labelPaint)
            }

        }
    }

    companion object
}
