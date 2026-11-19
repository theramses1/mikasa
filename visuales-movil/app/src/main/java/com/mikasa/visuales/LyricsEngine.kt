package com.mikasa.visuales

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.Build
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Letras en la proyección (versión Android de visuales-concierto/js/lyrics.js):
 *  - dos modos: banda en vivo (el operador pasa línea a línea) y música grabada (sincronizada sola),
 *  - las dibuja en un Bitmap transparente con 5 formas de mostrarlas,
 *  - los 5 efectos de texto se aplican después en la GPU (FINAL_FRAG en Shaders.kt).
 *
 * Los tiempos van en milisegundos (System.nanoTime() / 1e6). Lo usan a la vez la interfaz y el
 * hilo de render, por eso todo está sincronizado.
 */
class LyricsEngine {

    companion object {
        val DISPLAYS = listOf("Karaoke", "Gran centro", "Desplazamiento", "Palabra a palabra", "Palabras dispersas")
        val EFFECTS = listOf("Neón", "Cromático", "Ola", "Glitch", "Video en el texto")

        fun nowMs() = System.nanoTime() / 1e6

        /** "[01:23.45] texto" -> líneas con tiempo. Admite varias marcas por línea; ignora [ar:...] etc. */
        fun parseLrc(text: String?): List<Line> {
            val out = ArrayList<Line>()
            val stamp = Regex("\\[(\\d+):(\\d+(?:[.:]\\d+)?)]")
            for (raw in (text ?: "").lines()) {
                val stamps = stamp.findAll(raw).toList()
                if (stamps.isEmpty()) continue
                val line = raw.replace(Regex("\\[[^]]*]"), "").trim()
                for (m in stamps) {
                    val t = m.groupValues[1].toInt() * 60 + m.groupValues[2].replace(':', '.').toDouble()
                    out.add(Line(t, line))
                }
            }
            return out.sortedBy { it.t }
        }

        /** Texto normal: una línea por renglón. Un renglón vacío entre estrofas = pausa (pantalla limpia). */
        fun parsePlain(text: String?): List<Line> {
            val out = ArrayList<Line>()
            for (raw in (text ?: "").lines()) {
                val line = raw.trim()
                if (line.isEmpty() && (out.isEmpty() || out.last().text.isEmpty())) continue
                out.add(Line(out.size * 4.0, line))
            }
            while (out.isNotEmpty() && out.last().text.isEmpty()) out.removeAt(out.size - 1)
            return out
        }

        private fun rnd(n: Double): Double { val x = sin(n * 127.1 + 311.7) * 43758.5453; return x - floor(x) }
        private fun clamp01(v: Double) = v.coerceIn(0.0, 1.0)
    }

    data class Line(val t: Double, val text: String)

    class State(
        val idx: Int, val p: Double, val pos: Double, val dur: Double, val text: String, val next: String,
    )

    var lines: List<Line> = emptyList(); private set
    var synced = false; private set
    var title = ""; private set
    var offset = 0.0; private set

    private var songStart = nowMs()
    private var manualIdx = -1
    private var manualAt = nowMs()
    private var titleShownAt = -1e12

    @Volatile var display = 0
    @Volatile var effect = 0
    @Volatile var size = 1f
    @Volatile var visible = true
    /** Banda en vivo: las líneas solo cambian cuando el operador avanza. */
    @Volatile var manual = true

    // ---------------- Carga y sincronización ----------------

    /** Carga una letra (o null para ninguna). Empieza antes de la primera línea: solo el título. */
    @Synchronized
    fun load(track: Track?, fallbackTitle: String = "") {
        val artist = track?.artistName ?: ""
        val name = track?.trackName ?: ""
        title = listOf(artist, name).filter { it.isNotBlank() }.joinToString(" — ").ifBlank { fallbackTitle }
        val now = nowMs()
        titleShownAt = now
        offset = 0.0
        manualIdx = -1
        manualAt = now
        songStart = now
        when {
            track == null -> { lines = emptyList(); synced = false }
            track.instrumental -> { lines = listOf(Line(0.0, "♪ instrumental ♪")); synced = false }
            else -> {
                val s = parseLrc(track.syncedLyrics)
                synced = s.isNotEmpty()
                lines = if (synced) s else parsePlain(track.plainLyrics)
            }
        }
    }

