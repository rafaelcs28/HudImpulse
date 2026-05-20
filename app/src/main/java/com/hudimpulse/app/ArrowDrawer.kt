package com.hudimpulse.app

import android.graphics.*
import kotlin.math.abs

/**
 * Draws navigation arrows using Canvas/Path.
 * All arrows are geometric and clean for HUD readability.
 */
object ArrowDrawer {

    /**
     * Draw a navigation arrow into a Bitmap.
     * @param eventType maneuver type (0-17)
     * @param turnSide 1=LEFT, 2=RIGHT, 3=UNSPECIFIED
     * @param size bitmap size in pixels
     * @param color arrow color
     * @param turnAngle angle in degrees (-180..180, negative=left, positive=right), -1 if unavailable
     * @param turnNumber roundabout exit number, -1 if unavailable
     */
    fun drawArrow(
        eventType: Int,
        turnSide: Int,
        size: Int,
        color: Int,
        turnAngle: Int = -1,
        turnNumber: Int = -1
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
            strokeWidth = size * 0.06f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val cx = size / 2f
        val cy = size * 0.55f  // levemente abaixo do centro → aproxima base da seta do texto de distância

        // Resolve the effective maneuver using turnAngle when eventType is ambiguous.
        // Headunit Revived sometimes sends STRAIGHT (14) for slight curves.
        val effective = resolveEventType(eventType, turnSide, turnAngle)

        // r controla o tamanho da seta dentro do bitmap (size×size).
        // Reduzido ~10% para setas menores sem alterar o bitmap.
        val r = size * when (effective) {
            1, 2, 14, 15, 16 -> 0.30f  // retas
            3, 7, 8          -> 0.32f  // curvas leves
            else             -> 0.36f  // curvas normais, acentuadas, rotatória
        }

        // Resolve side: prefer turnSide if explicit, otherwise infer from turnAngle sign
        val resolvedSide = when {
            turnSide == 1 || turnSide == 2 -> turnSide
            turnAngle > 0   -> 2   // positive angle = right
            turnAngle < -1  -> 1   // negative angle = left (-1 é sentinela "não disponível", ignorado)
            else            -> 2   // fallback right (inclui sentinela -1 e ângulo zero)
        }

        when (effective) {
            0 -> drawUnknown(canvas, paint, cx, cy, r)                        // UNKNOWN
            1 -> drawStraight(canvas, paint, cx, cy, r)                        // DEPART
            2 -> drawStraight(canvas, paint, cx, cy, r)                        // NAME_CHANGE
            3 -> drawSlightTurn(canvas, paint, cx, cy, r, resolvedSide)        // SLIGHT_TURN
            4 -> drawTurn(canvas, paint, cx, cy, r, resolvedSide)              // TURN
            5 -> drawSharpTurn(canvas, paint, cx, cy, r, resolvedSide)         // SHARP_TURN
            6 -> drawUturn(canvas, paint, cx, cy, r, resolvedSide)             // UTURN
            7 -> drawSlightTurn(canvas, paint, cx, cy, r, resolvedSide)        // ON_RAMP
            8 -> drawSlightTurn(canvas, paint, cx, cy, r, resolvedSide)        // OFF_RAMP
            9 -> drawFork(canvas, paint, cx, cy, r, resolvedSide)              // FORK
            10 -> drawMerge(canvas, paint, cx, cy, r, resolvedSide)            // MERGE
            11 -> drawRoundabout(canvas, paint, cx, cy, r, turnNumber)         // ROUNDABOUT_ENTER
            12 -> drawRoundabout(canvas, paint, cx, cy, r, turnNumber)         // ROUNDABOUT_EXIT
            13 -> drawRoundabout(canvas, paint, cx, cy, r, turnNumber)         // ROUNDABOUT_ENTER_AND_EXIT
            14 -> drawStraight(canvas, paint, cx, cy, r)                       // STRAIGHT
            15 -> drawStraight(canvas, paint, cx, cy, r)                       // FERRY_BOAT
            16 -> drawStraight(canvas, paint, cx, cy, r)                       // FERRY_TRAIN
            17 -> drawDestination(canvas, paint, cx, cy, r)                    // DESTINATION
            else -> drawStraight(canvas, paint, cx, cy, r)
        }

        return bitmap
    }

