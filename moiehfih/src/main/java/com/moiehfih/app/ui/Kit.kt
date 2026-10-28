package com.moiehfih.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Paleta de colores; [dark] se fija al crear la actividad según el modo del sistema. */
object Pal {
    var dark = false
    private fun c(light: Long, dark: Long) = (if (this.dark) dark else light).toInt()

    val bg get() = c(0xFFF4F5F7, 0xFF0F1115)
    val surface get() = c(0xFFFFFFFF, 0xFF181B21)
    val surface2 get() = c(0xFFEEF0F3, 0xFF22262E)
    val text get() = c(0xFF14161A, 0xFFECEEF2)
    val muted get() = c(0xFF646A75, 0xFF979EAB)
    val border get() = c(0xFFDFE2E7, 0xFF2B3039)
    val accent get() = c(0xFF2F6DF6, 0xFF5B8CFF)
    val accentSoft get() = c(0xFFE3ECFF, 0xFF1F2A44)
    val good get() = c(0xFF1F9D5A, 0xFF3CC47C)
    val warn get() = c(0xFFC27C00, 0xFFF0B429)
    val bad get() = c(0xFFD64545, 0xFFFF6B6B)
    val protein get() = c(0xFFE0573F, 0xFFFF7A5C)
    val carbs get() = c(0xFF2F9BE0, 0xFF4FB3F6)
    val fat get() = c(0xFFE6A817, 0xFFF5C542)
    val sugar get() = c(0xFFD9467A, 0xFFFF77A8)
    val water get() = c(0xFF1E9BD7, 0xFF4FC3F7)
    val scale get() = c(0xFF9AA3B2, 0xFF5D6573)
    val onAccent = 0xFFFFFFFF.toInt()

    fun soft(color: Int, alpha: Int = 38) = (color and 0x00FFFFFF) or (alpha shl 24)
}

object Fmt {
    val locale: Locale = Locale.forLanguageTag("es-MX")
    private val short = DateTimeFormatter.ofPattern("EEE d MMM", locale)
    private val long = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", locale)

    fun n(x: Double?, decimals: Int = 0): String =
        if (x == null || x.isNaN()) "—" else String.format(locale, "%,.${decimals}f", x)

    fun n(x: Int?): String = if (x == null) "—" else String.format(locale, "%,d", x)

    fun signed(x: Double?, decimals: Int = 1): String = when {
        x == null -> "—"
        abs(x) < 0.5 * Math.pow(10.0, -decimals.toDouble()) -> "±" + n(0.0, decimals)
        x > 0 -> "+" + n(x, decimals)
        else -> "−" + n(-x, decimals)
    }

    /** Número para un campo editable (sin separador de miles). */
    fun plain(x: Double?, decimals: Int = 1): String {
        if (x == null) return ""
        if (x == Math.floor(x) && abs(x) < 1e9) return x.toLong().toString()
        return String.format(Locale.US, "%.${decimals}f", x).trimEnd('0').trimEnd('.')
    }

    fun date(d: LocalDate, longForm: Boolean = false, today: LocalDate = LocalDate.now()): String = when (d) {
        today -> "Hoy"
        today.minusDays(1) -> "Ayer"
        else -> (if (longForm) long else short).format(d).replaceFirstChar { it.uppercase(locale) }
    }

    fun parse(s: CharSequence?): Double? = s?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).roundToInt()

fun View.dp(v: Number): Int = context.dp(v)

fun rounded(color: Int, radius: Float, stroke: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (stroke != null) setStroke(strokeWidth, stroke)
}

fun clickable(base: Drawable, radius: Float): Drawable =
    RippleDrawable(ColorStateList.valueOf(Pal.soft(Pal.text, 30)), base, rounded(0xFFFFFFFF.toInt(), radius))

// ---------- Construcción de vistas ----------

fun Context.vertical(block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    block()
}

fun Context.horizontal(block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    block()
}

fun <T : View> ViewGroup.add(v: T, width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f): T {
    // En una fila, ocupar todo el ancho dejaría sin espacio a los demás elementos.
    val horizontalRow = this is LinearLayout && orientation == LinearLayout.HORIZONTAL
    val w = if (horizontalRow && width == ViewGroup.LayoutParams.MATCH_PARENT) ViewGroup.LayoutParams.WRAP_CONTENT else width
    addView(v, LinearLayout.LayoutParams(w, height, weight))
    return v
}

fun View.margins(top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0): View {
    (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
        it.setMargins(context.dp(start), context.dp(top), context.dp(end), context.dp(bottom))
        layoutParams = it
    }
    return this
}

/** Hace que la vista ocupe una fracción del ancho en una fila horizontal. */
fun <T : View> T.weighted(w: Float = 1f): T {
    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w).also { lp ->
        (layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp.setMargins(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin) }
    }
    return this
}