    @Synchronized
    fun clear() { lines = emptyList(); title = ""; synced = false }

    /** Canción reconocida: posAtEnd = segundo de la canción en que terminaba la muestra (endMs). */
    @Synchronized
    fun applyMatch(track: Track, posAtEnd: Double, endMs: Double) {
        load(track)
        val posNow = posAtEnd + (nowMs() - endMs) / 1000
        songStart = nowMs() - posNow * 1000
    }

    /** Misma canción, nueva medición: corrige solo el desfase. */
    @Synchronized
    fun resync(posAtEnd: Double, endMs: Double) {
        val now = nowMs()
        val posNow = posAtEnd + (now - endMs) / 1000
        offset += posNow - position(now)
    }

    fun position(now: Double) = (now - songStart) / 1000 + offset

    /** La canción (en vivo) empieza ahora mismo. */
    @Synchronized
    fun startNow() {
        val now = nowMs()
        songStart = now; offset = 0.0; manualIdx = -1; manualAt = now
    }

    /**
     * Línea siguiente / anterior. En modo banda (o letra sin tiempos) pasa de línea; con letra
     * sincronizada corrige el desfase para que esa línea empiece justo ahora.
     */
    @Synchronized
    fun step(dir: Int) {
        if (lines.isEmpty()) return
        val now = nowMs()
        if (manual || !synced) {
            manualIdx = (manualIdx + dir).coerceIn(-1, lines.size)
            manualAt = now
            return
        }
        val st = state(now)
        val target = (st.idx + dir).coerceIn(0, lines.size - 1)
        offset += lines[target].t - st.pos + 0.01
    }

    @Synchronized
    fun nudge(sec: Double) { offset += sec }

    @Synchronized
    fun state(now: Double): State {
        val l = lines
        if (manual || !synced) {
            val idx = manualIdx
            val dur = if (synced && idx in l.indices && idx + 1 < l.size) min(8.0, max(1.5, l[idx + 1].t - l[idx].t)) else 3.5
            val p = clamp01((now - manualAt) / 1000 / dur)
            return State(idx, p, 0.0, dur, l.getOrNull(idx)?.text ?: "", l.getOrNull(idx + 1)?.text ?: "")
        }
        val pos = position(now)
        var idx = -1
        for (i in l.indices) { if (l[i].t <= pos) idx = i else break }
        val t0 = if (idx >= 0) l[idx].t else 0.0
        val t1 = if (idx + 1 < l.size) l[idx + 1].t else t0 + 5
        val dur = max(0.3, t1 - t0)
        return State(
            idx, if (idx >= 0) clamp01((pos - t0) / dur) else 0.0, pos, dur,
            if (idx >= 0) l[idx].text else "", l.getOrNull(idx + 1)?.text ?: "",
        )
    }

    /** Para el teleprompter del operador: línea anterior, actual y dos siguientes. */
    @Synchronized
    fun teleprompter(): List<String> {
        val st = state(nowMs())
        return listOf(-1, 0, 1, 2).map { lines.getOrNull(st.idx + it)?.text ?: "" }
    }

    @Synchronized
    fun atEnd(): Boolean = lines.isNotEmpty() && state(nowMs()).idx >= lines.size - 1