    /**
     * Resolves the effective event type.
     *
     * 1. Se temos um ângulo válido e o evento é ambíguo (UNKNOWN, NAME_CHANGE, STRAIGHT),
     *    sobrepõe pelo ângulo.
     * 2. Se o evento é STRAIGHT mas turnSide é explicitamente LEFT ou RIGHT,
     *    é uma saída lateral — desenha slight turn no lado correto.
     * 3. Outros eventos específicos (FORK, OFF_RAMP, etc.) são preservados.
     */
    private fun resolveEventType(eventType: Int, turnSide: Int, turnAngle: Int): Int {
        val absAngle = abs(turnAngle)

        // Passo 1: sobreposição por ângulo em eventos ambíguos
        if (absAngle > 0 && eventType in listOf(0, 2, 14)) {
            return when {
                absAngle < 20  -> if (turnSide == 1 || turnSide == 2) 3 else 14 // lado explícito → slight
                absAngle < 50  -> 3   // slight curve
                absAngle < 110 -> 4   // normal turn
                else           -> 5   // sharp turn
            }
        }

        // Passo 2: STRAIGHT com lado explícito = saída lateral → slight turn
        if (eventType == 14 && (turnSide == 1 || turnSide == 2)) {
            return 3
        }

        return eventType
    }

    private fun drawStraight(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float) {
        val path = Path()
        // Arrow pointing up
        path.moveTo(cx, cy - r)                     // top point
        path.lineTo(cx - r * 0.4f, cy - r * 0.3f)  // left wing
        path.lineTo(cx - r * 0.15f, cy - r * 0.3f)
        path.lineTo(cx - r * 0.15f, cy + r)         // left body
        path.lineTo(cx + r * 0.15f, cy + r)         // right body
        path.lineTo(cx + r * 0.15f, cy - r * 0.3f)
        path.lineTo(cx + r * 0.4f, cy - r * 0.3f)  // right wing
        path.close()
        c.drawPath(path, p)
    }

    private fun drawTurn(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, side: Int) {
        val path = Path()
        val flip = if (side == 1) -1f else 1f // LEFT = -1, RIGHT = 1

        // Arrow pointing right (or left if flipped)
        path.moveTo(cx + flip * r, cy)                      // tip
        path.lineTo(cx + flip * r * 0.3f, cy - r * 0.4f)   // top wing
        path.lineTo(cx + flip * r * 0.3f, cy - r * 0.15f)
        path.lineTo(cx - flip * r * 0.3f, cy - r * 0.15f)  // bend
        path.lineTo(cx - flip * r * 0.3f, cy + r)           // stem down
        path.lineTo(cx - flip * r * 0.0f, cy + r)
        path.lineTo(cx - flip * r * 0.0f, cy + r * 0.15f)
        path.lineTo(cx + flip * r * 0.3f, cy + r * 0.15f)
        path.lineTo(cx + flip * r * 0.3f, cy + r * 0.4f)   // bottom wing
        path.close()
        c.drawPath(path, p)
    }

