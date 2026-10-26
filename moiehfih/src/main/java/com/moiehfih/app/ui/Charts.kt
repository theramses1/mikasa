package com.moiehfih.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Barra de progreso redondeada; se pone roja si pasa del 110 %. */
class ProgressBarView(context: Context, private val fraction: Double, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        paint.color = Pal.surface2
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, paint)
        val f = fraction.coerceIn(0.0, 1.0).toFloat()
        if (f <= 0f) return
        paint.color = if (fraction > 1.1) Pal.bad else color
        rect.set(0f, 0f, max(height.toFloat(), width * f), height.toFloat())
        canvas.drawRoundRect(rect, r, r, paint)
    }
}

/** Anillo de calorías con texto al centro. */
class RingView(
    context: Context,
    private val value: Double,
    private val target: Double,
    private val label: String,
    private val sub: String,
) : View(context) {
    private val stroke = context.dp(11).toFloat()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val r = size / 2f - stroke / 2f
        rect.set(cx - r, cy - r, cx + r, cy + r)
        paint.color = Pal.surface2
        canvas.drawArc(rect, 0f, 360f, false, paint)
        val pct = if (target > 0) min(1.0, value / target).toFloat() else 0f
        paint.color = if (target > 0 && value > target * 1.05) Pal.bad else Pal.accent
        if (pct > 0) canvas.drawArc(rect, -90f, 360f * pct, false, paint)
        textPaint.color = Pal.text
        textPaint.textSize = size * 0.19f
        textPaint.isFakeBoldText = true
        canvas.drawText(label, cx, cy + textPaint.textSize * 0.2f, textPaint)
        textPaint.color = Pal.muted
        textPaint.textSize = size * 0.085f
        textPaint.isFakeBoldText = false
        canvas.drawText(sub, cx, cy + size * 0.2f, textPaint)
    }
}

/**
 * Gráfica de líneas/puntos. Cada serie tiene un valor (o null) por posición del eje X;
 * [xLabels] son las etiquetas del primer y último punto. Con [compact] no dibuja ejes (sparkline).
 */
class LineChartView(
    context: Context,
    private val series: List<Series>,
    private val xLabels: Pair<String, String>? = null,
    private val decimals: Int = 1,
    private val compact: Boolean = false,
) : View(context) {

    class Series(val values: List<Double?>, val color: Int, val line: Boolean = true, val dots: Boolean = false, val width: Float = 2.2f)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint().apply { strokeWidth = 1f }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.dp(10).toFloat() }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val all = series.flatMap { s -> s.values.filterNotNull() }
        if (all.isEmpty()) {
            axisPaint.color = Pal.muted
            axisPaint.textAlign = Paint.Align.CENTER
            axisPaint.textSize = context.dp(13).toFloat()
            canvas.drawText("Sin datos todavía", width / 2f, height / 2f, axisPaint)
            return
        }
        var lo = all.min()
        var hi = all.max()
        if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
        val pad = (hi - lo) * 0.08
        lo -= pad; hi += pad
        val padL = if (compact) 2f else context.dp(40).toFloat()
        val padR = if (compact) 2f else context.dp(6).toFloat()
        val padT = if (compact) 3f else context.dp(8).toFloat()
        val padB = if (compact) 3f else context.dp(20).toFloat()
        val n = max(1, series.maxOf { it.values.size } - 1)
        val w = width - padL - padR
        val h = height - padT - padB
        fun x(i: Int) = padL + i.toFloat() / n * w
        fun y(v: Double) = padT + ((1 - (v - lo) / (hi - lo)) * h).toFloat()

        if (!compact) {
            gridPaint.color = Pal.border
            axisPaint.color = Pal.muted
            axisPaint.textSize = context.dp(10).toFloat()
            for (t in listOf(0.0, 0.5, 1.0)) {
                val yy = padT + (t * h).toFloat()
                canvas.drawLine(padL, yy, width - padR, yy, gridPaint)
                axisPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(Fmt.n(hi - (hi - lo) * t, decimals), padL - context.dp(4), yy + context.dp(3), axisPaint)
            }
            xLabels?.let { (a, b) ->
                axisPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(a, padL, height - context.dp(5).toFloat(), axisPaint)
                axisPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(b, width - padR, height - context.dp(5).toFloat(), axisPaint)
            }
        }

        for (s in series) {
            val pts = s.values.withIndex().filter { it.value != null }
            if (s.line && pts.size > 1) {
                path.reset()
                pts.forEachIndexed { k, (i, v) -> if (k == 0) path.moveTo(x(i), y(v!!)) else path.lineTo(x(i), y(v!!)) }
                linePaint.color = s.color
                linePaint.strokeWidth = context.dp(s.width).toFloat() / 1.5f
                canvas.drawPath(path, linePaint)
            }
            if (s.dots || pts.size == 1) {
                dotPaint.color = s.color
                val r = context.dp(2.6f).toFloat()
                pts.forEach { (i, v) -> canvas.drawCircle(x(i), y(v!!), r, dotPaint) }
            }
        }
    }
}

/** Barras horizontales con etiqueta y valor (series por músculo). */
class HBarsView(context: Context, private val items: List<Pair<String, Int>>) : View(context) {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.dp(13).toFloat() }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val rowH = context.dp(26)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), rowH * items.size)
    }

    override fun onDraw(canvas: Canvas) {
        val labelW = context.dp(104).toFloat()
        val valueW = context.dp(30).toFloat()
        val maxV = max(1, items.maxOfOrNull { it.second } ?: 1)
        val barH = context.dp(8).toFloat()
        items.forEachIndexed { i, (label, v) ->
            val cy = i * rowH + rowH / 2f
            text.color = Pal.text
            text.textAlign = Paint.Align.LEFT
            text.isFakeBoldText = false
            canvas.drawText(label, 0f, cy + text.textSize / 3, text)
            val x0 = labelW
            val x1 = width - valueW - context.dp(6)
            bar.color = Pal.surface2
            rect.set(x0, cy - barH / 2, x1, cy + barH / 2)
            canvas.drawRoundRect(rect, barH / 2, barH / 2, bar)
            bar.color = Pal.accent
            rect.set(x0, cy - barH / 2, x0 + (x1 - x0) * v / maxV, cy + barH / 2)
            canvas.drawRoundRect(rect, barH / 2, barH / 2, bar)
            text.textAlign = Paint.Align.RIGHT
            text.isFakeBoldText = true
            canvas.drawText("$v", width.toFloat(), cy + text.textSize / 3, text)
        }
    }
}
