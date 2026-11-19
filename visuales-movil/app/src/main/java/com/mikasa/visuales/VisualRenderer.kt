package com.mikasa.visuales

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Motor OpenGL ES 3 (equivale a visuales-concierto/js/renderer.js):
 *   1) la cámara llega como textura externa (SurfaceTexture) y se endereza/recorta -> comp,
 *   2) se aplica el efecto con feedback (ping-pong de framebuffers),
 *   3) pase final con la capa de letras -> pantalla del celular, video y proyector externo.
 */
class VisualRenderer(
    private val audio: AudioEngine,
    private val lyrics: LyricsEngine,
    private val callbacks: Callbacks,
) : GLSurfaceView.Renderer {

    interface Callbacks {
        /** Hay una SurfaceTexture nueva para la cámara (hilo de render). */
        fun onCameraTexture(texture: SurfaceTexture)
        fun onPhoto(bitmap: Bitmap)
        fun onError(message: String)
    }

    // Controles (se escriben desde la UI).
    @Volatile var effectIndex = 0
    @Volatile var intensity = 0.7f
    @Volatile var hue = 0.6f
    /** true = ver la imagen en espejo (lo normal con la cámara frontal). */
    @Volatile var mirror = false
    /** Giro extra de 90° elegido por el usuario (0..3), por si algún celular lo necesita. */
    @Volatile var userRot = 0
    /** Giro de la pantalla del celular en grados (0, 90, 180, 270). */
    @Volatile var displayRotation = 0
    @Volatile var beatFlash = false
    @Volatile private var rotQuarter = 1
    @Volatile private var bufW = 1280
    @Volatile private var bufH = 720

    /** Datos del frame de la cámara: tamaño del buffer y giro que necesita (en grados horarios). */
    fun setCameraFrame(width: Int, height: Int, rotationDegrees: Int) {
        bufW = width; bufH = height
        rotQuarter = ((rotationDegrees / 90) % 4 + 4) % 4
    }

    private class Program(val id: Int) {
        private val cache = HashMap<String, Int>()
        fun u(name: String) = cache.getOrPut(name) { glGetUniformLocation(id, name) }
    }

    private class Target(val fb: Int, val tex: Int)

    /** Una salida extra (codificador de video o proyector) dibujada con el mismo contexto. */
    private class Output(val surface: EGLSurface, val width: Int, val height: Int)

    private lateinit var quad: FloatBuffer
    private var compositeProg: Program? = null
    private var finalProg: Program? = null
    private val effectProgs = arrayOfNulls<Program>(Shaders.EFFECTS.size)
    private var vert = 0

    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val stMatrix = FloatArray(16)

    private var screenW = 1
    private var screenH = 1
    private var renderW = 1
    private var renderH = 1
    private var comp: Target? = null
    private val fbos = arrayOfNulls<Target>(2)
    private var read = 0
    private var halfFloat = false
    private var lyrTex = 0
    private var lyrW = 0
    private var lyrH = 0

    private val startNs = System.nanoTime()

    // Foto y grabación (se piden desde la UI, se atienden en el hilo de render).
    @Volatile private var photoRequested = false
    @Volatile private var pendingRecorder: VideoRecorder? = null
    @Volatile private var stopRequested = false
    private var recorder: VideoRecorder? = null
    private var encoder: Output? = null
    private var lastEncNs = 0L

    // Proyector (pantalla externa por HDMI o inalámbrica).
    private var projector: Output? = null

    fun requestPhoto() { photoRequested = true }
    fun startRecording(rec: VideoRecorder) { stopRequested = false; pendingRecorder = rec }
    fun stopRecording() { stopRequested = true }

    /** Tamaño en que se renderiza (para elegir la orientación del video). Hilo cualquiera. */
    @Volatile var renderAspect = 9f / 16f
        private set

    // ---------------- Ciclo de vida GL ----------------

    override fun onSurfaceCreated(gl: GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        // Contexto nuevo: todo lo anterior ya no existe.
        effectProgs.fill(null)
        comp = null; fbos.fill(null)
        recorder = null; encoder = null; projector = null
        lyrW = 0; lyrH = 0

        quad = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)); position(0)
        }
        halfFloat = (glGetString(GL_EXTENSIONS) ?: "").contains("GL_EXT_color_buffer_half_float")
        try {
            vert = compile(GL_VERTEX_SHADER, Shaders.VERT, "vértice")
            compositeProg = link(Shaders.COMPOSITE_FRAG, "cámara")
            finalProg = link(Shaders.FINAL_FRAG, "final")
        } catch (e: RuntimeException) {
            callbacks.onError(e.message ?: "Error de OpenGL")
        }

        val t = IntArray(2)
        glGenTextures(2, t, 0)
        oesTex = t[0]
        lyrTex = t[1]
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        texParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        glBindTexture(GL_TEXTURE_2D, lyrTex)
        texParams(GL_TEXTURE_2D)
        surfaceTexture?.release()
        val st = SurfaceTexture(oesTex)
        surfaceTexture = st
        callbacks.onCameraTexture(st)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenW = width; screenH = height
        allocTargets()
    }

    /**
     * Se renderiza con el lado corto a 720 px (buena calidad y fluido en celulares medios), con la
     * forma de la pantalla del celular o, si hay proyector, con la forma del proyector.
     */
    private fun allocTargets() {
        val p = projector
        val (aw, ah) = if (p != null) p.width to p.height else screenW to screenH
        val scale = 720f / min(aw, ah)
        val w = (aw * scale).roundToInt().coerceAtLeast(2)
        val h = (ah * scale).roundToInt().coerceAtLeast(2)
        if (w == renderW && h == renderH && comp != null) return
        renderW = w; renderH = h
        renderAspect = w.toFloat() / h
        listOfNotNull(comp, *fbos).forEach { deleteTarget(it) }
        comp = target(renderW, renderH, false)
        fbos[0] = target(renderW, renderH, halfFloat)
        fbos[1] = target(renderW, renderH, halfFloat)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        val composite = compositeProg ?: return
        val fin = finalProg ?: return
        try { st.updateTexImage() } catch (e: Exception) { /* la cámara aún no está lista */ }
        st.getTransformMatrix(stMatrix)

        handleRecorderRequests()
        handleProjectorRequests()
        val compT = comp ?: return

        val now = System.nanoTime()
        val time = (now - startNs) / 1e9f
        val beat = audio.beatEnvelope(now)

        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, quad)
        glDisable(GL_BLEND)
        glViewport(0, 0, renderW, renderH)

        // 1) Cámara -> comp
        glUseProgram(composite.id)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        glUniform1i(composite.u("uCamTex"), 0)
        glUniformMatrix4fv(composite.u("uStMatrix"), 1, false, stMatrix, 0)
        setCameraOrientation(composite)
        glUniform2f(composite.u("uRes"), renderW.toFloat(), renderH.toFloat())
        draw(compT.fb)

        // 2) Efecto -> framebuffer (lee el frame anterior para feedback)
        val src = fbos[read]!!
        val dst = fbos[1 - read]!!
        val prog = effectProgram(effectIndex)
        if (prog != null) {
            glUseProgram(prog.id)
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, compT.tex)
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, src.tex)
            glUniform1i(prog.u("uCam"), 0)
            glUniform1i(prog.u("uPrev"), 1)
            glUniform2f(prog.u("uRes"), renderW.toFloat(), renderH.toFloat())
            glUniform1f(prog.u("uTime"), time)
            glUniform1f(prog.u("uBass"), audio.bass)
            glUniform1f(prog.u("uMid"), audio.mid)
            glUniform1f(prog.u("uHigh"), audio.high)
            glUniform1f(prog.u("uLevel"), audio.level)
            glUniform1f(prog.u("uBeat"), beat)
            glUniform1f(prog.u("uIntensity"), intensity)
            glUniform1f(prog.u("uHue"), hue)
            draw(dst.fb)
        }
        val outTex = if (prog != null) dst.tex else compT.tex

        // 3) Capa de letras (a media resolución, como en la versión web)
        val lyrOn = uploadLyrics(beat)

        val fp = FinalParams(outTex, compT.tex, lyrOn, time, beat,
            if (beatFlash) 0.45f * beat.pow(4) * intensity else 0f)

        // 4) A la pantalla del celular: entera, con bandas si la forma no coincide.
        glViewport(0, 0, screenW, screenH)
        val (cx, cy) = contain(renderW, renderH, screenW, screenH)
        drawFinal(fin, fp, cx, cy, 0)

        if (photoRequested) {
            photoRequested = false
            capturePhoto(fin, fp)
        }

        // 5) Al proyector: llena la pantalla (recorta lo que sobre).
        projector?.let { p ->
            val (sx, sy) = cover(renderW, renderH, p.width, p.height)
            drawToOutput(p, fin, fp, sx, sy, null)
        }

        // 6) Al video (máximo 30 fps), recortado al tamaño del video.
        val rec = recorder
        val enc = encoder
        if (rec != null && enc != null && now - lastEncNs >= 30_000_000L) {
            lastEncNs = now
            val (sx, sy) = cover(renderW, renderH, enc.width, enc.height)
            drawToOutput(enc, fin, fp, sx, sy, now - rec.baseNs)
        }

        if (prog != null) read = 1 - read
    }

    /**
     * La cámara suele escribir su propio giro (y el espejo de la frontal) en la matriz de la
     * SurfaceTexture. Si ya viene aplicado solo falta compensar el giro de la pantalla; si no, se
     * usa el giro que informa CameraX (que ya incluye el de la pantalla).
     */
    private fun setCameraOrientation(p: Program) {
        // Parte lineal de la matriz con el eje Y hacia arriba:  x = a·s + b·t,  y = -(c·s + d·t)
        val a = stMatrix[0]; val b = stMatrix[4]; val c = stMatrix[1]; val d = stMatrix[5]
        val baked = !(a > 0f && d < 0f && abs(b) < abs(a) && abs(c) < abs(d))
        val bakedSwap = baked && abs(b) > abs(a)   // giro de 90° ya aplicado: ancho y alto cambiados
        val bakedFlip = (-a * d + b * c) < 0f      // determinante negativo: espejo ya aplicado
        val base = if (baked) ((360 - displayRotation) / 90) % 4 else rotQuarter
        val rq = (base + userRot) % 4
        glUniform1f(p.u("uRot"), rq.toFloat())
        // Espejo deseado en x. Si la imagen ya viene en espejo (horizontal en la imagen derecha),
        // tras girarla 90° ese espejo queda vertical en pantalla: se compensa en el eje que toque.
        var fx = mirror
        var fy = false
        if (bakedFlip) { if (rq % 2 == 0) fx = !fx else fy = !fy }
        glUniform2f(p.u("uFlip"), if (fx) 1f else 0f, if (fy) 1f else 0f)
        val swap = (rq % 2 == 1) != bakedSwap
        if (swap) glUniform2f(p.u("uBufRes"), bufH.toFloat(), bufW.toFloat())
        else glUniform2f(p.u("uBufRes"), bufW.toFloat(), bufH.toFloat())
    }

    private class FinalParams(
        val tex: Int, val compTex: Int, val lyrOn: Boolean, val time: Float, val beat: Float, val flash: Float,
    )

    private fun uploadLyrics(beat: Float): Boolean {
        val w = (renderW / 2).coerceAtLeast(2)
        val h = (renderH / 2).coerceAtLeast(2)
        val drew = lyrics.draw(w, h, beat)
        if (!drew) return false
        val bmp = lyrics.bitmapFor(w, h)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, lyrTex)
        if (w != lyrW || h != lyrH) {
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0)
            lyrW = w; lyrH = h
        } else {
            GLUtils.texSubImage2D(GL_TEXTURE_2D, 0, 0, 0, bmp)
        }
        return true
    }

    // ---------------- Salidas extra (video y proyector) ----------------

    private fun drawToOutput(o: Output, fin: Program, fp: FinalParams, sx: Float, sy: Float, ptsNs: Long?) {
        val display = EGL14.eglGetCurrentDisplay()
        val ctx = EGL14.eglGetCurrentContext()
        val draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val readS = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        if (!EGL14.eglMakeCurrent(display, o.surface, o.surface, ctx)) {
            Log.e("VisualRenderer", "eglMakeCurrent (salida extra) falló")
            return
        }
        glViewport(0, 0, o.width, o.height)
        drawFinal(fin, fp, sx, sy, 0)
        if (ptsNs != null) EGLExt.eglPresentationTimeANDROID(display, o.surface, ptsNs)
        EGL14.eglSwapBuffers(display, o.surface)
        EGL14.eglMakeCurrent(display, draw, readS, ctx)
    }

    private fun createOutput(surface: Surface, w: Int, h: Int, noVsync: Boolean): Output? {
        val display = EGL14.eglGetCurrentDisplay()
        val cfg = currentConfig(display) ?: return null
        val s = EGL14.eglCreateWindowSurface(display, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (s == null || s == EGL14.EGL_NO_SURFACE) return null
        if (noVsync) {
            // Que el proyector no frene a la pantalla del celular esperando su propio refresco.
            val ctx = EGL14.eglGetCurrentContext()
            val draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
            val readS = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
            if (EGL14.eglMakeCurrent(display, s, s, ctx)) {
                EGL14.eglSwapInterval(display, 0)
                EGL14.eglMakeCurrent(display, draw, readS, ctx)
            }
        }
        return Output(s, w, h)
    }

    private fun destroyOutput(o: Output?) {
        if (o != null) EGL14.eglDestroySurface(EGL14.eglGetCurrentDisplay(), o.surface)
    }

    private fun handleRecorderRequests() {
        pendingRecorder?.let { rec ->
            pendingRecorder = null
            val out = createOutput(rec.inputSurface, rec.width, rec.height, false)
            if (out == null) {
                callbacks.onError("No se pudo preparar la grabación en este dispositivo.")
                rec.stop()
            } else {
                encoder = out
                recorder = rec
                lastEncNs = 0L
            }
        }
        if (stopRequested) {
            stopRequested = false
            val rec = recorder ?: return
            recorder = null
            destroyOutput(encoder)
            encoder = null
            rec.stop()
        }
    }

    // El proyector se conecta y desconecta desde la UI (SurfaceView de la Presentation).
    private class ProjectorRequest(val surface: Surface?, val w: Int, val h: Int)
    @Volatile private var projectorRequest: ProjectorRequest? = null

    /** Llamar desde la UI. surface = null para desconectar. */
    fun setProjectorSurface(surface: Surface?, w: Int, h: Int) {
        projectorRequest = ProjectorRequest(surface, w, h)
    }

    /** Llamar en el hilo de render (queueEvent) antes de que la Surface del proyector se destruya. */
    fun detachProjectorNow() {
        projectorRequest = null
        destroyOutput(projector)
        projector = null
        allocTargets()
    }

    private fun handleProjectorRequests() {
        val req = projectorRequest ?: run { if (comp == null) allocTargets(); return }
        projectorRequest = null
        destroyOutput(projector)
        projector = null
        if (req.surface != null && req.surface.isValid) {
            projector = createOutput(req.surface, req.w, req.h, true)
            if (projector == null) callbacks.onError("No se pudo usar la pantalla externa.")
        }
        allocTargets()
    }

    /** La configuración EGL del contexto actual, para crear superficies extra. */
    private fun currentConfig(display: android.opengl.EGLDisplay): EGLConfig? {
        val id = IntArray(1)
        EGL14.eglQueryContext(display, EGL14.eglGetCurrentContext(), EGL14.EGL_CONFIG_ID, id, 0)
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        val ok = EGL14.eglChooseConfig(
            display, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0, configs, 0, 1, num, 0
        )
        return if (ok && num[0] > 0) configs[0] else null
    }

    // ---------------- Foto ----------------

    /** La foto se saca de la imagen de render completa (sin bandas negras), con las letras. */
    private fun capturePhoto(fin: Program, fp: FinalParams) {
        val w = renderW; val h = renderH
        val t = target(w, h, false)
        glViewport(0, 0, w, h)
        drawFinal(fin, fp, 1f, 1f, t.fb)
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        deleteTarget(t)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, screenW, screenH)
        buf.rewind()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        // OpenGL tiene el origen abajo: se voltea la imagen.
        val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
        val flipped = Bitmap.createBitmap(bmp, 0, 0, w, h, m, false)
        bmp.recycle()
        callbacks.onPhoto(flipped)
    }

    // ---------------- Utilidades GL ----------------

    /** Escala para ver la imagen entera (bandas negras si sobra sitio). */
    private fun contain(rw: Int, rh: Int, tw: Int, th: Int): Pair<Float, Float> {
        val ra = rw.toFloat() / rh; val ta = tw.toFloat() / th
        return if (ta > ra) (ta / ra) to 1f else 1f to (ra / ta)
    }

    /** Escala para llenar la salida (recorta lo que sobre). */
    private fun cover(rw: Int, rh: Int, tw: Int, th: Int): Pair<Float, Float> {
        val ra = rw.toFloat() / rh; val ta = tw.toFloat() / th
        return if (ta > ra) 1f to (ra / ta) else (ta / ra) to 1f
    }

    private fun drawFinal(fin: Program, fp: FinalParams, sx: Float, sy: Float, fb: Int) {
        glUseProgram(fin.id)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, fp.tex)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, lyrTex)
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, fp.compTex)
        glUniform1i(fin.u("uTex"), 0)
        glUniform1i(fin.u("uLyr"), 1)
        glUniform1i(fin.u("uComp"), 2)
        glUniform2f(fin.u("uScale"), sx, sy)
        glUniform2f(fin.u("uRes"), renderW.toFloat(), renderH.toFloat())
        glUniform1f(fin.u("uFlash"), fp.flash)
        glUniform1f(fin.u("uLyrOn"), if (fp.lyrOn) 1f else 0f)
        glUniform1f(fin.u("uLyrFx"), lyrics.effect.toFloat())
        glUniform1f(fin.u("uTime"), fp.time)
        glUniform1f(fin.u("uBass"), audio.bass)
        glUniform1f(fin.u("uBeat"), fp.beat)
        glUniform1f(fin.u("uHue"), hue)
        draw(fb)
    }

    private fun draw(fb: Int) {
        glBindFramebuffer(GL_FRAMEBUFFER, fb)
        glDrawArrays(GL_TRIANGLES, 0, 3)
    }

    /** Compila el efecto la primera vez que se usa (así la app arranca rápido). */
    private fun effectProgram(i: Int): Program? {
        val idx = i.coerceIn(0, Shaders.EFFECTS.size - 1)
        effectProgs[idx]?.let { return it }
        val e = Shaders.EFFECTS[idx]
        return try {
            link(Shaders.HEADER + e.frag + Shaders.MAIN, e.name).also { effectProgs[idx] = it }
        } catch (ex: RuntimeException) {
            callbacks.onError(ex.message ?: "Error en ${e.name}")
            effectIndex = 0
            null
        }
    }

    private fun compile(type: Int, src: String, name: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src)
        glCompileShader(s)
        val ok = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = glGetShaderInfoLog(s)
            glDeleteShader(s)
            throw RuntimeException("Error compilando el shader \"$name\":\n$log")
        }
        return s
    }

    private fun link(frag: String, name: String): Program {
        val fs = compile(GL_FRAGMENT_SHADER, frag, name)
        val p = glCreateProgram()
        glAttachShader(p, vert)
        glAttachShader(p, fs)
        glBindAttribLocation(p, 0, "aPos")
        glLinkProgram(p)
        glDeleteShader(fs)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = glGetProgramInfoLog(p)
            glDeleteProgram(p)
            throw RuntimeException("Error enlazando \"$name\": $log")
        }
        return Program(p)
    }

    private fun texParams(target: Int) {
        glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }

    private fun target(w: Int, h: Int, float: Boolean): Target {
        val t = IntArray(1)
        glGenTextures(1, t, 0)
        glBindTexture(GL_TEXTURE_2D, t[0])
        texParams(GL_TEXTURE_2D)
        if (float) glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GL_RGBA, GL_HALF_FLOAT, null)
        else glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        val f = IntArray(1)
        glGenFramebuffers(1, f, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, f[0])
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t[0], 0)
        if (float && glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            // Algunos GPU anuncian la extensión pero no la soportan bien: se usa 8 bits.
            glDeleteFramebuffers(1, f, 0)
            glDeleteTextures(1, t, 0)
            halfFloat = false
            return target(w, h, false)
        }
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        return Target(f[0], t[0])
    }

    private fun deleteTarget(t: Target) {
        glDeleteFramebuffers(1, intArrayOf(t.fb), 0)
        glDeleteTextures(1, intArrayOf(t.tex), 0)
    }

    /** Para cuando la actividad se pausa: termina una grabación en curso. */
    fun onPauseGl() {
        if (recorder != null) {
            stopRequested = true
            handleRecorderRequests()
        }
    }

    companion object {
        /**
         * Elige una configuración EGL "grabable" (EGL_RECORDABLE_ANDROID) para poder dibujar
         * también en la superficie del codificador de video con el mismo contexto.
         */
        val CONFIG_CHOOSER = GLSurfaceView.EGLConfigChooser { egl, display ->
            fun choose(recordable: Boolean): javax.microedition.khronos.egl.EGLConfig? {
                val attrs = mutableListOf(
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 0,
                    EGL10.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                )
                if (recordable) attrs += listOf(0x3142, 1) // EGL_RECORDABLE_ANDROID
                attrs += EGL10.EGL_NONE
                val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(1)
                val num = IntArray(1)
                egl.eglChooseConfig(display, attrs.toIntArray(), configs, 1, num)
                return if (num[0] > 0) configs[0] else null
            }
            choose(true) ?: choose(false) ?: throw IllegalStateException("OpenGL ES 3 no disponible")
        }
    }
}
