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
import android.util.Log
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
 *   3) se dibuja en pantalla y, si se está grabando, también en el codificador de video.
 */
class VisualRenderer(
    private val audio: AudioEngine,
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

    private val startNs = System.nanoTime()

    // Foto y grabación (se piden desde la UI, se atienden en el hilo de render).
    @Volatile private var photoRequested = false
    @Volatile private var pendingRecorder: VideoRecorder? = null
    @Volatile private var stopRequested = false
    private var recorder: VideoRecorder? = null
    private var encSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var lastEncNs = 0L

    fun requestPhoto() { photoRequested = true }
    fun startRecording(rec: VideoRecorder) { stopRequested = false; pendingRecorder = rec }
    fun stopRecording() { stopRequested = true }

    // ---------------- Ciclo de vida GL ----------------

    override fun onSurfaceCreated(gl: GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        // Contexto nuevo: todo lo anterior ya no existe.
        effectProgs.fill(null)
        comp = null; fbos.fill(null)
        recorder = null; encSurface = EGL14.EGL_NO_SURFACE

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

        val t = IntArray(1)
        glGenTextures(1, t, 0)
        oesTex = t[0]
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        surfaceTexture?.release()
        val st = SurfaceTexture(oesTex)
        surfaceTexture = st
        callbacks.onCameraTexture(st)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenW = width; screenH = height
        // Se renderiza con el lado corto a 720 px: buena calidad y fluido en celulares medios.
        val scale = min(1f, 720f / min(width, height))
        renderW = (width * scale).roundToInt().coerceAtLeast(2)
        renderH = (height * scale).roundToInt().coerceAtLeast(2)
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
        val compT = comp ?: return
        try { st.updateTexImage() } catch (e: Exception) { /* la cámara aún no está lista */ }
        st.getTransformMatrix(stMatrix)

        handleRecorderRequests()

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
        // La cámara suele escribir su propio giro (y el espejo de la frontal) en la matriz de la
        // SurfaceTexture. Si ya viene aplicado no hay que volver a girar; si no, se usa el giro
        // que informa CameraX. Parte lineal de la matriz en coordenadas con el eje Y hacia arriba:
        //   x = a·s + b·t,  y = -(c·s + d·t)
        val a = stMatrix[0]; val b = stMatrix[4]; val c = stMatrix[1]; val d = stMatrix[5]
        val baked = !(a > 0f && d < 0f && abs(b) < abs(a) && abs(c) < abs(d))
        val bakedSwap = baked && abs(b) > abs(a)          // giro de 90° ya aplicado: ancho y alto cambiados
        val bakedFlip = (-a * d + b * c) < 0f             // determinante negativo: espejo ya aplicado
        val rq = ((if (baked) 0 else rotQuarter) + userRot) % 4
        glUniform1f(composite.u("uRot"), rq.toFloat())
        glUniform1f(composite.u("uMirror"), if (mirror != bakedFlip) 1f else 0f)
        val swap = (rq % 2 == 1) != bakedSwap
        val upright = if (swap) floatArrayOf(bufH.toFloat(), bufW.toFloat()) else floatArrayOf(bufW.toFloat(), bufH.toFloat())
        glUniform2f(composite.u("uBufRes"), upright[0], upright[1])
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

        // 3) A pantalla
        val flash = if (beatFlash) 0.45f * beat.pow(4) * intensity else 0f
        glViewport(0, 0, screenW, screenH)
        drawFinal(fin, outTex, 1f, 1f, flash)

        if (photoRequested) {
            photoRequested = false
            capturePhoto()
        }

        // 4) Al video (máximo 30 fps), recortado a 9:16.
        recorder?.let { rec ->
            if (now - lastEncNs >= 30_000_000L) {
                lastEncNs = now
                encodeFrame(rec, fin, outTex, flash, now)
            }
        }

        if (prog != null) read = 1 - read
    }

    // ---------------- Grabación ----------------

    private fun handleRecorderRequests() {
        pendingRecorder?.let { rec ->
            pendingRecorder = null
            val display = EGL14.eglGetCurrentDisplay()
            val cfg = currentConfig(display)
            val surf = if (cfg != null) {
                EGL14.eglCreateWindowSurface(display, cfg, rec.inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
            } else EGL14.EGL_NO_SURFACE
            if (surf == null || surf == EGL14.EGL_NO_SURFACE) {
                callbacks.onError("No se pudo preparar la grabación en este dispositivo.")
                rec.stop()
            } else {
                encSurface = surf
                recorder = rec
                lastEncNs = 0L
            }
        }
        if (stopRequested) {
            stopRequested = false
            val rec = recorder ?: return
            recorder = null
            if (encSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(EGL14.eglGetCurrentDisplay(), encSurface)
                encSurface = EGL14.EGL_NO_SURFACE
            }
            rec.stop()
        }
    }

    private fun encodeFrame(rec: VideoRecorder, fin: Program, tex: Int, flash: Float, now: Long) {
        val display = EGL14.eglGetCurrentDisplay()
        val ctx = EGL14.eglGetCurrentContext()
        val draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val readS = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        if (!EGL14.eglMakeCurrent(display, encSurface, encSurface, ctx)) {
            Log.e("VisualRenderer", "eglMakeCurrent (video) falló")
            return
        }
        glViewport(0, 0, rec.width, rec.height)
        // Recorte tipo "cover" de la imagen de render al tamaño del video.
        val ra = renderW.toFloat() / renderH
        val ta = rec.width.toFloat() / rec.height
        val sx: Float; val sy: Float
        if (ta > ra) { sx = 1f; sy = ra / ta } else { sx = ta / ra; sy = 1f }
        drawFinal(fin, tex, sx, sy, flash)
        EGLExt.eglPresentationTimeANDROID(display, encSurface, now - rec.baseNs)
        EGL14.eglSwapBuffers(display, encSurface)
        EGL14.eglMakeCurrent(display, draw, readS, ctx)
    }

    /** La configuración EGL del contexto actual, para crear la superficie del codificador. */
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

    private fun capturePhoto() {
        val w = screenW; val h = screenH
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf)
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

    private fun drawFinal(fin: Program, tex: Int, sx: Float, sy: Float, flash: Float) {
        glUseProgram(fin.id)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, tex)
        glUniform1i(fin.u("uTex"), 0)
        glUniform2f(fin.u("uScale"), sx, sy)
        glUniform1f(fin.u("uFlash"), flash)
        draw(0)
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

    private fun target(w: Int, h: Int, float: Boolean): Target {
        val t = IntArray(1)
        glGenTextures(1, t, 0)
        glBindTexture(GL_TEXTURE_2D, t[0])
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
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

    /** Para cuando la actividad se pausa: suelta la cámara y termina una grabación en curso. */
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
