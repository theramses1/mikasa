package com.mikasa.visuales

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.mikasa.visuales.databinding.ActivityMainBinding
import kotlin.math.abs
import kotlin.random.Random

class MainActivity : AppCompatActivity(), VisualRenderer.Callbacks {

    private lateinit var b: ActivityMainBinding
    private lateinit var audio: AudioEngine
    private lateinit var renderer: VisualRenderer
    private lateinit var adapter: EffectAdapter

    private var cameraProvider: ProcessCameraProvider? = null
    private val prefs by lazy { getSharedPreferences("visuales", MODE_PRIVATE) }
    private fun rotKey() = if (useFront) "rot_front" else "rot_back"
    private var cameraTexture: SurfaceTexture? = null
    private var useFront = false

    // La SurfaceTexture solo admite una cámara conectada a la vez: si llega una petición nueva
    // mientras la anterior sigue en uso, se espera a que la suelte.
    private var activeSurface: Surface? = null
    private var pendingRequest: SurfaceRequest? = null

    private var auto = false
    private var autoEvery = 8
    private var beatCount = 0
    private var controlsVisible = true

    private var recording = false
    private var recStart = 0L
    private val recTicker = object : Runnable {
        override fun run() {
            if (!recording) return
            val s = (SystemClock.elapsedRealtime() - recStart) / 1000
            b.tvRec.text = String.format("● %02d:%02d", s / 60, s % 60)
            b.tvRec.postDelayed(this, 500)
        }
    }

    private val bpmTicker = object : Runnable {
        override fun run() {
            val bpm = audio.bpm
            b.tvBpm.text = if (bpm > 0) "$bpm BPM" else "— BPM"
            b.tvBpm.postDelayed(this, 500)
        }
    }

    private val hideEffectName = Runnable {
        b.tvEffect.animate().alpha(0f).setDuration(600).start()
        b.tvEffectDesc.animate().alpha(0f).setDuration(600).start()
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsChanged() }

    private val storageLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()

        audio = AudioEngine(this)
        audio.onBeat = { onBeat() }
        renderer = VisualRenderer(audio, this)

        b.glView.setEGLContextClientVersion(3)
        b.glView.setEGLConfigChooser(VisualRenderer.CONFIG_CHOOSER)
        b.glView.preserveEGLContextOnPause = true
        b.glView.setRenderer(renderer)
        b.glView.renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY

        b.meter.audio = audio
        setupControls()
        setupGestures()
        selectEffect(0, scroll = false)
        showHint()
    }

    override fun onResume() {
        super.onResume()
        b.glView.onResume()
        onPermissionsChanged(askIfMissing = true)
        b.tvBpm.post(bpmTicker)
    }

    override fun onPause() {
        if (recording) stopRecording()
        b.glView.queueEvent { renderer.onPauseGl() }
        b.glView.onPause()
        audio.stop()
        b.tvBpm.removeCallbacks(bpmTicker)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val d = resources.displayMetrics.density
            b.topBar.updatePadding(top = bars.top + (8 * d).toInt())
            b.bottomBar.updatePadding(bottom = bars.bottom + (16 * d).toInt())
            insets
        }
    }

    // ---------------- Permisos ----------------

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private var askedOnce = false
    private var warnedNoMic = false

    private fun onPermissionsChanged(askIfMissing: Boolean = false) {
        val cam = has(Manifest.permission.CAMERA)
        val mic = has(Manifest.permission.RECORD_AUDIO)
        if ((!cam || !mic) && askIfMissing && !askedOnce) {
            askedOnce = true
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            return
        }
        b.permissionBox.visibility = if (cam) View.GONE else View.VISIBLE
        if (cam) bindCamera()
        val micOk = mic && (audio.running || audio.start())
        if (!micOk && cam && !warnedNoMic) {
            warnedNoMic = true
            toast(getString(R.string.no_mic))
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    // ---------------- Cámara ----------------

    override fun onCameraTexture(texture: SurfaceTexture) {
        // Hilo de render: hay un contexto GL nuevo, hay que volver a conectar la cámara.
        runOnUiThread {
            cameraTexture = texture
            activeSurface = null
            pendingRequest = null
            if (has(Manifest.permission.CAMERA)) bindCamera(force = true)
        }
    }

    private var boundFront: Boolean? = null

    private fun bindCamera(force: Boolean = false) {
        if (cameraTexture == null) return
        if (!force && boundFront == useFront && cameraProvider != null) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val selector = if (useFront && provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                useFront = false
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .build()
                )
                .build()
            preview.setSurfaceProvider(ContextCompat.getMainExecutor(this)) { request -> onSurfaceRequest(request) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview)
                boundFront = useFront
                renderer.mirror = useFront
                b.switchMirror.isChecked = useFront
                renderer.userRot = prefs.getInt(rotKey(), 0)
            } catch (e: Exception) {
                toast("No se pudo abrir la cámara: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onSurfaceRequest(request: SurfaceRequest) {
        if (activeSurface != null) {
            pendingRequest?.willNotProvideSurface()
            pendingRequest = request
            return
        }
        val st = cameraTexture ?: run { request.willNotProvideSurface(); return }
        val res = request.resolution
        st.setDefaultBufferSize(res.width, res.height)
        request.setTransformationInfoListener(ContextCompat.getMainExecutor(this)) { info ->
            renderer.setCameraFrame(res.width, res.height, info.rotationDegrees)
        }
        val surface = Surface(st)
        activeSurface = surface
        request.provideSurface(surface, ContextCompat.getMainExecutor(this)) {
            surface.release()
            if (activeSurface === surface) activeSurface = null
            pendingRequest?.let { next ->
                pendingRequest = null
                onSurfaceRequest(next)
            }
        }
    }

    private fun flipCamera() {
        useFront = !useFront
        bindCamera(force = true)
    }

    // ---------------- Controles ----------------

    private fun setupControls() {
        adapter = EffectAdapter { selectEffect(it, scroll = false) }
        b.effectList.adapter = adapter

        b.btnFlip.setOnClickListener { flipCamera() }
        b.btnSettings.setOnClickListener {
            val show = b.settingsPanel.visibility != View.VISIBLE
            b.settingsPanel.visibility = if (show) View.VISIBLE else View.GONE
            b.btnSettings.isActivated = show
        }
        b.btnPhoto.setOnClickListener {
            ensureStoragePermission()
            renderer.requestPhoto()
        }
        b.btnRecord.setOnClickListener { if (recording) stopRecording() else startRecording() }
        b.btnAuto.setOnClickListener {
            auto = !auto
            beatCount = 0
            b.btnAuto.isActivated = auto
            toast(if (auto) "Cambio automático cada $autoEvery golpes" else "Cambio automático desactivado")
        }
        b.meterBox.setOnClickListener { audio.tap() }
        b.btnGrant.setOnClickListener {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) || !askedOnce) {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            } else {
                openAppSettings()
            }
        }

        b.sliderIntensity.addOnChangeListener { _, v, _ -> renderer.intensity = v }
        b.sliderHue.addOnChangeListener { _, v, _ -> renderer.hue = v }
        b.sliderSens.addOnChangeListener { _, v, _ -> audio.sensitivity = v }
        b.groupAuto.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            autoEvery = when (id) {
                R.id.auto4 -> 4
                R.id.auto16 -> 16
                R.id.auto32 -> 32
                else -> 8
            }
        }
        b.switchFlash.setOnCheckedChangeListener { _, on -> renderer.beatFlash = on }
        b.switchMirror.setOnCheckedChangeListener { _, on -> renderer.mirror = on }
        b.btnRotate.setOnClickListener {
            // Respaldo por si en algún celular la imagen sale girada. Se recuerda por cámara.
            val r = (renderer.userRot + 1) % 4
            renderer.userRot = r
            prefs.edit().putInt(rotKey(), r).apply()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                val dy = e2.y - (e1?.y ?: e2.y)
                if (abs(dx) > abs(dy) && abs(dx) > 80) {
                    val n = Shaders.EFFECTS.size
                    selectEffect(((renderer.effectIndex + if (dx < 0) 1 else -1) % n + n) % n)
                    return true
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                setControlsVisible(!controlsVisible)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                flipCamera()
                return true
            }
        })
        b.glView.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        val alpha = if (visible) 1f else 0f
        listOf(b.topBar, b.bottomBar).forEach { v ->
            if (visible) v.visibility = View.VISIBLE
            v.animate().alpha(alpha).setDuration(250).withEndAction {
                if (!controlsVisible) v.visibility = View.INVISIBLE
            }.start()
        }
        if (!visible) b.settingsPanel.visibility = View.GONE
        b.btnSettings.isActivated = false
    }

    private fun selectEffect(i: Int, scroll: Boolean = true) {
        renderer.effectIndex = i
        adapter.selected = i
        if (scroll) b.effectList.smoothScrollToPosition(i)
        val e = Shaders.EFFECTS[i]
        b.tvEffect.text = e.name
        b.tvEffectDesc.text = e.desc
        b.tvEffect.animate().cancel(); b.tvEffectDesc.animate().cancel()
        b.tvEffect.alpha = 1f; b.tvEffectDesc.alpha = 1f
        b.tvEffect.removeCallbacks(hideEffectName)
        b.tvEffect.postDelayed(hideEffectName, 2200)
    }

    private fun showHint() {
        b.tvEffectDesc.text = getString(R.string.hint)
        b.tvEffect.removeCallbacks(hideEffectName)
        b.tvEffect.postDelayed(hideEffectName, 4500)
    }

    /** Hilo de audio: en cada golpe, el cambio automático de efecto. */
    private fun onBeat() {
        if (!auto) return
        beatCount++
        if (beatCount % autoEvery != 0) return
        runOnUiThread {
            val n = Shaders.EFFECTS.size
            var next = Random.nextInt(n - 1)
            if (next >= renderer.effectIndex) next++
            selectEffect(next)
        }
    }

    // ---------------- Foto y video ----------------

    private fun ensureStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        if (has(Manifest.permission.WRITE_EXTERNAL_STORAGE)) return true
        storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return false
    }

    override fun onPhoto(bitmap: Bitmap) {
        Thread {
            val uri = MediaSaver.savePhoto(this, bitmap)
            bitmap.recycle()
            runOnUiThread { toast(getString(if (uri != null) R.string.photo_saved else R.string.save_failed)) }
        }.start()
    }

    private fun startRecording() {
        if (!ensureStoragePermission()) return
        val withAudio = audio.running
        val file = MediaSaver.newVideoFile(this)
        val onFinished: (java.io.File, Boolean) -> Unit = { f, ok ->
            val uri = if (ok) MediaSaver.saveVideo(this, f) else null
            if (uri == null) f.delete()
            runOnUiThread { toast(getString(if (uri != null) R.string.video_saved else R.string.save_failed)) }
        }
        // 1080x1920 si el celular lo soporta; si no, 720x1280.
        var rec: VideoRecorder? = null
        for ((w, h) in listOf(1080 to 1920, 720 to 1280)) {
            val r = VideoRecorder(file, w, h, withAudio, onFinished)
            try {
                r.prepare()
                rec = r
                break
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "No se pudo grabar a ${w}x$h", e)
            }
        }
        if (rec == null) {
            toast("Este celular no permite grabar video desde la app.")
            return
        }
        audio.pcmListener = rec::feedAudio
        renderer.startRecording(rec)
        recording = true
        recStart = SystemClock.elapsedRealtime()
        b.btnRecord.isActivated = true
        b.tvRec.visibility = View.VISIBLE
        b.tvRec.post(recTicker)
    }

    private fun stopRecording() {
        recording = false
        audio.pcmListener = null
        renderer.stopRecording()
        b.btnRecord.isActivated = false
        b.tvRec.visibility = View.GONE
        b.tvRec.removeCallbacks(recTicker)
    }

    override fun onError(message: String) {
        runOnUiThread { toast(message) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