fun ViewGroup.text(
    s: CharSequence,
    size: Float = 15f,
    color: Int = Pal.text,
    bold: Boolean = false,
    gravity: Int = Gravity.START,
    lines: Int = 0,
): TextView = add(TextView(context).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    this.gravity = gravity
    if (lines > 0) {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }
})

fun ViewGroup.h2(s: CharSequence) = text(s, 18f, bold = true)
fun ViewGroup.h3(s: CharSequence) = text(s, 16f, bold = true)
fun ViewGroup.muted(s: CharSequence, size: Float = 13f) = text(s, size, Pal.muted)

fun ViewGroup.sectionTitle(s: String) = text(s.uppercase(Fmt.locale), 12f, Pal.muted, bold = true).also {
    it.letterSpacing = 0.05f
    it.margins(top = 14, bottom = 8, start = 4)
}

fun ViewGroup.space(h: Int) = add(View(context), height = context.dp(h))

fun ViewGroup.card(onClick: (() -> Unit)? = null, block: LinearLayout.() -> Unit): LinearLayout {
    val ctx = context
    val r = ctx.dp(14).toFloat()
    val card = ctx.vertical {
        val base = rounded(Pal.surface, r, Pal.border, ctx.dp(1))
        background = if (onClick != null) clickable(base, r) else base
        setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14))
        if (onClick != null) setOnClickListener { onClick() }
        block()
    }
    add(card).margins(bottom = 12)
    return card
}

enum class Btn { PRIMARY, SECONDARY, GHOST, DANGER }

fun ViewGroup.button(label: String, style: Btn = Btn.SECONDARY, small: Boolean = false, onClick: () -> Unit): TextView {
    val ctx = context
    val r = ctx.dp(10).toFloat()
    val tv = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 13f else 14f)
        val (bg, fg) = when (style) {
            Btn.PRIMARY -> Pal.accent to Pal.onAccent
            Btn.SECONDARY -> Pal.surface2 to Pal.text
            Btn.GHOST -> 0 to Pal.accent
            Btn.DANGER -> Pal.surface2 to Pal.bad
        }
        setTextColor(fg)
        background = clickable(rounded(bg, r, if (style == Btn.SECONDARY || style == Btn.DANGER) Pal.border else null, ctx.dp(1)), r)
        val v = if (small) 6 else 11
        setPadding(ctx.dp(if (small) 10 else 14), ctx.dp(v), ctx.dp(if (small) 10 else 14), ctx.dp(v))
        minHeight = ctx.dp(if (small) 32 else 44)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }
    return add(tv)
}

fun ViewGroup.row(block: LinearLayout.() -> Unit): LinearLayout = add(context.horizontal(block))

fun ViewGroup.pill(s: String, color: Int = Pal.accent): TextView {
    val ctx = context
    return add(TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color)
        background = rounded(Pal.soft(color, 40), ctx.dp(99).toFloat())
        setPadding(ctx.dp(9), ctx.dp(3), ctx.dp(9), ctx.dp(3))
    }, width = ViewGroup.LayoutParams.WRAP_CONTENT)
}

fun ViewGroup.divider() = add(View(context).apply { setBackgroundColor(Pal.border) }, height = 1)

enum class InputKind { TEXT, INT, DECIMAL, MULTILINE }

fun Context.input(value: String = "", hint: String = "", kind: InputKind = InputKind.TEXT): EditText = EditText(this).apply {
    setText(value)
    this.hint = hint
    setTextColor(Pal.text)
    setHintTextColor(Pal.muted)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    background = rounded(Pal.surface2, dp(10).toFloat(), Pal.border, dp(1))
    setPadding(dp(11), dp(9), dp(11), dp(9))
    inputType = when (kind) {
        InputKind.TEXT -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        InputKind.INT -> InputType.TYPE_CLASS_NUMBER
        InputKind.DECIMAL -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        InputKind.MULTILINE -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
    if (kind == InputKind.MULTILINE) {
        minLines = 3
        gravity = Gravity.TOP or Gravity.START
    } else {
        isSingleLine = true
    }
}

/** Campo con etiqueta encima; devuelve el EditText. */
fun ViewGroup.field(label: String, value: String = "", kind: InputKind = InputKind.TEXT, hint: String = ""): EditText {
    val box = add(context.vertical()).margins(bottom = 10) as LinearLayout
    box.text(label, 13f, Pal.muted, bold = true).margins(bottom = 4)
    return box.add(context.input(value, hint, kind))
}

fun EditText.onChange(block: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(s: Editable?) = block(s?.toString() ?: "")
})

