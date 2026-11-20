package com.mikasa.visuales

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Análisis de audio en vivo desde el micrófono del celular: bandas (graves / medios / agudos),
 * detección de golpes y BPM. Es el mismo algoritmo que visuales-concierto/js/audio.js
 * (FFT de 2048 con auto-ganancia), pero hecho a mano con AudioRecord.
 *
 * El audio también se reenvía a [pcmListener] para grabarlo en el video.
 */
class AudioEngine(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 44100
        private const val FFT_SIZE = 2048
        private const val BLOCK = 1024
        private const val SMOOTHING = 0.55f
        private const val MIN_DB = -100f
        private const val MAX_DB = -30f
        private const val TAG = "AudioEngine"
    }

    // Bandas normalizadas 0..1.5, leídas por el hilo de render.
    @Volatile var bass = 0f; private set
    @Volatile var mid = 0f; private set
    @Volatile var high = 0f; private set
    @Volatile var level = 0f; private set
    @Volatile var bpm = 0; private set
    /** Volumen real del micrófono (RMS 0..1, sin auto-ganancia). */
    @Volatile var rms = 0f; private set
    @Volatile var sensitivity = 1f

    /** Momento del último golpe (System.nanoTime) o 0. */
    @Volatile var lastBeatNs = 0L; private set

    /** Se llama en el hilo de audio con cada bloque PCM 16 bits mono y su marca de tiempo (ns). */
    @Volatile var pcmListener: ((ShortArray, Int, Long) -> Unit)? = null

    /** Se llama en el hilo de audio en cada golpe detectado o manual. */
    @Volatile var onBeat: (() -> Unit)? = null

    val running get() = thread != null

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var stop = false

    // Reconocimiento en tiempo real: el micrófono se pasa a 11 025 Hz sobre la marcha y se guardan
    // los últimos 12 s en una memoria circular. Así cada análisis tiene el audio listo al instante.
    private var fpResampler = Resampler(SAMPLE_RATE)
    private val history = FloatArray(Fingerprint.SR * 12)
    private var historyPos = 0
    private var historyFilled = 0
    private var historyEndNs = 0L

    // Últimos 12 s a calidad completa (para el reconocimiento por internet).
    private val raw = ShortArray(SAMPLE_RATE * 12)
    private var rawPos = 0
    private var rawFilled = 0
    private var rawEndNs = 0L

    /**
     * Últimos `sec` segundos del micrófono a 22 050 Hz (para enviar a internet) y el instante en
     * que terminan. null si aún no hay `minSec` segundos.
     */
    fun recentHq(sec: Double, minSec: Double): Pair<FloatArray, Long>? = synchronized(raw) {
        val n = minOf(rawFilled, (sec * SAMPLE_RATE).toInt()) / 2 * 2
        if (n < SAMPLE_RATE * minSec) return null
        val out = FloatArray(n / 2)
        val start = (rawPos - n + raw.size) % raw.size
        for (i in out.indices) {
            val a = raw[(start + 2 * i) % raw.size]
            val b = raw[(start + 2 * i + 1) % raw.size]
            out[i] = (a + b) / 65536f
        }
        out to rawEndNs
    }

    // FFT
    private val ring = FloatArray(FFT_SIZE)
    private var ringPos = 0
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val window = FloatArray(FFT_SIZE) { i ->
        // Ventana de Blackman, como el AnalyserNode del navegador.
        val a = 2.0 * PI * i / FFT_SIZE
        (0.42 - 0.5 * cos(a) + 0.08 * cos(2 * a)).toFloat()
    }
    private val smoothMag = FloatArray(FFT_SIZE / 2)
    private val bytes = FloatArray(FFT_SIZE / 2)

    // Auto-ganancia
    private val floorV = floatArrayOf(1f, 1f, 1f, 1f)
    private val peakV = floatArrayOf(0f, 0f, 0f, 0f)
    private val bands = FloatArray(4)
    private var bassAvg = 0f
    private var prevBass = 0f
    private val intervals = ArrayList<Long>()

    /** Envolvente del golpe (1 justo en el golpe y decae), como en la versión web. */
    fun beatEnvelope(nowNs: Long): Float {
        val last = lastBeatNs
        if (last == 0L) return 0f
        val dt = (nowNs - last) / 1e9f
        return if (dt < 0f) 1f else exp(-dt * 7f)
    }

    /** Golpe manual (doble toque en la barra de audio) por si el micrófono no capta el ritmo. */
    fun tap() = triggerBeat(System.nanoTime())

    /**
     * Últimos `sec` segundos del micrófono, ya a 11 025 Hz (listos para la huella), y el instante
     * (System.nanoTime) en que terminan. null si todavía no hay al menos `minSec` segundos.
     */
    fun recentForId(sec: Double, minSec: Double = 3.0): Pair<FloatArray, Long>? = synchronized(history) {
        val n = minOf(historyFilled, (sec * Fingerprint.SR).toInt())
        if (n < Fingerprint.SR * minSec) return null
        val out = FloatArray(n)
        val start = (historyPos - n + history.size) % history.size
        for (i in 0 until n) out[i] = history[(start + i) % history.size]
        out to historyEndNs
    }

    @SuppressLint("MissingPermission") // se llama solo con el permiso concedido
    fun start(): Boolean {
        if (thread != null) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val bufSize = max(minBuf, BLOCK * 2 * 4)

        // Para música conviene el micrófono sin procesar (sin cancelación de eco ni control de ganancia).
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = if (unprocessed) {
            listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        } else {
            listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        }
        var rec: AudioRecord? = null
        for (src in sources) {
            try {
                val r = AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break }
                r.release()
            } catch (e: Exception) {
                Log.w(TAG, "Fuente de audio $src no disponible", e)
            }
        }
        if (rec == null) return false
        record = rec
        stop = false
        rec.startRecording()
        thread = Thread({ loop(rec) }, "audio").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    fun stop() {
        stop = true
        thread?.join(500)
        thread = null
        record?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        record = null
        synchronized(history) { historyFilled = 0; fpResampler = Resampler(SAMPLE_RATE) }
        synchronized(raw) { rawFilled = 0 }
        bass = 0f; mid = 0f; high = 0f; level = 0f
    }

    private fun loop(rec: AudioRecord) {
        val buf = ShortArray(BLOCK)
        while (!stop) {
            val n = rec.read(buf, 0, BLOCK)
            if (n <= 0) { if (n < 0) Thread.sleep(10); continue }
            val endNs = System.nanoTime()
            val startNs = endNs - n * 1_000_000_000L / SAMPLE_RATE
            pcmListener?.invoke(buf, n, startNs)
            synchronized(raw) {
                for (i in 0 until n) { raw[rawPos] = buf[i]; rawPos = (rawPos + 1) % raw.size }
                rawFilled = minOf(raw.size, rawFilled + n)
                rawEndNs = endNs
            }
            var sq = 0.0
            for (i in 0 until n) {
                val v = buf[i] / 32768f
                sq += v * v
                fpResampler.push(v)
            }
            rms = kotlin.math.sqrt(sq / n).toFloat()
            synchronized(history) {
                val m = fpResampler.pendingCount()
                for (i in 0 until m) {
                    history[historyPos] = fpResampler.pendingAt(i)
                    historyPos = (historyPos + 1) % history.size
                }
                fpResampler.clearPending()
                historyFilled = minOf(history.size, historyFilled + m)
                historyEndNs = endNs
            }
            for (i in 0 until n) {
                ring[ringPos] = buf[i] / 32768f
                ringPos = (ringPos + 1) % FFT_SIZE
            }
            analyze(endNs)
        }
    }

    private fun analyze(nowNs: Long) {
        for (i in 0 until FFT_SIZE) {
            re[i] = ring[(ringPos + i) % FFT_SIZE] * window[i]
            im[i] = 0f
        }
        fft(re, im)
        // Igual que getByteFrequencyData: magnitud suavizada en el tiempo -> dB -> 0..255.
        for (k in 0 until FFT_SIZE / 2) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k]) / FFT_SIZE
            smoothMag[k] = SMOOTHING * smoothMag[k] + (1 - SMOOTHING) * mag
            val db = 20f * log10(max(smoothMag[k], 1e-12f))
            bytes[k] = ((db - MIN_DB) / (MAX_DB - MIN_DB) * 255f).coerceIn(0f, 255f)
        }
        val hz = SAMPLE_RATE.toFloat() / FFT_SIZE
        fun avg(f0: Float, f1: Float): Float {
            val a = max(1, floor(f0 / hz).toInt())
            val b = min(bytes.size - 1, ceil(f1 / hz).toInt())
            var s = 0f
            for (i in a..b) s += bytes[i]
            return s / ((b - a + 1) * 255f)
        }
        val raw = FloatArray(4)
        raw[0] = avg(20f, 150f)
        raw[1] = avg(150f, 2000f)
        raw[2] = avg(2000f, 10000f)
        raw[3] = (raw[0] + raw[1] + raw[2]) / 3f

        // Auto-ganancia: cada banda se normaliza entre su mínimo y máximo recientes, así funciona
        // igual con la banda lejos que pegado al altavoz. Coeficientes ajustados a ~43 bloques/s.
        val sens = sensitivity
        val norm = FloatArray(4)
        for (k in 0 until 4) {
            val v = raw[k]
            floorV[k] = if (v < floorV[k]) v else floorV[k] + (v - floorV[k]) * 0.0028f
            peakV[k] = if (v > peakV[k]) v else peakV[k] - (peakV[k] - v) * 0.0028f
            val range = max(peakV[k] - floorV[k], 0.04f)
            norm[k] = (((v - floorV[k]) / range) * sens).coerceIn(0f, 1.5f)
            val prev = bands[k]
            bands[k] = prev + (norm[k] - prev) * (if (norm[k] > prev) 0.7f else 0.2f)
        }
        bass = bands[0]; mid = bands[1]; high = bands[2]; level = bands[3]

        // Golpe: subida brusca de graves sobre su media reciente.
        val nb = norm[0]
        bassAvg = bassAvg * 0.93f + nb * 0.07f
        val rising = nb - prevBass > 0.04f
        prevBass = nb
        if (nb > 0.45f && nb > bassAvg + 0.22f / sens && rising && nowNs - lastBeatNs > 220_000_000L) {
            triggerBeat(nowNs)
        }
    }

    @Synchronized
    private fun triggerBeat(nowNs: Long) {
        val dt = (nowNs - lastBeatNs) / 1_000_000L
        if (dt in 251L..1499L) {
            intervals.add(dt)
            if (intervals.size > 12) intervals.removeAt(0)
            val sorted = intervals.sorted()
            bpm = (60000.0 / sorted[sorted.size / 2]).roundToInt()
        }
        lastBeatNs = nowNs
        onBeat?.invoke()
    }

    /** FFT radix-2 in situ. */
    private fun fft(x: FloatArray, y: FloatArray) {
        val n = x.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = x[i]; x[i] = x[j]; x[j] = t
                t = y[i]; y[i] = y[j]; y[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val tr = x[b] * cr - y[b] * ci
                    val ti = x[b] * ci + y[b] * cr
                    x[b] = x[a] - tr; y[b] = y[a] - ti
                    x[a] += tr; y[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
