package com.moiehfih.app.ui

import android.view.View
import android.widget.LinearLayout
import com.moiehfih.app.data.JournalEntry
import com.moiehfih.app.logic.Calc
import java.time.LocalDate

class WeightScreen(act: MainActivity) : Screen(act) {
    override val title = "Peso corporal"
    private var range = 1 // índice en RANGES
    private var entryDate = LocalDate.now()

    private val ranges = listOf("1 mes" to 30L, "3 meses" to 90L, "1 año" to 365L, "Todo" to 0L)

    override fun build(root: LinearLayout) {
        val today = LocalDate.now()
        val d = data.derived(today)

        root.card {
            h3("Registrar peso").margins(bottom = 8)
            row {
                button(Fmt.date(entryDate)) { act.pickDate(entryDate) { entryDate = it; act.refresh() } }.weighted(1.2f)
                add(View(context), width = dp(8))
                val kg = add(act.input(Fmt.plain(data.weights[entryDate]), "kg", InputKind.DECIMAL), width = 0, weight = 1f)
                add(View(context), width = dp(8))
                button("Guardar", Btn.PRIMARY) {
                    val v = Fmt.parse(kg.text)
                    if (v == null || v !in 25.0..400.0) return@button act.toast("Peso no válido.")
                    data.setWeight(entryDate, v)
                    act.toast("Peso guardado: ${Fmt.n(v, 1)} kg")
                    act.refresh()
                }
            }
        }

        fun change(days: Long): Double? {
            val past = Calc.trendOn(d.trend, today.minusDays(days)) ?: return null
            return d.trendNow?.minus(past)
        }
        val c7 = change(7)
        val goalSign = Math.signum(data.goal.ratePct)
        root.statCards(
            Triple(Fmt.n(d.trendNow, 1), "Tendencia (kg)", Pal.text),
            Triple(Fmt.signed(c7, 2), "7 días", if (c7 != null && goalSign != 0.0 && Math.signum(c7) == goalSign) Pal.good else Pal.text),
            Triple(Fmt.signed(change(30), 1), "30 días", Pal.text),
        )

        root.segmented(ranges.map { it.first }, range) { range = it; act.refresh() }
        val days = ranges[range].second
        val from = if (days > 0) today.minusDays(days - 1) else d.trend.firstOrNull()?.date ?: today
        val trendIn = d.trend.filter { it.date >= from }
        val labels = if (trendIn.isNotEmpty()) shortDate(trendIn.first().date) to shortDate(trendIn.last().date) else null

        root.card {
            h3("Peso").margins(bottom = 8)
            add(
                LineChartView(
                    context,
                    listOf(
                        LineChartView.Series(trendIn.map { it.scale }, Pal.scale, line = false, dots = true),
                        LineChartView.Series(trendIn.map { it.trend }, Pal.accent),
                    ),
                    labels,
                ),
                height = dp(200),
            )
            legend(this, listOf("Báscula" to Pal.scale, "Tendencia" to Pal.accent))
            muted("La tendencia suaviza las variaciones diarias de agua, sal y digestión.", 12f).margins(top = 6)
        }

        val expIn = d.exp.filter { it.date >= from }
        val intake = data.intakeByDay()
        root.card {
            row {
                h3("Gasto energético vs ingesta").weighted()
                pill("${Fmt.n(d.tdee)} kcal", Pal.good)
            }.margins(bottom = 8)
            add(
                LineChartView(
                    context,
                    listOf(
                        LineChartView.Series(expIn.map { intake[it.date] }, Pal.warn, line = false, dots = true),
                        LineChartView.Series(expIn.map { it.tdee }, Pal.good),
                    ),
                    if (expIn.isNotEmpty()) shortDate(expIn.first().date) to shortDate(expIn.last().date) else null,
                    decimals = 0,
                ),
                height = dp(200),
            )
            legend(this, listOf("Gasto estimado" to Pal.good, "Ingesta" to Pal.warn))
            muted("Se calcula con tu ingesta registrada y el cambio de tu peso de tendencia. Mientras más días registres, más precisa.", 12f).margins(top = 6)
        }

        val entries = data.weights.keys.filter { it >= from }.sortedDescending()
        root.card {
            h3("Registros")
            if (entries.isEmpty()) muted("Sin registros en este rango.").margins(top = 6)
            for (k in entries.take(21)) {
                listRow(
                    "${Fmt.n(data.weights[k], 1)} kg",
                    Fmt.date(k),
                    "tend. ${Fmt.n(Calc.trendOn(d.trend, k), 1)}",
                    onDelete = { act.confirm("¿Eliminar el peso de ${Fmt.date(k)}?", "Eliminar") { data.deleteWeight(k); act.refresh() } },
                    onClick = { entryDate = k; act.refresh(scrollTop = true) },
                )
            }
            if (entries.size > 21) muted("Mostrando los 21 más recientes de ${entries.size}.", 12f).margins(top = 6)
        }
    }

