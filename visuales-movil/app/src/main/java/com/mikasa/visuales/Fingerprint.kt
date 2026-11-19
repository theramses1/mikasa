package com.mikasa.visuales

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Reconocimiento de canciones por "huella acústica", 100 % local y gratuito.
 * Es el mismo algoritmo que visuales-concierto/js/fingerprint.js (constelación de picos, A. Wang 2003):
 *   1. espectrograma del audio a 11 025 Hz,
 *   2. solo los picos de energía más destacados (robustos a ruido y volumen),
 *   3. cada pico se empareja con unos pocos posteriores -> hash (f1, f2, Δt),
 *   4. para reconocer se cuentan los hashes que coinciden con el MISMO desfase temporal:
 *      ese desfase es además la posición de la muestra dentro de la canción.
 */
object Fingerprint {
    const val SR = 11025
    private const val N = 1024           // ventana FFT (~93 ms)
    const val HOP = 512                  // salto entre ventanas (~46 ms)
    private const val FMIN = 8
    private const val FMAX = 480         // bins útiles (~86 Hz – 5,2 kHz)
    private const val NB = FMAX - FMIN
    private const val PEAKS_PER_FRAME = 4
    private const val FANOUT = 5
    private const val DT_MIN = 1
    private const val DT_MAX = 50
    private const val DF_MAX = 120
    private const val WF = 10            // vecindario de un pico: ±bins
    private const val WT = 4             // y ±frames
    private const val DELTA = 2.0f       // debe superar la media del frame en e^2 (~8,7 dB)

    const val FRAME_SEC = HOP.toDouble() / SR

    class Index(val h: IntArray, val t: IntArray)

    private val hann = FloatArray(N) { i -> (0.5 - 0.5 * cos(2 * PI * i / (N - 1))).toFloat() }
    private val cosT = FloatArray(N / 2) { i -> cos(2 * PI * i / N).toFloat() }
    private val sinT = FloatArray(N / 2) { i -> (-sin(2 * PI * i / N)).toFloat() }
    private val rev = IntArray(N).also { r ->
        val bits = 10
        for (i in 0 until N) {
            var x = 0
            for (b in 0 until bits) x = x or (((i shr b) and 1) shl (bits - 1 - b))
            r[i] = x
        }
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        for (i in 0 until N) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= N) {
            val half = size shr 1
            val step = N / size
            var start = 0
            while (start < N) {
                for (k in 0 until half) {
                    val a = start + k
                    val b = a + half
                    val wr = cosT[k * step]
                    val wi = sinT[k * step]
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
                start += size
            }
            size = size shl 1
        }
    }

    /** Huella de un audio mono a 11 025 Hz: hashes y tiempos (en frames). */
    fun fingerprint(x: FloatArray): Pair<IntArray, IntArray> {
        val frames = max(0, (x.size - N) / HOP + 1)
        val s = FloatArray(frames * NB)
        val re = FloatArray(N)
        val im = FloatArray(N)
        for (f in 0 until frames) {
            val off = f * HOP
            for (i in 0 until N) { re[i] = x[off + i] * hann[i]; im[i] = 0f }
            fft(re, im)
            val row = f * NB
            for (k in 0 until NB) {
                val b = k + FMIN
                s[row + k] = ln(re[b] * re[b] + im[b] * im[b] + 1e-10f)
            }
        }
        return hashPeaks(findPeaks(s, frames))
    }

    /** Picos locales, ordenados por tiempo: lista plana [t0, f0, t1, f1, ...]. */
    private fun findPeaks(s: FloatArray, frames: Int): IntArray {
        val m1 = FloatArray(s.size) // máximo en frecuencia
        for (t in 0 until frames) {
            val row = t * NB
            for (k in 0 until NB) {
                var m = Float.NEGATIVE_INFINITY
                val a = max(0, k - WF)
                val b = min(NB - 1, k + WF)
                for (j in a..b) if (s[row + j] > m) m = s[row + j]
                m1[row + k] = m
            }
        }
        val peaks = ArrayList<Int>()
        val candV = FloatArray(NB)
        val candK = IntArray(NB)
        for (t in 0 until frames) {
            val row = t * NB
            var mean = 0f
            for (k in 0 until NB) mean += s[row + k]
            mean /= NB
            var nc = 0
            val ta = max(0, t - WT)
            val tb = min(frames - 1, t + WT)
            for (k in 0 until NB) {
                val v = s[row + k]
                if (v < mean + DELTA || v < m1[row + k]) continue
                var isMax = true
                var u = ta
                while (u <= tb && isMax) { if (m1[u * NB + k] > v) isMax = false; u++ }
                if (isMax) { candV[nc] = v; candK[nc] = k; nc++ }
            }
            // Los PEAKS_PER_FRAME más fuertes, después ordenados por frecuencia.
            val idx = (0 until nc).sortedByDescending { candV[it] }.take(PEAKS_PER_FRAME).map { candK[it] }.sorted()
            for (k in idx) { peaks.add(t); peaks.add(k + FMIN) }
        }
        return peaks.toIntArray()
    }

