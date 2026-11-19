package com.mikasa.visuales

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Medidor pequeño: barras de graves, medios y agudos, y un punto que se enciende en cada golpe. */
class LevelMeterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var audio: AudioEngine? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors = intArrayOf(0xFFFF4D6D.toInt(), 0xFFFFC23D.toInt(), 0xFF3DD6FF.toInt())
    private val rect = RectF()
    private val d = resources.displayMetrics.density

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postOnAnimation(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val a = audio ?: return
        val values = floatArrayOf(a.bass, a.mid, a.high)
        val dot = height.toFloat()
        val barW = (width - dot - 4 * d * 3) / 3f
        val r = 2 * d
        for (i in 0 until 3) {
            val x = i * (barW + 4 * d)
            paint.color = 0x33FFFFFF
            rect.set(x, 0f, x + barW, height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
            val h = (values[i] / 1.5f).coerceIn(0f, 1f) * height
            paint.color = colors[i]
            rect.set(x, height - h, x + barW, height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
        }
        val beat = a.beatEnvelope(System.nanoTime())
        paint.color = 0xFFFFFFFF.toInt()
        paint.alpha = (40 + 215 * beat).toInt()
        canvas.drawCircle(width - dot / 2, height / 2f, dot / 2 * (0.6f + 0.4f * beat), paint)
    }
}
