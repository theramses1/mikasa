package com.mikasa.chess.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import com.mikasa.chess.engine.Board
import com.mikasa.chess.engine.Move
import com.mikasa.chess.engine.Piece
import kotlin.math.hypot
import kotlin.math.min

/**
 * Tablero de ajedrez interactivo. Admite tocar origen y destino o arrastrar
 * la pieza. No valida la lógica del puzzle: avisa al [listener] y la
 * actividad decide qué posición mostrar con [setPosition].
 */
class BoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    fun interface Listener {
        /** El usuario quiere mover de [from] a [to]; [animate] es false si arrastró la pieza. */
        fun onUserMove(from: Int, to: Int, animate: Boolean)
    }

    var listener: Listener? = null

    var board: Board? = null
        private set

    /** Negras abajo. */
    var flipped = false
        set(value) {
            field = value
            invalidate()
        }

    /** Si el usuario puede mover piezas (solo las de [playerIsWhite]). */
    var interactive = false
        set(value) {
            field = value
            if (!value) clearSelection()
            invalidate()
        }

    var playerIsWhite = true

    private var lastMove: Move? = null
    private var hintSquare = -1
    private var markSquare = -1
    private var markColor = 0

    private var selected = -1
    private var targets: List<Move> = emptyList()

    private var dragFrom = -1
    private var dragging = false
    private var downOnSelected = false
    private var dragX = 0f
    private var dragY = 0f
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var animMove: Move? = null
    private var animProgress = 1f
    private var animator: ValueAnimator? = null

    private val sq get() = width / 8f

    private val squarePaint = Paint()
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val coordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val piecePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = pieceTypeface(context)
    }
    private val glyphBounds = Rect()

    // ------------------------------------------------------------------ API

    /**
     * Muestra [position]. Si [animate] no es null, la pieza que llegó a
     * `animate.to` se desliza desde `animate.from`.
     */
    fun setPosition(position: Board, lastMove: Move?, animate: Move? = null, onEnd: (() -> Unit)? = null) {
        animator?.cancel()
        board = position
        this.lastMove = lastMove
        hintSquare = -1
        clearSelection()
        if (animate == null || width == 0) {
            animMove = null
            animProgress = 1f
            invalidate()
            onEnd?.invoke()
            return
        }
        animMove = animate
        animProgress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    animMove = null
                    animProgress = 1f
                    invalidate()
                    if (!cancelled) onEnd?.invoke()
                }
            })
            start()
        }
    }

    /** Resalta la pieza que hay que mover. */
    fun showHint(square: Int) {
        hintSquare = square
        invalidate()
    }

    /** Colorea una casilla (verde = correcto, rojo = error); -1 para quitarlo. */
    fun mark(square: Int, color: Int) {
        markSquare = square
        markColor = color
        invalidate()
    }

    fun clearMark() = mark(-1, 0)

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    // --------------------------------------------------------------- medida

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) w else min(w, h)
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Ajusta el tamaño de letra para que el rey ocupe ~82 % de la casilla.
        piecePaint.textSize = sq
        piecePaint.getTextBounds(GLYPH_SOLID[Piece.KING], 0, 1, glyphBounds)
        if (glyphBounds.height() > 0) piecePaint.textSize = sq * sq * 0.82f / glyphBounds.height()
        coordPaint.textSize = sq * 0.2f
        dotPaint.strokeWidth = sq * 0.08f
    }

    // ---------------------------------------------------------------- dibujo

    private fun col(square: Int) = if (flipped) 7 - Board.file(square) else Board.file(square)
    private fun row(square: Int) = if (flipped) Board.rank(square) else 7 - Board.rank(square)
    private fun left(square: Int) = col(square) * sq
    private fun top(square: Int) = row(square) * sq

    private fun squareAt(x: Float, y: Float): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return -1
        val c = (x / sq).toInt().coerceIn(0, 7)
        val r = (y / sq).toInt().coerceIn(0, 7)
        val file = if (flipped) 7 - c else c
        val rank = if (flipped) r else 7 - r
        return Board.square(file, rank)
    }

    private fun isLight(square: Int) = (Board.file(square) + Board.rank(square)) % 2 == 1

    override fun onDraw(canvas: Canvas) {
        val b = board ?: return
        val s = sq

        for (square in 0 until 64) {
            squarePaint.color = if (isLight(square)) LIGHT else DARK
            canvas.drawRect(left(square), top(square), left(square) + s, top(square) + s, squarePaint)
        }

        lastMove?.let {
            fillSquare(canvas, it.from, HIGHLIGHT)
            fillSquare(canvas, it.to, HIGHLIGHT)
        }
        if (selected >= 0) fillSquare(canvas, selected, SELECTED)
        if (markSquare >= 0) fillSquare(canvas, markSquare, markColor)
        if (hintSquare >= 0) {
            fillSquare(canvas, hintSquare, HINT)
        }

        if (b.inCheck()) {
            val k = b.kingSquare(b.whiteToMove)
            val cx = left(k) + s / 2
            val cy = top(k) + s / 2
            checkPaint.shader = RadialGradient(
                cx, cy, s * 0.62f,
                intArrayOf(Color.argb(255, 255, 40, 40), Color.argb(170, 230, 20, 20), Color.argb(0, 200, 0, 0)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(left(k), top(k), left(k) + s, top(k) + s, checkPaint)
        }

        drawCoordinates(canvas)

        val anim = animMove
        for (square in 0 until 64) {
            val piece = b[square]
            if (piece == 0) continue
            if (dragging && square == dragFrom) continue
            if (anim != null && square == anim.to) continue
            drawPiece(canvas, piece, left(square) + s / 2, top(square) + s / 2, 1f)
        }

        for (move in targets) {
            val cx = left(move.to) + s / 2
            val cy = top(move.to) + s / 2
            if (b[move.to] != 0) {
                dotPaint.style = Paint.Style.STROKE
                dotPaint.color = TARGET
                canvas.drawCircle(cx, cy, s * 0.44f, dotPaint)
            } else {
                dotPaint.style = Paint.Style.FILL
                dotPaint.color = TARGET
                canvas.drawCircle(cx, cy, s * 0.16f, dotPaint)
            }
        }

        if (anim != null) {
            val p = animProgress
            val x = left(anim.from) + (left(anim.to) - left(anim.from)) * p + s / 2
            val y = top(anim.from) + (top(anim.to) - top(anim.from)) * p + s / 2
            drawPiece(canvas, b[anim.to], x, y, 1f)
        }
        if (dragging && dragFrom >= 0 && b[dragFrom] != 0) {
            drawPiece(canvas, b[dragFrom], dragX, dragY - s * 0.35f, 1.25f)
        }
    }

    private fun fillSquare(canvas: Canvas, square: Int, color: Int) {
        overlayPaint.style = Paint.Style.FILL
        overlayPaint.color = color
        canvas.drawRect(left(square), top(square), left(square) + sq, top(square) + sq, overlayPaint)
    }

    private fun drawCoordinates(canvas: Canvas) {
        val s = sq
        val pad = s * 0.06f
        for (i in 0..7) {
            // Números en la columna izquierda.
            val rankSquare = if (flipped) Board.square(7, i) else Board.square(0, i)
            coordPaint.color = if (isLight(rankSquare)) DARK else LIGHT
            coordPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("${i + 1}", left(rankSquare) + pad, top(rankSquare) + pad + coordPaint.textSize * 0.85f, coordPaint)
            // Letras en la fila inferior.
            val fileSquare = if (flipped) Board.square(i, 7) else Board.square(i, 0)
            coordPaint.color = if (isLight(fileSquare)) DARK else LIGHT
            coordPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("${'a' + i}", left(fileSquare) + s - pad, top(fileSquare) + s - pad, coordPaint)
        }
    }

    private fun drawPiece(canvas: Canvas, piece: Int, cx: Float, cy: Float, scale: Float) {
        val type = Piece.type(piece)
        val baseSize = piecePaint.textSize
        piecePaint.textSize = baseSize * scale
        val solid = GLYPH_SOLID[type]
        piecePaint.getTextBounds(solid, 0, 1, glyphBounds)
        // Ambos glifos (relleno y contorno) comparten métricas: se centran con el relleno.
        val ax = cx - glyphBounds.exactCenterX()
        val y = cy - glyphBounds.exactCenterY()
        if (Piece.isWhite(piece)) {
            piecePaint.style = Paint.Style.FILL
            piecePaint.color = WHITE_FILL
            canvas.drawText(solid, ax, y, piecePaint)
            piecePaint.color = PIECE_INK
            canvas.drawText(GLYPH_OUTLINE[type], ax, y, piecePaint)
        } else {
            piecePaint.style = Paint.Style.STROKE
            piecePaint.strokeWidth = sq * 0.035f * scale
            piecePaint.color = BLACK_EDGE
            canvas.drawText(solid, ax, y, piecePaint)
            piecePaint.style = Paint.Style.FILL
            piecePaint.color = BLACK_FILL
            canvas.drawText(solid, ax, y, piecePaint)
        }
        piecePaint.textSize = baseSize
    }

    // --------------------------------------------------------------- toques

    private fun clearSelection() {
        selected = -1
        targets = emptyList()
        dragFrom = -1
        dragging = false
    }

    private fun isOwnPiece(square: Int): Boolean {
        val p = board?.get(square) ?: return false
        return p != 0 && Piece.isWhite(p) == playerIsWhite
    }

    private fun select(square: Int) {
        selected = square
        targets = board?.legalMovesFrom(square) ?: emptyList()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive || board == null || animMove != null) return false
        val square = squareAt(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                dragX = event.x
                dragY = event.y
                downOnSelected = false
                if (selected >= 0 && targets.any { it.to == square }) {
                    val from = selected
                    clearSelection()
                    invalidate()
                    listener?.onUserMove(from, square, true)
                    return true
                }
                if (square >= 0 && isOwnPiece(square)) {
                    downOnSelected = square == selected
                    select(square)
                    dragFrom = square
                } else {
                    clearSelection()
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragFrom >= 0) {
                    dragX = event.x
                    dragY = event.y
                    if (!dragging && hypot(event.x - downX, event.y - downY) > touchSlop) dragging = true
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val from = dragFrom
                if (dragging && from >= 0) {
                    dragging = false
                    dragFrom = -1
                    if (targets.any { it.to == square }) {
                        clearSelection()
                        invalidate()
                        listener?.onUserMove(from, square, false)
                        return true
                    }
                    // Soltada fuera de una casilla válida: vuelve a su sitio, sigue seleccionada.
                } else if (downOnSelected) {
                    clearSelection()
                }
                dragFrom = -1
                invalidate()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                dragFrom = -1
                invalidate()
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private const val ANIM_MS = 220L

        // Colores del tablero (verde clásico).
        val LIGHT = Color.rgb(238, 238, 210)
        val DARK = Color.rgb(118, 150, 86)
        private val HIGHLIGHT = Color.argb(130, 246, 246, 105)
        private val SELECTED = Color.argb(150, 246, 246, 105)
        private val HINT = Color.argb(150, 80, 160, 255)
        private val TARGET = Color.argb(60, 0, 0, 0)
        val MARK_GOOD = Color.argb(150, 129, 182, 76)
        val MARK_BAD = Color.argb(170, 230, 60, 50)

        private val WHITE_FILL = Color.rgb(250, 250, 250)
        private val PIECE_INK = Color.rgb(25, 25, 25)
        private val BLACK_FILL = Color.rgb(40, 38, 36)
        private val BLACK_EDGE = Color.argb(200, 235, 235, 235)

        // Índice = tipo de pieza (1 = peón ... 6 = rey).
        private val GLYPH_SOLID = arrayOf("", "♟", "♞", "♝", "♜", "♛", "♚")
        private val GLYPH_OUTLINE = arrayOf("", "♙", "♘", "♗", "♖", "♕", "♔")

        private var cachedTypeface: Typeface? = null

        private fun pieceTypeface(context: Context): Typeface =
            cachedTypeface ?: try {
                Typeface.createFromAsset(context.assets, "fonts/chess_pieces.ttf")
            } catch (e: RuntimeException) {
                Typeface.DEFAULT
            }.also { cachedTypeface = it }
    }
}
