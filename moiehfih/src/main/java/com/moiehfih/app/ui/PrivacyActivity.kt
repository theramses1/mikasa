package com.moiehfih.app.ui

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Explica qué datos de salud lee la app. Android la muestra desde la pantalla de permisos de
 * Health Connect (es obligatoria para poder pedir esos permisos).
 */
class PrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pal.dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val column = vertical { setPadding(dp(20), dp(24), dp(20), dp(24)) }
        column.setBackgroundColor(Pal.bg)
        column.text("Privacidad de tus datos de salud", 22f, bold = true).margins(bottom = 12)
        column.card { privacyText(this) }
        column.button("Entendido", Btn.PRIMARY) { finish() }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Pal.bg)
            addView(column, LinearLayout.LayoutParams(-1, -2))
        })
    }

    companion object {
        fun privacyText(root: LinearLayout) {
            for (line in listOf(
                "MoiehFih lee de Health Connect, solo si tú lo permites: pasos, calorías activas, distancia, sesiones de ejercicio, " +
                    "frecuencia cardiaca, frecuencia cardiaca en reposo, sueño, peso, oxigenación (SpO₂) y presión arterial.",
                "Los usa para mostrarte tu actividad y signos vitales, registrar tu cardio y completar tu peso.",
                "Los datos se guardan únicamente en tu teléfono. La app no tiene servidores, no usa internet y no comparte nada con nadie.",
                "Puedes quitar los permisos cuando quieras desde Health Connect, y borrar todo desde Ajustes → Borrar todos los datos.",
            )) {
                root.text(line, 14f, Pal.text).margins(bottom = 10)
            }
        }
    }
}