    private fun drawSlightTurn(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, side: Int) {
        val flip = if (side == 1) -1f else 1f

        // Stem curvo: sai reto do fundo e curva suavemente para o lado
        val strokePaint = Paint(p).apply {
            style      = Paint.Style.STROKE
            strokeWidth = r * 0.22f
            strokeCap  = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val tipX  = cx + flip * r * 0.45f
        val tipY  = cy - r * 0.72f
        val ctrlX = cx
        val ctrlY = cy - r * 0.25f

        val stemPath = Path()
        stemPath.moveTo(cx, cy + r)
        stemPath.quadTo(ctrlX, ctrlY, tipX, tipY)
        c.drawPath(stemPath, strokePaint)

        // Ponta de seta alinhada com a tangente da curva no ponto final
        // Tangente no fim de uma quadrática = direção (end − control)
        val dx  = tipX - ctrlX
        val dy  = tipY - ctrlY
        val len = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        val tx  = dx / len;  val ty  = dy / len   // tangente unitária
        val px  = -ty;       val py  = tx          // perpendicular

        val hs = r * 0.28f
        val headPaint = Paint(p).apply { style = Paint.Style.FILL }
        val head = Path()
        head.moveTo(tipX + tx * hs,                         tipY + ty * hs)
        head.lineTo(tipX - tx * hs * 0.6f + px * hs * 0.7f, tipY - ty * hs * 0.6f + py * hs * 0.7f)
        head.lineTo(tipX - tx * hs * 0.6f - px * hs * 0.7f, tipY - ty * hs * 0.6f - py * hs * 0.7f)
        head.close()
        c.drawPath(head, headPaint)
    }

    private fun drawSharpTurn(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, side: Int) {
        val flip = if (side == 1) -1f else 1f
        val path = Path()
        // Arrow pointing down-right (sharp 135 degrees)
        path.moveTo(cx + flip * r, cy + r * 0.5f)            // tip
        path.lineTo(cx + flip * r * 0.3f, cy)                // top wing
        path.lineTo(cx + flip * r * 0.3f, cy + r * 0.25f)
        path.lineTo(cx - flip * r * 0.2f, cy - r)            // stem up
        path.lineTo(cx + flip * r * 0.1f, cy - r)
        path.lineTo(cx + flip * r * 0.55f, cy + r * 0.05f)
        path.lineTo(cx + flip * r * 0.55f, cy + r * 0.1f)   // bottom wing
        path.close()
        c.drawPath(path, p)
    }

    private fun drawUturn(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, side: Int) {
        val flip = if (side == 1) -1f else 1f
        val paint = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.25f }
        // U-turn arc
        val rect = RectF(cx - r * 0.5f, cy - r, cx + r * 0.5f, cy)
        c.drawArc(rect, if (flip < 0) 0f else 180f, 180f, false, paint)
        // Arrow head at the end
        val headPaint = Paint(p).apply { style = Paint.Style.FILL }
        val headX = if (flip < 0) cx - r * 0.5f else cx + r * 0.5f
        val path = Path()
        path.moveTo(headX, cy + r * 0.5f)
        path.lineTo(headX - flip * r * 0.3f, cy)
        path.lineTo(headX + flip * r * 0.3f, cy)
        path.close()
        c.drawPath(path, headPaint)
        // Stem
        c.drawLine(headX, cy, headX, cy + r, paint)
    }

    private fun drawRoundabout(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, exitNumber: Int) {
        val cr = r * 0.50f  // raio do círculo da rotatória

        // ── Arco CCW (300°) — sentido anti-horário, deixa lacuna no canto inferior-direito ──
        val strokePaint = Paint(p).apply {
            style       = Paint.Style.STROKE
            strokeWidth = r * 0.13f
            strokeCap   = Paint.Cap.BUTT
        }
        val oval = RectF(cx - cr, cy - cr, cx + cr, cy + cr)
        // Começa em 120° (inferior-esquerdo), varre -300° CCW → termina em 180° (esquerda)
        c.drawArc(oval, 120f, -300f, false, strokePaint)

        // ── Ponta de seta no final do arco (ângulo final = 120° - 300° = -180° = 180°) ──
        val endAngleDeg = 120.0 - 300.0          // = -180° → normalize to 180°
        val endAngleRad = Math.toRadians(endAngleDeg)
        val ex = (cx + cr * Math.cos(endAngleRad)).toFloat()  // ponto no círculo
        val ey = (cy + cr * Math.sin(endAngleRad)).toFloat()

        // Tangente CCW: rotate ponto 90° no sentido anti-horário = (-sin θ, cos θ) mas CCW é (-sin, -cos)...
        // Para arco CCW: tangente = (sin θ, -cos θ)
        val tangX =  Math.sin(endAngleRad).toFloat()
        val tangY = -Math.cos(endAngleRad).toFloat()

        val hs   = r * 0.22f   // tamanho da seta
        val perpX = -tangY * hs * 0.5f
        val perpY =  tangX * hs * 0.5f
        val headPaint = Paint(p).apply { style = Paint.Style.FILL }
        val arrowPath = Path()
        arrowPath.moveTo(ex + tangX * hs * 0.5f, ey + tangY * hs * 0.5f)          // ponta
        arrowPath.lineTo(ex - tangX * hs * 0.5f + perpX, ey - tangY * hs * 0.5f + perpY) // asa 1
        arrowPath.lineTo(ex - tangX * hs * 0.5f - perpX, ey - tangY * hs * 0.5f - perpY) // asa 2
        arrowPath.close()
        c.drawPath(arrowPath, headPaint)

        // ── Seta de entrada na rotatória (de baixo, apontando para cima) ──
        val entryPaint = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.13f; strokeCap = Paint.Cap.ROUND }
        c.drawLine(cx, cy + r, cx, cy + cr + r * 0.05f, entryPaint)

