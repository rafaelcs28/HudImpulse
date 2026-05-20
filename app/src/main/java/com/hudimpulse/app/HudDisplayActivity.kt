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
            val data = NavData(
                distanceMeters = intent.getIntExtra("distance_meters", -1),
                timeSeconds    = intent.getIntExtra("time_seconds", -1),
                road           = intent.getStringExtra("road") ?: "",
                nextEventType  = intent.getIntExtra("next_event_type", 0),
                actionText     = intent.getStringExtra("action_text") ?: "",
                turnSide       = intent.getIntExtra("turn_side", 3),
                turnNumber     = intent.getIntExtra("turn_number", -1),
                turnAngle      = intent.getIntExtra("turn_angle", -1)
            )
            onNavigationUpdate(data)
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
            }
            if (intent.hasExtra(CarDataService.EXTRA_ENGINE_RPM)) {
                speedPanel.engineRpm = intent.getIntExtra(CarDataService.EXTRA_ENGINE_RPM, -1)
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
        var energyPercent: Float = 0f
            set(v) { field = v.coerceIn(-100f, 100f); invalidate() }
        var engineRpm: Int = -1
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
        // Arco externo — RPM motor ICE
        private val rpmArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = 8f
            strokeCap   = Paint.Cap.ROUND
        }
        // Círculo decorativo interno
        private val innerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color  = Color.argb(55, 255, 255, 255)
            style  = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        // Labels pequenos nas extremidades dos arcos
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface  = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        private val energyArcRect = RectF()
        private val rpmArcRect    = RectF()
        private val MAX_RPM = 6000

        init { setBackgroundColor(Color.BLACK) }

        override fun onDraw(canvas: Canvas) {
            val w  = width.toFloat()
            val h  = height.toFloat()
            val cx = w / 2f

            val overPct = if (limitKmh > 0 && speedKmh > limitKmh)
                (speedKmh - limitKmh) * 100f / limitKmh else 0f

            // ── Centro visual dos arcos (ligeiramente acima do centro geométrico) ──
            val speedCenterY = h / 2f - 15f

            // ── Círculo decorativo interno ──
            val innerR = h * 0.32f
            canvas.drawCircle(cx, speedCenterY, innerR, innerCirclePaint)

            // ── Arco interno: energia EV ──
            // Consumo (+): semicírculo superior, 9h → 3h sentido horário
            // Regen  (-): semicírculo inferior, 3h → 9h sentido horário
            val energyArcR = h * 0.33f
            if (energyPercent != 0f) {
                val abs   = kotlin.math.abs(energyPercent)
                val sweep = (abs / 100f) * 180f
                val energyColor = when {
                    energyPercent < 0f  -> Color.parseColor("#52B788")
                    energyPercent > 75f -> Color.parseColor("#C05555")
                    energyPercent > 50f -> Color.parseColor("#C87941")
                    energyPercent > 30f -> Color.parseColor("#C9A84C")
                    else                -> Color.parseColor("#4A86C8")
                }
                energyArcPaint.color = energyColor
                energyArcPaint.alpha = 210
                energyArcRect.set(cx - energyArcR, speedCenterY - energyArcR,
                                  cx + energyArcR, speedCenterY + energyArcR)
                if (energyPercent > 0f) {
                    canvas.drawArc(energyArcRect, 180f, sweep, false, energyArcPaint)
                } else {
                    canvas.drawArc(energyArcRect, 0f, sweep, false, energyArcPaint)
                }
            }

            // ── Arco externo: RPM motor ICE ──
            val rpmArcR = h * 0.40f
            if (engineRpm > 0) {
                val rpmFraction = (engineRpm.coerceIn(0, MAX_RPM) / MAX_RPM.toFloat())
                val rpmSweep    = rpmFraction * 180f
                val rpmColor = when {
                    rpmFraction > 0.83f -> Color.parseColor("#C05555")
                    rpmFraction > 0.50f -> Color.parseColor("#C87941")
                    else                -> Color.parseColor("#4A86C8")
                }
                rpmArcPaint.color = rpmColor
                rpmArcPaint.alpha = 230
                rpmArcRect.set(cx - rpmArcR, speedCenterY - rpmArcR,
                               cx + rpmArcR, speedCenterY + rpmArcR)
                canvas.drawArc(rpmArcRect, 180f, rpmSweep, false, rpmArcPaint)
            }

            // ── Velocidade ──
            speedPaint.color = when {
                overPct > 20f -> Color.RED
                overPct > 0f  -> Color.YELLOW
                else          -> Color.WHITE
            }
            speedPaint.textSize = h * 0.386f
            val speedFm = speedPaint.fontMetrics
            val speedY  = speedCenterY - (speedFm.ascent + speedFm.descent) / 2f
            canvas.drawText(speedKmh.toString(), cx, speedY, speedPaint)

            // ── Linha "km/h" + placa ──
            val rowY = speedCenterY + h * 0.22f
            val signR = 13f
            unitPaint.textSize = h * 0.074f
            val kmhW   = unitPaint.measureText("km/h")
            val gap    = h * 0.025f
            val totalW = if (limitKmh > 0) kmhW + gap + signR * 2f else kmhW
            val groupX = cx - totalW / 2f
            val unitFm = unitPaint.fontMetrics
            val unitY  = rowY - (unitFm.ascent + unitFm.descent) / 2f
            canvas.drawText("km/h", groupX, unitY, unitPaint)

            if (limitKmh > 0) {
                val signCx = groupX + kmhW + gap + signR
                canvas.drawCircle(signCx, rowY, signR, circleFill)
                circleBorder.strokeWidth = signR * 0.25f
                canvas.drawCircle(signCx, rowY, signR, circleBorder)
                limitTextPaint.textSize = if (limitKmh >= 100) 12f else 14f
                val fm    = limitTextPaint.fontMetrics
                val textY = rowY - (fm.ascent + fm.descent) / 2f
                canvas.drawText(limitKmh.toString(), signCx, textY, limitTextPaint)
            }

            // ── Labels: abaixo da placa de limite E abaixo do arco de regen ──
            labelPaint.textSize = h * 0.065f
            val labelFm      = labelPaint.fontMetrics
            val labelGap     = h * 0.058f
            val labelStartY  = maxOf(rowY + signR * 2f + 8f, speedCenterY + energyArcR + 12f)
            val labelBaseline = labelStartY - (labelFm.ascent + labelFm.descent) / 2f
            var labelY = labelBaseline

            if (engineRpm > 0) {
                val rpmFraction = (engineRpm.coerceIn(0, MAX_RPM) / MAX_RPM.toFloat())
                val rpmColor = when {
                    rpmFraction > 0.83f -> Color.parseColor("#C05555")
                    rpmFraction > 0.50f -> Color.parseColor("#C87941")
                    else                -> Color.parseColor("#4A86C8")
                }
                val rpmStr = if (engineRpm >= 1000) "${engineRpm / 100 * 100}" else "$engineRpm"
                labelPaint.color = rpmColor
                labelPaint.alpha = 190
                canvas.drawText("$rpmStr rpm", cx, labelY, labelPaint)
                labelY += labelGap
            }

            if (energyPercent != 0f) {
                val abs = kotlin.math.abs(energyPercent)
                val energyColor = when {
                    energyPercent < 0f  -> Color.parseColor("#52B788")
                    energyPercent > 75f -> Color.parseColor("#C05555")
                    energyPercent > 50f -> Color.parseColor("#C87941")
                    energyPercent > 30f -> Color.parseColor("#C9A84C")
                    else                -> Color.parseColor("#4A86C8")
                }
                val energyLabel = if (energyPercent > 0f) "▲ ${abs.toInt()}%" else "▼ ${abs.toInt()}%"
                labelPaint.color = energyColor
                labelPaint.alpha = 190
                canvas.drawText(energyLabel, cx, labelY, labelPaint)
            }
        }
    }

    companion object
}