    // ---------------- Dibujo ----------------

    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.SANS_SERIF, 900, false)
        } else Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    /** Bitmap de la capa (se crea al tamaño pedido). */
    fun bitmapFor(w: Int, h: Int): Bitmap {
        val b = bitmap
        if (b != null && b.width == w && b.height == h) return b
        b?.recycle()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it; canvas = Canvas(it) }
    }

    /** Dibuja la capa de letras. Devuelve true si hay algo que mostrar. */
    @Synchronized
    fun draw(w: Int, h: Int, beat: Float): Boolean {
        bitmapFor(w, h)
        val c = canvas!!
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val now = nowMs()
        val W = w.toFloat(); val H = h.toFloat()
        var drew = false
        paint.textAlign = Paint.Align.CENTER

        // Cartel con artista y título durante 5 s al cargar una canción.
        val tc = now - titleShownAt
        if (title.isNotEmpty() && tc < 5000) {
            paint.alpha = (255 * clamp01(min(tc / 400, (5000 - tc) / 600))).toInt()
            paint.textSize = H * 0.045f
            fitText(title, W * 0.92f)
            c.drawText(title, W / 2, H * 0.06f + paint.textSize, paint)
            paint.alpha = 255
            drew = true
        }
        if (!visible || lines.isEmpty()) return drew

        val st = state(now)
        if (st.idx < 0 && display != 2) return drew      // intro: solo el desplazamiento la anticipa
        if (st.text.isEmpty() && display != 2) return drew // pausa / instrumental: pantalla limpia
        if (st.idx >= lines.size) return drew            // fin de la letra
        when (display) {
            0 -> karaoke(c, W, H, st)
            1 -> center(c, W, H, st, beat)
            2 -> scroll(c, W, H, st)
            3 -> word(c, W, H, st, beat)
            else -> scatter(c, W, H, st, beat)
        }
        paint.alpha = 255
        return true
    }

    private fun fitText(text: String, maxW: Float) {
        val w = paint.measureText(text)
        if (w > maxW) paint.textSize *= maxW / w
    }

    private fun wrap(text: String, maxW: Float): List<String> {
        val rows = ArrayList<String>()
        var cur = ""
        for (w in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val test = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(test) > maxW && cur.isNotEmpty()) { rows.add(cur); cur = w } else cur = test
        }
        if (cur.isNotEmpty()) rows.add(cur)
        return rows
    }

    // 1. Karaoke: la línea se va "pintando" de izquierda a derecha; la siguiente debajo.
    private fun karaoke(c: Canvas, W: Float, H: Float, st: State) {
        val fs = H * 0.075f * size
        paint.textSize = fs
        val rows = wrap(st.text, W * 0.86f)
        val lh = fs * 1.15f
        val y0 = H * 0.80f - (rows.size - 1) * lh
        val widths = rows.map { paint.measureText(it) }
        var filled = (st.p * widths.sum()).toFloat()
        paint.textAlign = Paint.Align.LEFT
        rows.forEachIndexed { i, row ->
            val w = widths[i]; val x = (W - w) / 2; val y = y0 + i * lh
            paint.alpha = 107
            c.drawText(row, x, y, paint)
            val f = filled.coerceIn(0f, w)
            if (f > 0) {
                c.save()
                c.clipRect(x, y - fs * 1.1f, x + f, y + fs * 0.5f)
                paint.alpha = 255
                c.drawText(row, x, y, paint)
                c.restore()
            }
            filled -= w
        }
        if (st.next.isNotEmpty()) {
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = fs * 0.55f
            paint.alpha = 90
            c.drawText(wrap(st.next, W * 0.9f).firstOrNull() ?: "", W / 2, y0 + (rows.size - 1) * lh + fs * 0.95f, paint)
        }
    }

    // 2. Gran centro: una línea enorme en el centro que aparece, late con el ritmo y desaparece.
    private fun center(c: Canvas, W: Float, H: Float, st: State, beat: Float) {
        val elapsed = st.p * st.dur; val left = (1 - st.p) * st.dur
        val a = clamp01(min(elapsed / 0.25, if (synced && !manual) left / 0.3 else 1.0))
        val s = 1 + 0.08f * beat
        val fs = H * 0.11f * size
        c.save()
        c.translate(W / 2, H / 2)
        c.scale(s, s)
        paint.textSize = min(fs, W * 0.16f)
        val rows = wrap(st.text, W * 0.82f / s)
        val lh = paint.textSize * 1.12f
        paint.alpha = (255 * a).toInt()
        rows.forEachIndexed { i, row -> c.drawText(row, 0f, (i - (rows.size - 1) / 2f) * lh + paint.textSize * 0.35f, paint) }
        c.restore()
    }

    // 3. Desplazamiento: varias líneas como un teleprompter; la actual resaltada.
    private fun scroll(c: Canvas, W: Float, H: Float, st: State) {
        val fs = H * 0.06f * size
        val lh = fs * 1.7f
        var s = if (synced && !manual) clamp01((st.p * st.dur - (st.dur - 0.35)) / 0.35) else 0.0
        s = s * s * (3 - 2 * s)
        val ci = st.idx + s
        val base = floor(ci).toInt()
        for (k in base - 4..base + 5) {
            if (k < 0 || k >= lines.size) continue
            val text = lines[k].text
            if (text.isEmpty()) continue
            val d = abs(k - ci)
            val isCur = k == st.idx
            val sz = if (isCur) fs * 1.25f else fs
            paint.textSize = sz
            val w = paint.measureText(text)
            val sc = min(1f, W * 0.9f / w)
            c.save()
            c.translate(W / 2, (H / 2 + (k - ci) * lh + sz * 0.35f).toFloat())
            c.scale(sc, sc)
            paint.alpha = (255 * if (isCur) 1.0 else max(0.0, 0.55 - d * 0.1)).toInt()
            c.drawText(text, 0f, 0f, paint)
            c.restore()
        }
    }

    // 4. Palabra a palabra: cada palabra sola y enorme, con la línea completa abajo como guía.
    private fun word(c: Canvas, W: Float, H: Float, st: State, beat: Float) {
        val words = st.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return
        val n = words.size
        val wi = min(n - 1, floor(st.p * n).toInt())
        val local = st.p * n - wi
        val pop = (1 + 0.25 * (1 - min(1.0, local * 4)) + 0.1 * beat).toFloat()
        var fs = H * 0.17f * size
        paint.textSize = fs
        val w = paint.measureText(words[wi])
        val fit = min(1f, W * 0.88f / (w * pop))
        c.save()
        c.translate(W / 2, H * 0.5f)
        c.scale(pop * fit, pop * fit)
        paint.alpha = 255
        c.drawText(words[wi], 0f, fs * 0.35f, paint)
        c.restore()
        fs = H * 0.035f * size
        paint.textSize = fs
        val gap = paint.measureText(" ")
        val ws = words.map { paint.measureText(it) }
        val total = ws.sum() + gap * (n - 1)
        val scale = min(1f, W * 0.94f / total)
        paint.textSize = fs * scale
        var x = (W - total * scale) / 2
        paint.textAlign = Paint.Align.LEFT
        words.forEachIndexed { i, wd ->
            paint.alpha = if (i == wi) 255 else 90
            c.drawText(wd, x, H * 0.9f, paint)
            x += (ws[i] + gap) * scale
        }
    }

    // 5. Palabras dispersas: saltan a posiciones y ángulos al azar por toda la pantalla.
    private fun scatter(c: Canvas, W: Float, H: Float, st: State, beat: Float) {
        val words = st.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val n = words.size
        val seed = (st.idx + 1) * 97.0
        words.forEachIndexed { k, wd ->
            val appear = k.toDouble() / n
            if (st.p < appear) return@forEachIndexed
            val age = (st.p - appear) * st.dur
            fun r(j: Int) = rnd(seed + k * 13 + j)
            val pop = if (age < 0.15) age / 0.15 * 1.25 else 1 + 0.25 * max(0.0, 1 - (age - 0.15) * 5)
            paint.textSize = (H * (0.07 + 0.08 * r(4)) * size).toFloat()
            fitText(wd, W * 0.6f)
            c.save()
            c.translate((W * (0.15 + 0.7 * r(1))).toFloat(), (H * (0.2 + 0.62 * r(2))).toFloat())
            c.rotate(((r(3) - 0.5) * 0.7 * 57.2958).toFloat())
            val s = (pop + 0.08 * beat).toFloat()
            c.scale(s, s)
            paint.alpha = if (r(5) > 0.6) 255 else 191
            c.drawText(wd, 0f, 0f, paint)
            c.restore()
        }
    }
}