/** Control segmentado (pestañas pequeñas). Se repinta solo; [onSelect] recibe el nuevo índice. */
fun ViewGroup.segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
    val ctx = context
    val bar = ctx.horizontal {
        background = rounded(Pal.surface2, ctx.dp(10).toFloat())
        setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
    }
    val views = ArrayList<TextView>()
    var current = selected
    fun paint() = views.forEachIndexed { i, tv ->
        tv.setTextColor(if (i == current) Pal.text else Pal.muted)
        tv.background = if (i == current) rounded(Pal.surface, ctx.dp(8).toFloat()) else null
    }
    options.forEachIndexed { i, label ->
        val tv = TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(ctx.dp(2), ctx.dp(8), ctx.dp(2), ctx.dp(8))
            setOnClickListener {
                if (i != current) {
                    current = i
                    paint()
                    onSelect(i)
                }
            }
        }
        views += tv
        bar.add(tv, width = 0, weight = 1f)
    }
    paint()
    add(bar).margins(bottom = 12)
    return bar
}

/** Escala del 1 al 5 con botones. */
fun ViewGroup.scale5(label: String, value: Int?, onSelect: (Int?) -> Unit) {
    val ctx = context
    text(label, 13f, Pal.muted, bold = true).margins(bottom = 4)
    val bar = row {}
    bar.margins(bottom = 10)
    val views = ArrayList<TextView>()
    fun paint(sel: Int?) = views.forEachIndexed { i, tv ->
        val on = sel == i + 1
        tv.setTextColor(if (on) Pal.onAccent else Pal.text)
        tv.background = rounded(if (on) Pal.accent else Pal.surface2, ctx.dp(9).toFloat(), if (on) Pal.accent else Pal.border, ctx.dp(1))
    }
    var current = value
    for (i in 1..5) {
        val tv = TextView(ctx).apply {
            text = "$i"
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, ctx.dp(9), 0, ctx.dp(9))
            setOnClickListener {
                current = if (current == i) null else i
                paint(current)
                onSelect(current)
            }
        }
        views += tv
        bar.add(tv, width = 0, weight = 1f).margins(end = if (i < 5) 6 else 0)
    }
    paint(current)
}

/** Fila de lista: título, subtítulo, valor a la derecha y botón de borrar opcionales. */
fun ViewGroup.listRow(
    title: CharSequence,
    subtitle: CharSequence? = null,
    trailing: CharSequence? = null,
    trailingSub: CharSequence? = null,
    onDelete: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
): LinearLayout {
    val ctx = context
    if (childCount > 0 && getChildAt(childCount - 1).tag == "row") divider()
    val r = ctx.horizontal {
        tag = "row"
        setPadding(0, ctx.dp(10), 0, ctx.dp(10))
        if (onClick != null) {
            background = clickable(rounded(0, ctx.dp(8).toFloat()), ctx.dp(8).toFloat())
            setOnClickListener { onClick() }
        }
        val main = add(ctx.vertical(), width = 0, weight = 1f)
        main.text(title, 15f, bold = true, lines = 2)
        if (subtitle != null) main.muted(subtitle, 12.5f)
        if (trailing != null) {
            val right = add(ctx.vertical { gravity = Gravity.END }, width = ViewGroup.LayoutParams.WRAP_CONTENT)
            right.text(trailing, 14.5f, bold = true, gravity = Gravity.END)
            if (trailingSub != null) right.muted(trailingSub, 11.5f).gravity = Gravity.END
            right.margins(start = 8)
        }
        if (onDelete != null) {
            add(TextView(ctx).apply {
                text = "✕"
                setTextColor(Pal.muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                contentDescription = "Eliminar"
                setOnClickListener { onDelete() }
            }, width = ctx.dp(36), height = ctx.dp(36)).margins(start = 4)
        }
    }
    add(r)
    // El divisor posterior lo pone la siguiente fila.
    getChildAt(childCount - 1).tag = "row"
    return r
}

/** Cifra grande con etiqueta, para cuadrículas de estadísticas. */
fun ViewGroup.stat(value: String, label: String, color: Int = Pal.text, size: Float = 22f): LinearLayout {
    val box = add(context.vertical())
    box.text(value, size, color, bold = true)
    box.muted(label, 12f)
    return box
}

/** Fila de [n] tarjetas de igual ancho. */
fun ViewGroup.statCards(vararg items: Triple<String, String, Int>) {
    val r = row {}
    items.forEachIndexed { i, (value, label, color) ->
        val c = r.add(context.vertical(), width = 0, weight = 1f)
        c.margins(end = if (i < items.size - 1) 8 else 0)
        c.card { stat(value, label, color, 20f) }
    }
}

fun ViewGroup.macroBar(label: String, color: Int, value: Double, target: Int, unit: String = "g") {
    val ctx = context
    val top = row {}
    top.margins(top = 8, bottom = 4)
    top.add(View(ctx).apply { background = rounded(color, ctx.dp(4).toFloat()) }, width = ctx.dp(8), height = ctx.dp(8)).margins(end = 6)
    top.text(label, 13f).weighted()
    top.text("${Fmt.n(value)} / ${Fmt.n(target)} $unit", 13f, bold = true)
    add(ProgressBarView(ctx, if (target > 0) value / target else 0.0, color), height = ctx.dp(8))
}