    private fun legend(parent: LinearLayout, items: List<Pair<String, Int>>) {
        val r = parent.row {}
        r.margins(top = 6)
        for ((label, color) in items) {
            r.add(View(parent.context).apply { background = rounded(color, parent.context.dp(2).toFloat()) }, width = parent.context.dp(12), height = parent.context.dp(3)).margins(end = 5)
            r.add(android.widget.TextView(parent.context).apply {
                text = label
                setTextColor(Pal.muted)
                textSize = 12f
            }, width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT).margins(end = 14)
        }
    }
}

class JournalScreen(act: MainActivity) : Screen(act) {
    override val title = "Bitácora"
    private var date = LocalDate.now()

    override fun build(root: LinearLayout) {
        val today = LocalDate.now()
        root.dateNav(act, date) { date = it; act.refresh() }
        val tot = data.dayTotals(date)
        val tg = data.currentTargets()
        val logged = data.entries[date]?.isNotEmpty() == true
        val trend = data.derived(today).trend
        val ws = data.finishedWorkouts().filter { it.date == date }
        root.card {
            h3("Resumen del día").margins(bottom = 4)
            listRow("Calorías", trailing = if (logged) "${Fmt.n(tot.kcal)} / ${Fmt.n(tg.kcal)}" else "—")
            listRow("Macros (P / C / G)", trailing = if (logged) "${Fmt.n(tot.p)} / ${Fmt.n(tot.c)} / ${Fmt.n(tot.f)} g" else "—")
            listRow(
                "Peso", trailing = data.weights[date]?.let { "${Fmt.n(it, 1)} kg" } ?: "—",
                trailingSub = Calc.trendOn(trend, date)?.let { "tend. ${Fmt.n(it, 1)}" },
            )
            listRow("Entreno", trailing = if (ws.isEmpty()) "Descanso" else ws.joinToString(", ") { it.name })
        }

        val j = data.journal[date] ?: JournalEntry(date)
        var sleepQ = j.sleepQ
        var energy = j.energy
        var mood = j.mood
        var hunger = j.hunger
        var stress = j.stress
        root.card {
            val r = row {}
            val sleep = r.add(context.vertical(), width = 0, weight = 1f).field("Horas de sueño", Fmt.plain(j.sleepH), InputKind.DECIMAL)
            r.add(View(context), width = dp(10))
            val steps = r.add(context.vertical(), width = 0, weight = 1f).field("Pasos", j.steps?.toString() ?: "", InputKind.INT)
            scale5("Calidad del sueño", sleepQ) { sleepQ = it }
            scale5("Energía", energy) { energy = it }
            scale5("Ánimo", mood) { mood = it }
            scale5("Hambre", hunger) { hunger = it }
            scale5("Estrés", stress) { stress = it }
            val notes = field("Notas", j.notes, InputKind.MULTILINE, "¿Cómo te sentiste? Digestión, antojos, dolor, logros…")
            button("Guardar bitácora", Btn.PRIMARY) {
                data.saveJournal(
                    JournalEntry(
                        date, Fmt.parse(sleep.text), sleepQ, energy, mood, hunger, stress,
                        Fmt.parse(steps.text)?.toInt(), notes.text.toString().trim(),
                    ),
                )
                act.toast("Bitácora guardada")
                act.refresh()
            }
        }

        val last7 = data.journal.filterKeys { it > today.minusDays(7) }.values
        fun avg(f: (JournalEntry) -> Number?): Double? {
            val v = last7.mapNotNull { f(it)?.toDouble() }
            return if (v.isEmpty()) null else v.average()
        }
        root.sectionTitle("Promedios 7 días")
        root.statCards(
            Triple(Fmt.n(avg { it.sleepH }, 1), "Horas de sueño", Pal.text),
            Triple(Fmt.n(avg { it.energy }, 1), "Energía", Pal.text),
            Triple(Fmt.n(avg { it.hunger }, 1), "Hambre", Pal.text),
        )

        root.sectionTitle("Entradas recientes")
        root.card {
            val keys = data.journal.keys.sortedDescending().take(21)
            if (keys.isEmpty()) muted("Aún no hay entradas.")
            for (k in keys) {
                listRow(Fmt.date(k), journalSummary(data.journal.getValue(k)), onClick = { date = k; act.refresh(scrollTop = true) })
            }
        }
    }
}
