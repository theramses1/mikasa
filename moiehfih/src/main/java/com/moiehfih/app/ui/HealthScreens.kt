package com.moiehfih.app.ui

import android.view.View
import android.widget.LinearLayout
import com.moiehfih.app.data.HealthDay
import com.moiehfih.app.health.HealthSync
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun fmtSleep(min: Long?): String = if (min == null) "—" else "${min / 60} h ${min % 60} min"

/** Tarjeta de actividad y signos vitales del día (o invitación a conectar el reloj). */
fun LinearLayout.healthCard(act: MainActivity, date: LocalDate) {
    val data = act.data
    val h = data.health[date]
    card({ act.push(HealthScreen(act)) }) {
        row {
            h3("❤ Actividad y signos vitales").weighted()
            if (data.healthConnected) pill("Health Connect", Pal.good)
        }.margins(bottom = 8)
        when {
            h != null -> {
                row {
                    stat(Fmt.n(h.steps?.toInt()), "Pasos", size = 18f).weighted()
                    stat(h.restingHr?.let { "$it" } ?: "—", "FC reposo", size = 18f).weighted()
                    stat(h.sleepMin?.let { "${Fmt.n(it / 60.0, 1)} h" } ?: "—", "Sueño", size = 18f).weighted()
                }
                row {
                    stat(Fmt.n(h.activeKcal), "kcal activas", size = 18f).weighted()
                    stat(h.hrAvg?.let { "$it" } ?: "—", "FC media", size = 18f).weighted()
                    stat(h.spo2?.let { "${Fmt.n(it)} %" } ?: "—", "SpO₂", size = 18f).weighted()
                }.margins(top = 8)
            }
            data.healthConnected -> muted("Aún no hay datos de hoy. Toca para sincronizar.")
            else -> muted("Conecta Google Fit, Fitbit, Samsung Health o tu reloj (vía Health Connect) para ver pasos, pulso, sueño y más.")
        }
    }
}

class HealthScreen(act: MainActivity) : Screen(act) {
    override val title = "Salud y relojes"

    override fun build(root: LinearLayout) {
        root.card {
            h2("Health Connect")
            muted(
                "Health Connect es el centro de datos de salud de Android. Google Fit, Fitbit, Samsung Health, Garmin, " +
                    "Wear OS y otras apps de relojes guardan ahí tus pasos, pulso, sueño y entrenamientos; MoiehFih los lee desde ahí.",
            ).margins(top = 4, bottom = 10)
            when {
                !HealthSync.isSupported -> {
                    text("Tu teléfono tiene una versión de Android anterior a la 14, que no trae Health Connect integrado.", 14f, Pal.warn)
                }
                data.healthConnected -> {
                    val last = data.healthLastSync?.let {
                        DateTimeFormatter.ofPattern("d MMM, HH:mm", Fmt.locale).format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
                    }
                    pill(if (act.healthSyncing) "Sincronizando…" else "Conectado", Pal.good)
                    muted("Última sincronización: ${last ?: "nunca"} · ${HealthSync.grantedPermissions(context).size} de ${HealthSync.PERMISSIONS.size} permisos").margins(top = 6, bottom = 10)
                    button("Sincronizar ahora", Btn.PRIMARY) { act.syncHealth(quiet = false) }
                    row {
                        button("Permisos") { act.openHealthConnectSettings() }.weighted()
                        add(View(context), width = dp(10))
                        button("Desconectar", Btn.DANGER) { act.disconnectHealth() }.weighted()
                    }.margins(top = 10)
                }
                else -> {
                    button("Conectar con Health Connect", Btn.PRIMARY) { act.connectHealth() }
                    muted("Android te preguntará qué datos compartir; puedes elegir solo algunos.", 12f).margins(top = 6)
                }
            }
        }

        if (HealthSync.isSupported) {
            root.card {
                h3("¿Usas Google Fit u otra app?")
                for (line in listOf(
                    "Google Fit: abre Fit → Perfil → ⚙ → «Sincronizar Fit con Health Connect».",
                    "Fitbit, Samsung Health, Garmin Connect, Zepp, Huawei Health…: activa en su app la opción de compartir con Health Connect.",
                    "MoiehFih importa sesiones de cardio (correr, caminar, bici, natación…), no las de pesas: esas se registran en Entreno.",
                )) muted("• $line", 13f).margins(top = 6)
            }
        }

        val today = LocalDate.now()
        val days = (0L until 14L).map { today.minusDays(it) }.mapNotNull { data.health[it] }
        if (days.isEmpty()) return

        val week = days.filter { it.date > today.minusDays(7) }
        fun avgL(f: (HealthDay) -> Long?) = week.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()
        root.sectionTitle("Promedio 7 días")
        root.statCards(
            Triple(Fmt.n(avgL { it.steps }), "Pasos", Pal.text),
            Triple(Fmt.n(avgL { it.restingHr }), "FC reposo", Pal.text),
            Triple(avgL { it.sleepMin }?.let { fmtSleep(it.toLong()) } ?: "—", "Sueño", Pal.text),
        )

        val chronological = (13L downTo 0L).map { today.minusDays(it) }
        val labels = shortDate(chronological.first()) to shortDate(chronological.last())
        root.card {
            h3("Pasos (14 días)").margins(bottom = 8)
            add(LineChartView(context, listOf(LineChartView.Series(chronological.map { data.health[it]?.steps?.toDouble() }, Pal.accent, dots = true)), labels, decimals = 0), height = dp(160))
        }
        root.card {
            h3("Frecuencia cardiaca en reposo").margins(bottom = 8)
            add(LineChartView(context, listOf(LineChartView.Series(chronological.map { data.health[it]?.restingHr?.toDouble() }, Pal.bad, dots = true)), labels, decimals = 0), height = dp(160))
        }

        root.sectionTitle("Por día")
        root.card {
            for (h in days) {
                val parts = listOfNotNull(
                    h.steps?.let { "👣 ${Fmt.n(it.toInt())}" },
                    h.activeKcal?.let { "🔥 ${Fmt.n(it)} kcal" },
                    h.distanceM?.let { "📏 ${Fmt.n(it / 1000, 1)} km" },
                    h.hrAvg?.let { "❤ ${h.hrMin ?: "–"}/${it}/${h.hrMax ?: "–"} lpm" },
                    h.restingHr?.let { "reposo $it lpm" },
                    h.sleepMin?.let { "😴 ${fmtSleep(it)}" },
                    h.spo2?.let { "SpO₂ ${Fmt.n(it)} %" },
                    if (h.bpSys != null && h.bpDia != null) "🩺 ${Fmt.n(h.bpSys)}/${Fmt.n(h.bpDia)}" else null,
                    h.weightKg?.let { "⚖ ${Fmt.n(it, 1)} kg" },
                )
                listRow(Fmt.date(h.date), parts.joinToString(" · "))
            }
            muted("Pulso: mínimo/promedio/máximo del día.", 11.5f).margins(top = 6)
        }

        root.card {
            h3("Privacidad")
            PrivacyActivity.privacyText(this)
        }
    }
}
