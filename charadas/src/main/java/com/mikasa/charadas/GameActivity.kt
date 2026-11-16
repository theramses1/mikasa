package com.mikasa.charadas

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs

class GameActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        const val EXTRA_CATEGORY = "category"
        private const val TILT_TRIGGER = 7.0f   // m/s² en el eje Z para contar la inclinación
        private const val TILT_NEUTRAL = 4.0f   // hay que volver a esta zona antes de la siguiente
        private const val FEEDBACK_MS = 750L
    }

    private enum class State { WAITING, COUNTDOWN, PLAYING, FEEDBACK, PAUSED, FINISHED }

    private lateinit var category: Category
    private lateinit var session: CategoryRepository.Session
    private lateinit var root: View
    private lateinit var wordView: TextView
    private lateinit var timerView: TextView
    private lateinit var scoreView: TextView
    private lateinit var hintView: TextView
    private lateinit var hintLeft: TextView
    private lateinit var hintRight: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var sensorManager: SensorManager? = null
    private var sensor: Sensor? = null
    private var usingGravity = false
    private val filtered = FloatArray(3)

    private var tone: ToneGenerator? = null
    private var vibrator: Vibrator? = null

    private var state = State.WAITING
    private var tiltMode = true
    private var soundOn = true
    private var durationMs = 60_000L
    private var endTime = 0L
    private var remainingOnPause = 0L
    private var lastSecondShown = -1
    private var armed = false
    private var neutralSince = 0L
    private var currentWord = ""

    private val words = ArrayList<String>()
    private val results = ArrayList<Boolean>()

    private var playColor = 0
    private val correctColor = 0xFF2EBD59.toInt()
    private val passColor = 0xFFF08A24.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        val cat = CategoryRepository.find(this, intent.getStringExtra(EXTRA_CATEGORY) ?: "")
        if (cat == null || cat.words.isEmpty()) { finish(); return }
        category = cat
        session = CategoryRepository.startSession(this, cat)

        root = findViewById(R.id.root)
        wordView = findViewById(R.id.word)
        timerView = findViewById(R.id.timer)
        scoreView = findViewById(R.id.score)
        hintView = findViewById(R.id.hint)
        hintLeft = findViewById(R.id.hintLeft)
        hintRight = findViewById(R.id.hintRight)

        tiltMode = CategoryRepository.tiltMode(this)
        soundOn = CategoryRepository.sound(this)
        durationMs = CategoryRepository.duration(this) * 1000L
        playColor = ColorUtils.blendARGB(category.color, 0xFF000000.toInt(), 0.25f)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)?.also { usingGravity = true }
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) tiltMode = false

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (soundOn) tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()

        findViewById<TextView>(R.id.category).text = "${category.emoji} ${category.name}"
        root.setOnTouchListener { v, e -> onTouch(v, e) }

        hideSystemBars()
        showWaiting()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------------- Estados ----------------

    private fun showWaiting() {
        state = State.WAITING
        root.setBackgroundColor(playColor)
        scoreView.text = ""
        timerView.text = ""
        setTapHints(false)
        wordView.text = if (tiltMode) "Pon el teléfono\nen tu frente" else "¿Listos?"
        hintView.text = if (tiltMode) "Abajo = correcto · Arriba = paso  ·  o toca para empezar"
        else "Toca para empezar"
    }

    private fun startCountdown() {
        state = State.COUNTDOWN
        neutralSince = 0L
        hintView.text = ""
        var n = 3
        val step = object : Runnable {
            override fun run() {
                if (state != State.COUNTDOWN) return
                if (n > 0) {
                    wordView.text = n.toString()
                    beep(ToneGenerator.TONE_PROP_BEEP, 120)
                    n--
                    handler.postDelayed(this, 900)
                } else {
                    startRound()
                }
            }
        }
        handler.post(step)
    }

    private fun startRound() {
        state = State.PLAYING
        endTime = SystemClock.elapsedRealtime() + durationMs
        lastSecondShown = -1
        armed = false
        setTapHints(!tiltMode)
        updateScore()
        nextWord()
        handler.post(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (state != State.PLAYING && state != State.FEEDBACK) return
            val left = endTime - SystemClock.elapsedRealtime()
            if (left <= 0) {
                timerView.text = "0"
                if (state == State.PLAYING) finishRound()
                // si está en FEEDBACK, finishRound se llama al terminar el destello
                return
            }
            val secs = ((left + 999) / 1000).toInt()
            if (secs != lastSecondShown) {
                lastSecondShown = secs
                timerView.text = secs.toString()
                timerView.setTextColor(if (secs <= 5) 0xFFFFE066.toInt() else 0xFFFFFFFF.toInt())
                if (secs <= 5) beep(ToneGenerator.TONE_PROP_BEEP2, 80)
            }
            handler.postDelayed(this, 100)
        }
    }

    private fun nextWord() {
        currentWord = session.next()
        wordView.text = currentWord
        root.setBackgroundColor(playColor)
    }

    private fun answer(correct: Boolean) {
        if (state != State.PLAYING) return
        state = State.FEEDBACK
        armed = false
        words += currentWord
        results += correct
        updateScore()
        root.setBackgroundColor(if (correct) correctColor else passColor)
        wordView.text = if (correct) "¡CORRECTO!" else "¡PASO!"
        if (correct) beep(ToneGenerator.TONE_PROP_ACK, 200) else beep(ToneGenerator.TONE_PROP_NACK, 200)
        vibrate(if (correct) 60 else 30)
        handler.postDelayed({
            if (state != State.FEEDBACK) return@postDelayed
            if (SystemClock.elapsedRealtime() >= endTime) {
                finishRound()
            } else {
                state = State.PLAYING
                nextWord()
            }
        }, FEEDBACK_MS)
    }

    private fun finishRound() {
        if (state == State.FINISHED) return
        state = State.FINISHED
        session.commit()
        handler.removeCallbacksAndMessages(null)
        setTapHints(false)
        root.setBackgroundColor(0xFF1B1033.toInt())
        wordView.text = "¡TIEMPO!"
        timerView.text = ""
        beep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
        vibrate(300)
        handler.postDelayed({
            startActivity(
                Intent(this, ResultsActivity::class.java)
                    .putExtra(EXTRA_CATEGORY, category.id)
                    .putStringArrayListExtra(ResultsActivity.EXTRA_WORDS, words)
                    .putExtra(ResultsActivity.EXTRA_RESULTS, results.toBooleanArray())
            )
            finish()
        }, 1600)
    }

    private fun updateScore() {
        scoreView.text = "✓ ${results.count { it }}"
    }

    private fun setTapHints(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        hintLeft.visibility = v
        hintRight.visibility = v
    }

    // ---------------- Entrada ----------------

    private fun onTouch(v: View, e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        when (state) {
            State.WAITING -> startCountdown()
            State.PAUSED -> resumeFromPause()
            State.PLAYING -> if (!tiltMode) answer(e.x > v.width / 2f)
            else -> {}
        }
        v.performClick()
        return true
    }

    override fun onSensorChanged(event: SensorEvent) {
        val z: Float
        if (usingGravity) {
            z = event.values[2]
        } else {
            // filtro paso bajo para aislar la gravedad del acelerómetro
            for (i in 0..2) filtered[i] = 0.8f * filtered[i] + 0.2f * event.values[i]
            z = filtered[2]
        }
        when (state) {
            State.WAITING -> {
                // empieza solo cuando el teléfono lleva un momento en vertical (en la frente)
                val now = SystemClock.elapsedRealtime()
                if (abs(z) < 3.0f) {
                    if (neutralSince == 0L) neutralSince = now
                    else if (now - neutralSince > 1000) startCountdown()
                } else neutralSince = 0L
            }
            State.PLAYING -> {
                if (!armed) {
                    if (abs(z) < TILT_NEUTRAL) armed = true
                } else if (z < -TILT_TRIGGER) {
                    answer(true)   // pantalla hacia el piso
                } else if (z > TILT_TRIGGER) {
                    answer(false)  // pantalla hacia el techo
                }
            }
            else -> {}
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------- Ciclo de vida ----------------

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (tiltMode) sensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
        when (state) {
            State.PLAYING, State.FEEDBACK -> {
                if (state == State.FEEDBACK) {
                    // contabiliza la respuesta y deja lista la siguiente palabra
                    currentWord = session.next()
                }
                remainingOnPause = (endTime - SystemClock.elapsedRealtime()).coerceAtLeast(1000)
                handler.removeCallbacksAndMessages(null)
                state = State.PAUSED
                root.setBackgroundColor(0xFF333333.toInt())
                wordView.text = "Pausa"
                hintView.text = "Toca para continuar"
                setTapHints(false)
            }
            State.COUNTDOWN -> {
                handler.removeCallbacksAndMessages(null)
                showWaiting()
            }
            else -> {}
        }
    }

    private fun resumeFromPause() {
        hintView.text = ""
        endTime = SystemClock.elapsedRealtime() + remainingOnPause
        lastSecondShown = -1
        armed = false
        state = State.PLAYING
        setTapHints(!tiltMode)
        wordView.text = currentWord
        root.setBackgroundColor(playColor)
        handler.post(ticker)
    }

    override fun onDestroy() {
        // si salen a media ronda, las palabras que ya salieron también cuentan
        if (::session.isInitialized) session.commit()
        handler.removeCallbacksAndMessages(null)
        tone?.release()
        super.onDestroy()
    }

    // ---------------- Sonido y vibración ----------------

    private fun beep(type: Int, ms: Int) {
        if (soundOn) runCatching { tone?.startTone(type, ms) }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long) {
        runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }
}
