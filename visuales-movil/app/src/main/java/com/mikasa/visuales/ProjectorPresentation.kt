package com.mikasa.visuales

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Ventana a pantalla completa en una pantalla externa (proyector o TV por HDMI, o pantalla
 * inalámbrica). Solo muestra el video: el render dibuja en su Surface con el mismo contexto
 * OpenGL, y el celular se queda con los controles.
 */
class ProjectorPresentation(
    context: Context,
    display: Display,
    private val glView: GLSurfaceView,
    private val renderer: VisualRenderer,
) : Presentation(context, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val view = SurfaceView(context).apply { setBackgroundColor(Color.BLACK) }
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                view.background = null
                renderer.setProjectorSurface(holder.surface, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // El render tiene que soltar la Surface antes de que se destruya.
                val done = CountDownLatch(1)
                glView.queueEvent {
                    renderer.detachProjectorNow()
                    done.countDown()
                }
                done.await(500, TimeUnit.MILLISECONDS)
                renderer.setProjectorSurface(null, 0, 0)
            }
        })
        setContentView(view)
    }
}