    private fun hashPeaks(p: IntArray): Pair<IntArray, IntArray> {
        val h = ArrayList<Int>()
        val tt = ArrayList<Int>()
        val n = p.size / 2
        for (i in 0 until n) {
            val t1 = p[2 * i]; val f1 = p[2 * i + 1]
            var c = 0
            var j = i + 1
            while (j < n && c < FANOUT) {
                val t2 = p[2 * j]; val f2 = p[2 * j + 1]
                val dt = t2 - t1
                j++
                if (dt < DT_MIN) continue
                if (dt > DT_MAX) break
                if (abs(f2 - f1) > DF_MAX) continue
                h.add((f1 shl 15) or (f2 shl 6) or dt)
                tt.add(t1)
                c++
            }
        }
        return h.toIntArray() to tt.toIntArray()
    }

    /** Ordena por hash para buscar con búsqueda binaria (y guardarlo compacto). */
    fun buildIndex(fp: Pair<IntArray, IntArray>): Index {
        val (h, t) = fp
        val packed = LongArray(h.size) { (h[it].toLong() shl 32) or (t[it].toLong() and 0xffffffffL) }
        packed.sort()
        return Index(IntArray(h.size) { (packed[it] shr 32).toInt() }, IntArray(h.size) { packed[it].toInt() })
    }

    private fun lowerBound(a: IntArray, v: Int): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }

    class Match(val id: Long, val score: Int, val offset: Int)

    /** Compara una muestra con las canciones. Devuelve candidatos ordenados de mejor a peor. */
    fun match(query: Pair<IntArray, IntArray>, songs: List<Pair<Long, Index>>): List<Match> {
        val (qh, qt) = query
        val out = ArrayList<Match>()
        for ((id, idx) in songs) {
            val counts = HashMap<Int, Int>()
            for (i in qh.indices) {
                val hv = qh[i]
                var j = lowerBound(idx.h, hv)
                while (j < idx.h.size && idx.h[j] == hv) {
                    val off = idx.t[j] - qt[i]
                    counts[off] = (counts[off] ?: 0) + 1
                    j++
                }
            }
            var best = 0
            var bestOff = 0
            for ((off, c) in counts) {
                val sc = c + max(counts[off - 1] ?: 0, counts[off + 1] ?: 0)
                if (sc > best) { best = sc; bestOff = off }
            }
            out.add(Match(id, best, bestOff))
        }
        return out.sortedByDescending { it.score }
    }
}

/**
 * Pasa audio mono de cualquier frecuencia a 11 025 Hz, por partes (sirve para archivos largos).
 * Filtro paso bajo FIR (sinc con ventana de Blackman, corte 5 kHz) + diezmado al sample más cercano.
 */
class Resampler(private val srcRate: Int) {
    private val half = 32
    private val len = 2 * half + 1
    private val taps: FloatArray
    private val hist = FloatArray(len)
    private var n = 0L
    private var nextPos: Double = half.toDouble()
    private val step = srcRate.toDouble() / Fingerprint.SR
    private var out = FloatArray(1 shl 16)
    private var outLen = 0

    init {
        val fc = 5000.0 / srcRate
        val t = DoubleArray(len) { i ->
            val k = i - half
            val sinc = if (k == 0) 2 * fc else sin(2 * PI * fc * k) / (PI * k)
            val w = 0.42 - 0.5 * cos(2 * PI * i / (len - 1)) + 0.08 * cos(4 * PI * i / (len - 1))
            sinc * w
        }
        val sum = t.sum()
        taps = FloatArray(len) { (t[it] / sum).toFloat() }
    }

    fun push(x: Float) {
        hist[(n % len).toInt()] = x
        n++
        while (nextPos.roundToLong() + half < n) {
            val center = nextPos.roundToLong()
            var acc = 0f
            var idx = ((center - half) % len).toInt()
            for (k in 0 until len) {
                acc += taps[k] * hist[idx]
                idx++; if (idx == len) idx = 0
            }
            if (outLen == out.size) out = out.copyOf(out.size * 2)
            out[outLen++] = acc
            nextPos += step
        }
    }

    fun result(): FloatArray = out.copyOf(outLen)

    // Uso en tiempo real: leer las muestras ya convertidas y vaciar el búfer.
    fun pendingCount() = outLen
    fun pendingAt(i: Int) = out[i]
    fun clearPending() { outLen = 0 }

    companion object {
        fun convert(x: FloatArray, srcRate: Int): FloatArray {
            val r = Resampler(srcRate)
            for (v in x) r.push(v)
            return r.result()
        }
    }
}