        // ── Número da saída no centro ──
        if (exitNumber > 0) {
            val textPaint = Paint(p).apply {
                style     = Paint.Style.FILL
                textSize  = r * 0.62f
                textAlign = Paint.Align.CENTER
                typeface  = android.graphics.Typeface.DEFAULT_BOLD
            }
            c.drawText(exitNumber.toString(), cx, cy + textPaint.textSize * 0.36f, textPaint)
        }
    }

    private fun drawFork(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, side: Int) {
        val flip = if (side == 1) -1f else 1f  // LEFT = -1, RIGHT = 1

        // Ramo não selecionado (fino e mais opaco)
        val dimPaint = Paint(p).apply {
            style = Paint.Style.STROKE
            strokeWidth = r * 0.13f
            alpha = 100
        }
        c.drawLine(cx, cy + r, cx, cy - r * 0.1f, dimPaint)
        c.drawLine(cx, cy - r * 0.1f, cx - flip * r * 0.55f, cy - r, dimPaint)

        // Ramo selecionado (grosso, opaco total)
        val activePaint = Paint(p).apply {
            style = Paint.Style.STROKE
            strokeWidth = r * 0.22f
            strokeCap = Paint.Cap.ROUND
        }
        c.drawLine(cx, cy + r, cx, cy - r * 0.1f, activePaint)
        c.drawLine(cx, cy - r * 0.1f, cx + flip * r * 0.55f, cy - r, activePaint)

        // Seta no topo do ramo ativo
        val headPaint = Paint(p).apply { style = Paint.Style.FILL }
        val tipX = cx + flip * r * 0.55f
        val tipY = cy - r
        val path = Path()
        path.moveTo(tipX, tipY - r * 0.18f)
        path.lineTo(tipX - flip * r * 0.22f, tipY + r * 0.15f)
        path.lineTo(tipX + flip * r * 0.1f, tipY + r * 0.1f)
        path.close()
        c.drawPath(path, headPaint)
    }

    private fun drawMerge(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, side: Int) {
        val strokePaint = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.2f }
        // Two lines merging into one
        c.drawLine(cx - r * 0.5f, cy + r, cx, cy, strokePaint)
        c.drawLine(cx + r * 0.5f, cy + r, cx, cy, strokePaint)
        c.drawLine(cx, cy, cx, cy - r, strokePaint)
    }

    private fun drawDestination(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float) {
        // Flag/pin icon
        val flagPaint = Paint(p).apply { style = Paint.Style.FILL }
        // Pin stem
        c.drawLine(cx, cy + r, cx, cy - r * 0.3f, Paint(p).apply { strokeWidth = r * 0.12f; style = Paint.Style.STROKE })
        // Flag
        val path = Path()
        path.moveTo(cx, cy - r)
        path.lineTo(cx + r * 0.6f, cy - r * 0.65f)
        path.lineTo(cx, cy - r * 0.3f)
        path.close()
        c.drawPath(path, flagPaint)
        // Base circle
        c.drawCircle(cx, cy + r, r * 0.15f, flagPaint)
    }

    private fun drawUnknown(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float) {
        // Simple question mark or dot
        c.drawCircle(cx, cy, r * 0.3f, p)
    }
}
