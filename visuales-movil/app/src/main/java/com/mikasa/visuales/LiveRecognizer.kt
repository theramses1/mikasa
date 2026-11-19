package com.mikasa.visuales

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Reconocimiento de canciones en tiempo real con el micrófono del celular.
 *
 * El AudioEngine ya guarda los últimos segundos del micrófono a 11 025 Hz. Cada segundo se saca
 * la huella de la ventana más reciente (7 s, y otra corta de 3,5 s para notar antes un cambio de
 * canción) y se compara con el setlist. Para no equivocarse con un golpe de ruido, una canción se
 * acepta si la coincidencia es muy clara, o si otra lectura de los últimos 3,5 s dio la misma
 * canción en una posición coherente con el tiempo que pasó entre ambas.
 * Probado con audio simulado con ruido y eco: reconoce en ~4 s y detecta el cambio en 3-5 s.
 */
class LiveRecognizer(
    private val audio: AudioEngine,
    private val library: SongLibrary,
    private val listener: Listener,
) {
    interface Listener {
        /** Coincidencia confirmada: `pos` = segundo de la canción en el instante `endMs`. */
        fun onMatch(song: Song, pos: Double, endMs: Double, score: Int)
        /** Sin coincidencia confirmada en esta lectura (mensaje para mostrar). */
        fun onListening(message: String, secondsWithoutMatch: Int)
    }

    companion object {
        private const val PERIOD_MS = 1000L
        private const val WINDOW_SEC = 7.0   // ventana analizada en cada lectura
        private const val SHORT_SEC = 3.5    // ventana corta para detectar cambios de canción
        private const val MIN_SEC = 2.5      // empieza a intentar con 2,5 s de audio
        private const val AGREE_SEC = 3.5    // dos lecturas coherentes dentro de este margen
        private const val MIN_SCORE = 5
        private const val SURE_SCORE = 12
    }

    private val main = Handler(Looper.getMainLooper())
    private var exec: ScheduledExecutorService? = null
    private var task: ScheduledFuture<*>? = null
    private class Reading(val songId: Long, val pos: Double, val endMs: Double)
    private val history = ArrayList<Reading>()
    private var lastMatchMs = 0.0

    /** La canción que está mostrando la app (para que la ventana corta busque un cambio). */
    @Volatile var currentSongId: Long? = null

    val running get() = task != null

    fun start() {
        if (task != null) return
        val e = exec ?: Executors.newSingleThreadScheduledExecutor().also { exec = it }
        history.clear()
        lastMatchMs = LyricsEngine.nowMs()
        task = e.scheduleWithFixedDelay({ tick() }, 0, PERIOD_MS, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        task?.cancel(false)
        task = null
    }

    fun release() {
        stop()
        exec?.shutdown()
        exec = null
    }

    private fun tick() {
        try {
            if (!library.hasAudioSongs()) {
                post { listener.onListening("Añade canciones con 🎵 Desde audio para reconocerlas.", 0) }
                return
            }
            val rec = audio.recentForId(WINDOW_SEC, MIN_SEC)
            if (rec == null) {
                post { listener.onListening(if (audio.running) "🎤 Escuchando…" else "Sin micrófono", 0) }
                return
            }
            val (samples, endNs) = rec
            val endMs = endNs / 1e6
            fun ok(x: SongLibrary.Ranked?) = x != null && x.score >= MIN_SCORE && x.score >= 1.5 * max(1, x.second)
            val long = library.rank(samples)
            val shortLen = minOf(samples.size, (SHORT_SEC * Fingerprint.SR).toInt())
            val short = library.rank(samples.copyOfRange(samples.size - shortLen, samples.size))
            val cur = currentSongId
            // La ventana larga aún mezcla la canción anterior: si la corta ya oye otra, manda la corta.
            val useShort = ok(short) && short!!.song.id != cur && (!ok(long) || long!!.song.id == cur)
            val r = if (useShort) short else long
            val valid = ok(r)
            val sure = valid && r!!.score >= SURE_SCORE && r.score >= 2.5 * max(1, r.second)
            history.removeAll { endMs - it.endMs > AGREE_SEC * 1000 }
            val agree = valid && history.any {
                it.songId == r!!.song.id && abs((r.pos - it.pos) - (endMs - it.endMs) / 1000) < 0.5
            }
            if (valid) history.add(Reading(r!!.song.id, r.pos, endMs))
            if (valid && (sure || agree)) {
                lastMatchMs = endMs
                val m = r!!
                post { listener.onMatch(m.song, m.pos, endMs, m.score) }
            } else {
                val silent = ((LyricsEngine.nowMs() - lastMatchMs) / 1000).toInt()
                val msg = if (valid) "🎤 Parece ${r!!.song.label}…" else "🎤 Escuchando…"
                post { listener.onListening(msg, silent) }
            }
        } catch (e: Exception) {
            post { listener.onListening("Error al reconocer: ${e.message}", 0) }
        }
    }

    private fun post(f: () -> Unit) { if (task != null) main.post(f) }
}
