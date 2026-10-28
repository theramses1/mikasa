package com.moiehfih.app.ui

import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import com.moiehfih.app.data.ActivityLevel
import com.moiehfih.app.data.Demo
import com.moiehfih.app.data.Goal
import com.moiehfih.app.data.JournalEntry
import com.moiehfih.app.data.Profile
import com.moiehfih.app.data.Sex
import com.moiehfih.app.logic.Calc
import java.time.LocalDate
import kotlin.math.abs

// ---------- Componentes compartidos ----------

/** Tarjeta con anillo de calorías y barras de macros para un día. */
fun LinearLayout.dayCard(act: MainActivity, date: LocalDate, title: String) {
    val tg = act.data.currentTargets()
    val tot = act.data.dayTotals(date)
    val left = tg.kcal - tot.kcal
    card {
        row {
            h2(title).weighted()
            pill("${Fmt.n(tg.kcal)} kcal objetivo")
        }.margins(bottom = 10)
        row {
            add(RingView(context, tot.kcal, tg.kcal.toDouble(), Fmt.n(tot.kcal), "kcal consumidas"), width = context.dp(124), height = context.dp(124))
            val right = add(context.vertical(), width = 0, weight = 1f)
            right.margins(start = 14)
            right.stat(Fmt.n(abs(left)), if (left < 0) "kcal por encima" else "kcal restantes", if (left < 0) Pal.bad else Pal.text)
            right.macroBar("Proteína", Pal.protein, tot.p, tg.p)
            right.macroBar("Carbos", Pal.carbs, tot.c, tg.c)
            right.macroBar("Grasa", Pal.fat, tot.f, tg.f)
            right.macroBar("Azúcares (máx.)", Pal.sugar, tot.sugar, Calc.sugarLimitG(tg.kcal))
        }
    }
}

/** Tarjeta de agua del día con botones rápidos. */
fun LinearLayout.waterCard(act: MainActivity, date: LocalDate) {
    val data = act.data
    val total = data.waterTotal(date)
    val goal = data.waterGoal()
    card {
        row {
            h3("💧 Agua").weighted()
            text("${Fmt.n(total)} / ${Fmt.n(goal)} ml", 14f, bold = true)
        }.margins(bottom = 8)
        add(ProgressBarView(context, total.toDouble() / goal, Pal.water, overIsBad = false), height = dp(10))
        val glasses = data.water[date]?.size ?: 0
        muted(
            if (total >= goal) "¡Meta cumplida! ($glasses tomas)"
            else "Faltan ${Fmt.n(goal - total)} ml · $glasses tomas",
            12f,
        ).margins(top = 6, bottom = 8)
        row {
            for (ml in listOf(250, 500)) {
                button("+$ml ml", small = true) { data.addWater(date, ml); act.refresh() }.margins(end = 6)
            }
            button("Otra", small = true) { waterDialog(act, date) }.margins(end = 6)
            val last = data.water[date]?.lastOrNull()
            if (last != null) button("Deshacer", Btn.GHOST, small = true) {
                data.deleteWater(last)
                act.refresh()
            }
        }
    }
}

fun waterDialog(act: MainActivity, date: LocalDate) {
    lateinit var amount: android.widget.EditText
    act.formDialog("Agregar agua", "Agregar", build = {
        amount = field("Cantidad (ml)", "", InputKind.INT, "330")
    }) {
        val ml = Fmt.parse(amount.text)?.toInt()
        if (ml == null || ml !in 1..5000) return@formDialog "Indica entre 1 y 5,000 ml."
        act.data.addWater(date, ml)
        act.refresh()
        null
    }
}

/** Navegación ‹ fecha › con selector de fecha al tocar el texto. */
fun LinearLayout.dateNav(act: MainActivity, date: LocalDate, onChange: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    row {
        button("‹", small = true) { onChange(date.minusDays(1)) }
        text(Fmt.date(date, longForm = true), 16f, bold = true, gravity = Gravity.CENTER).weighted().setOnClickListener {
            act.pickDate(date) { onChange(it) }
        }
        val next = button("›", small = true) { if (date < today) onChange(date.plusDays(1)) }
        next.alpha = if (date < today) 1f else 0.35f
    }.margins(bottom = 12)
}

fun goalLabel(rate: Double): String =
    if (rate == 0.0) "Mantener peso" else "${if (rate < 0) "Perder" else "Ganar"} ${Fmt.n(abs(rate), 2)} % por semana"

fun journalSummary(j: JournalEntry): String = buildList {
    j.sleepH?.let { add("😴 ${Fmt.n(it, 1)} h") }
    j.energy?.let { add("⚡ $it/5") }
    j.mood?.let { add("🙂 $it/5") }
    j.hunger?.let { add("🍽 hambre $it/5") }
    j.steps?.let { add("👣 ${Fmt.n(it)}") }
}.joinToString(" · ") + if (j.notes.isNotBlank()) "\n${j.notes}" else ""

// ---------- Inicio ----------

class HomeScreen(act: MainActivity) : Screen(act) {
    override val title = "MoiehFih"

    override fun build(root: LinearLayout) {
        val today = LocalDate.now()
        val d = data.derived(today)
        val tg = data.currentTargets()

        if (data.checkinDue(today)) {
            val preview = data.proposedTargets(today)
            root.add(act.vertical {
                background = rounded(Pal.accentSoft, dp(14).toFloat(), Pal.soft(Pal.accent, 90), dp(1))
                setPadding(dp(14), dp(14), dp(14), dp(14))
                h3("Check-in semanal disponible")
                muted(
                    if (preview != null) "Nuevo objetivo estimado: ${Fmt.n(preview.kcal)} kcal · P ${preview.p} · C ${preview.c} · G ${preview.f}"
                    else "Registra tu peso para calcularlo.",
                ).margins(top = 4, bottom = 10)
                button("Hacer check-in", Btn.PRIMARY) { checkin() }
            }).margins(bottom = 12)
        }

        root.dayCard(act, today, "Hoy")
        root.waterCard(act, today)

        val weekAgo = today.minusDays(7)
        val expWeekAgo = d.exp.lastOrNull { it.date <= weekAgo }?.tdee
        val trendWeekAgo = Calc.trendOn(d.trend, weekAgo)
        val weekly = if (d.trendNow != null && trendWeekAgo != null) d.trendNow - trendWeekAgo else null
        root.row {
            val a = add(context.vertical(), width = 0, weight = 1f)
            a.margins(end = 6)
            a.card({ act.selectTab(MainActivity.Tab.WEIGHT) }) {
                muted("Gasto energético")
                text(Fmt.n(d.tdee), 28f, bold = true)
                muted("kcal/día · " + if (d.tdee != null && expWeekAgo != null) "${Fmt.signed(d.tdee - expWeekAgo, 0)} en 7 días" else "estimación inicial", 12f)
                add(LineChartView(context, listOf(LineChartView.Series(d.exp.takeLast(28).map { it.tdee }, Pal.good)), compact = true), height = dp(34)).margins(top = 6)
            }
            val b = add(context.vertical(), width = 0, weight = 1f)
            b.margins(start = 6)
            b.card({ act.selectTab(MainActivity.Tab.WEIGHT) }) {
                muted("Peso de tendencia")
                text(Fmt.n(d.trendNow, 1), 28f, bold = true)
                muted("kg · " + if (weekly != null) "${Fmt.signed(weekly, 2)} kg/sem" else "sin datos", 12f)
                add(LineChartView(context, listOf(LineChartView.Series(d.trend.takeLast(28).map { it.trend }, Pal.accent)), compact = true), height = dp(34)).margins(top = 6)
            }
        }

        val t = data.targets
        root.card {
            row {
                h2("Plan").weighted()
                if (t?.manual == true) pill("Manual", Pal.warn) else pill("Coach", Pal.good)
            }
            text(
                goalLabel(data.goal.ratePct) +
                    (d.trendNow?.let { " · ≈ ${Fmt.n(abs(data.goal.ratePct / 100 * it), 2)} kg/sem" } ?: ""),
                14f,
            ).margins(top = 6, bottom = 10)
            row {
                stat(Fmt.n(tg.p), "Proteína (g)").weighted()
                stat(Fmt.n(tg.c), "Carbos (g)").weighted()
                stat(Fmt.n(tg.f), "Grasa (g)").weighted()
            }
            val next = if (t != null) maxOf(0, 7 - Calc.days(t.date, today)) else 0
            muted(if (data.checkinDue(today)) "Check-in pendiente" else "Próximo check-in en $next día${if (next == 1) "" else "s"}", 12f).margins(top = 8)
        }

        root.healthCard(act, today)

        val finished = data.finishedWorkouts()
        val thisWeek = finished.count { it.date > today.minusDays(7) }
        val cardioWeek = data.cardio.filter { it.date > today.minusDays(7) }
        val active = data.activeWorkout()
        val last = finished.maxByOrNull { it.start }
        root.card {
            row {
                h2("Entreno").weighted()
                pill("$thisWeek sesiones / 7 días")
            }.margins(bottom = 10)
            when {
                active != null -> button("Continuar: ${active.name}", Btn.PRIMARY) { act.selectTab(MainActivity.Tab.TRAIN) }
                last != null -> {
                    text("Último: ${last.name} · ${Fmt.date(last.date)} · ${Fmt.n(Calc.volume(last.exercises.flatMap { Calc.workingSets(it.sets) }))} kg de volumen", 14f)
                    button("Empezar entreno") { act.selectTab(MainActivity.Tab.TRAIN) }.margins(top = 10)
                }
                else -> button("Empezar tu primer entreno") { act.selectTab(MainActivity.Tab.TRAIN) }
            }
            muted(
                if (cardioWeek.isEmpty()) "Sin cardio esta semana"
                else "Cardio: ${cardioWeek.size} sesiones · ${cardioWeek.sumOf { it.minutes }} min · ${Fmt.n(cardioWeek.sumOf { it.kcal ?: 0.0 })} kcal (7 días)",
                12.5f,
            ).margins(top = 10)
        }

        val j = data.journal[today]
        root.card({ act.selectTab(MainActivity.Tab.JOURNAL) }) {
            row {
                h2("Bitácora de hoy").weighted()
                text(if (j != null) "Editar" else "Registrar", 14f, Pal.accent, bold = true)
            }
            muted(j?.let { journalSummary(it) } ?: "Anota sueño, energía, hambre y notas del día.").margins(top = 6)
        }
    }

    private fun checkin() {
        val prev = data.targets
        val next = data.doCheckin()
        when {
            next == null -> act.toast("Registra tu peso para hacer el check-in.")
            prev?.manual == true -> act.toast("Check-in guardado (tus objetivos manuales se mantienen).")
            prev != null -> act.toast("Nuevo objetivo: ${Fmt.n(next.kcal)} kcal (${Fmt.signed((next.kcal - prev.macros.kcal).toDouble(), 0)})")
            else -> act.toast("Objetivo: ${Fmt.n(next.kcal)} kcal")
        }
        act.refresh()
    }
}

// ---------- Perfil (onboarding y ajustes) ----------

val RATES = listOf(-1.0, -0.75, -0.5, -0.25, 0.0, 0.1, 0.25, 0.5)

/** Formulario de perfil y objetivo; [read] devuelve los valores o un mensaje de error. */
class ProfileForm(root: LinearLayout, p: Profile?, g: Goal, withWeight: Boolean) {
    private val ctx = root.context
    private val name: EditText = root.field("Nombre (opcional)", p?.name ?: "")
    private val sex: Spinner
    private val age: EditText
    private val height: EditText
    private val weight: EditText?
    private val activity: Spinner
    private val rate: Spinner
    private val protein: EditText
    private val fat: EditText

    init {
        root.text("Sexo", 13f, Pal.muted, bold = true).margins(bottom = 4)
        sex = root.add(spinner(listOf("Hombre", "Mujer"), if (p?.sex == Sex.F) 1 else 0)).margins(bottom = 10) as Spinner
        val r1 = root.row {}
        age = r1.add(ctx.vertical(), width = 0, weight = 1f).field("Edad", p?.age?.toString() ?: "", InputKind.INT)
        r1.add(android.view.View(ctx), width = ctx.dp(10))
        height = r1.add(ctx.vertical(), width = 0, weight = 1f).field("Estatura (cm)", Fmt.plain(p?.heightCm), InputKind.DECIMAL)
        weight = if (withWeight) root.field("Peso actual (kg)", "", InputKind.DECIMAL) else null
        root.text("Actividad diaria", 13f, Pal.muted, bold = true).margins(bottom = 4)
        activity = root.add(spinner(ActivityLevel.values().map { it.label }, (p?.activity ?: ActivityLevel.MODERADO).ordinal)).margins(bottom = 10) as Spinner
        root.text("Objetivo", 13f, Pal.muted, bold = true).margins(bottom = 4)
        rate = root.add(spinner(RATES.map { goalLabel(it) }, RATES.indexOf(g.ratePct).takeIf { it >= 0 } ?: 4)).margins(bottom = 10) as Spinner
        val r2 = root.row {}
        protein = r2.add(ctx.vertical(), width = 0, weight = 1f).field("Proteína (g por kg)", Fmt.plain(g.proteinGPerKg), InputKind.DECIMAL)
        r2.add(android.view.View(ctx), width = ctx.dp(10))
        fat = r2.add(ctx.vertical(), width = 0, weight = 1f).field("Grasa (% de kcal)", Fmt.plain(g.fatPct * 100, 0), InputKind.DECIMAL)
    }

    private fun spinner(items: List<String>, selected: Int) = Spinner(ctx).apply {
        adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(selected)
    }

    class Result(val profile: Profile, val goal: Goal, val weightKg: Double?)

    fun read(): Pair<Result?, String?> {
        val a = Fmt.parse(age.text)?.toInt()
        val h = Fmt.parse(height.text)
        if (a == null || a !in 12..100) return null to "Indica una edad válida."
        if (h == null || h !in 100.0..250.0) return null to "Indica tu estatura en cm."
        val w = weight?.let { Fmt.parse(it.text) }
        if (weight != null && (w == null || w !in 25.0..400.0)) return null to "Indica tu peso actual en kg."
        val profile = Profile(
            name.text.toString().trim(), if (sex.selectedItemPosition == 1) Sex.F else Sex.M, a, h,
            ActivityLevel.values()[activity.selectedItemPosition],
        )
        val goal = Goal(
            RATES[rate.selectedItemPosition],
            (Fmt.parse(protein.text) ?: 2.0).coerceIn(0.8, 3.5),
            ((Fmt.parse(fat.text) ?: 27.0) / 100).coerceIn(0.15, 0.5),
        )
        return Result(profile, goal, w) to null
    }
}

class OnboardingScreen(act: MainActivity) : Screen(act) {
    override val title = "Bienvenido a MoiehFih"

    override fun build(root: LinearLayout) {
        lateinit var form: ProfileForm
        root.card {
            h2("Tu coach de nutrición y entreno")
            muted(
                "Registra lo que comes, tu peso y tus entrenos. MoiehFih estima tu gasto energético real con tus datos " +
                    "y ajusta tus calorías y macros cada semana. También te dice cuánto peso y cuántas repeticiones hacer.",
                14f,
            ).margins(top = 6, bottom = 14)
            form = ProfileForm(this, null, data.goal, withWeight = true)
            button("Empezar", Btn.PRIMARY) { start(form) }.margins(top = 6)
        }
        root.button("Explorar con datos de demostración") {
            Demo.load(data)
            act.restart()
            act.toast("Datos de demostración cargados")
        }
        root.muted("Tus datos se guardan solo en este teléfono. Puedes exportar una copia desde Ajustes.", 12f).margins(top = 10)
    }

    private fun start(form: ProfileForm) {
        val (r, error) = form.read()
        if (r == null) return act.toast(error!!)
        data.saveProfile(r.profile, r.goal)
        data.setWeight(LocalDate.now(), r.weightKg!!)
        data.recalcTargets()
        act.restart()
        act.toast("Tu objetivo inicial: ${Fmt.n(data.currentTargets().kcal)} kcal")
    }
}

class SettingsScreen(act: MainActivity) : Screen(act) {
    override val title = "Ajustes"

    override fun build(root: LinearLayout) {
        lateinit var form: ProfileForm
        root.card {
            h2("Perfil y objetivo").margins(bottom = 10)
            form = ProfileForm(this, data.profile, data.goal, withWeight = false)
            button("Guardar y recalcular", Btn.PRIMARY) {
                val (r, error) = form.read()
                if (r == null) return@button act.toast(error!!)
                data.saveProfile(r.profile, r.goal)
                if (data.targets?.manual != true) data.recalcTargets()
                act.toast("Perfil guardado")
                act.refresh()
            }
        }

        val t = data.targets
        val tg = data.currentTargets()
        root.card {
            h2("Objetivos diarios")
            muted(
                "En modo Coach las calorías y macros se recalculan en cada check-in semanal a partir de tu gasto " +
                    "energético estimado (${Fmt.n(data.derived().tdee)} kcal). En modo Manual se quedan fijas.",
            ).margins(top = 4, bottom = 10)
            segmented(listOf("Coach (adaptativo)", "Manual"), if (t?.manual == true) 1 else 0) { i ->
                if (i == 0) {
                    data.recalcTargets()
                    act.toast("Modo coach activado")
                } else {
                    data.setTargets(t?.copy(manual = true) ?: com.moiehfih.app.data.Targets(LocalDate.now(), tg, null, true))
                }
                act.refresh()
            }
            if (t?.manual == true) {
                val r1 = row {}
                val kcal = r1.add(context.vertical(), width = 0, weight = 1f).field("Calorías", "${tg.kcal}", InputKind.INT)
                r1.add(android.view.View(context), width = dp(10))
                val p = r1.add(context.vertical(), width = 0, weight = 1f).field("Proteína (g)", "${tg.p}", InputKind.INT)
                val r2 = row {}
                val c = r2.add(context.vertical(), width = 0, weight = 1f).field("Carbos (g)", "${tg.c}", InputKind.INT)
                r2.add(android.view.View(context), width = dp(10))
                val f = r2.add(context.vertical(), width = 0, weight = 1f).field("Grasa (g)", "${tg.f}", InputKind.INT)
                button("Guardar objetivos", Btn.PRIMARY) {
                    val values = listOf(kcal, p, c, f).map { Fmt.parse(it.text)?.toInt() }
                    if (values.any { it == null || it < 0 }) return@button act.toast("Completa calorías y macros.")
                    data.setTargets(t.copy(macros = com.moiehfih.app.data.MacroTargets(values[0]!!, values[1]!!, values[2]!!, values[3]!!)))
                    act.toast("Objetivos guardados")
                    act.refresh()
                }
            } else {
                row {
                    stat(Fmt.n(tg.kcal), "kcal").weighted()
                    stat(Fmt.n(tg.p), "P (g)").weighted()
                    stat(Fmt.n(tg.c), "C (g)").weighted()
                    stat(Fmt.n(tg.f), "G (g)").weighted()
                }
            }
        }

        root.card {
            h2("Agua").margins(bottom = 8)
            val auto = Calc.waterGoalMl(data.derived().trendNow)
            val goal = field("Meta diaria (ml)", data.waterGoalOverride?.toString() ?: "", InputKind.INT, "automática: ${Fmt.n(auto)}")
            muted("Si la dejas vacía se calcula con tu peso (≈35 ml por kg).", 12f)
            button("Guardar") {
                val text = goal.text.toString().trim()
                val ml = Fmt.parse(text)?.toInt()
                if (text.isNotEmpty() && (ml == null || ml !in 500..8000)) return@button act.toast("Indica entre 500 y 8,000 ml.")
                data.setWaterGoal(if (text.isEmpty()) null else ml)
                act.toast("Meta de agua: ${Fmt.n(data.waterGoal())} ml")
                act.refresh()
            }.margins(top = 8)
        }

        root.card({ act.push(HealthScreen(act)) }) {
            row {
                h2("Salud y relojes").weighted()
                if (data.healthConnected) pill("Conectado", Pal.good)
            }
            muted("Importa pasos, pulso, sueño, SpO₂, presión, peso y sesiones de cardio desde Google Fit, Fitbit, Samsung Health y otros, vía Health Connect.").margins(top = 4)
        }

        root.card {
            h2("Entreno").margins(bottom = 8)
            val rest = field("Descanso entre series (segundos)", "${data.restSeconds}", InputKind.INT)
            muted("Al marcar una serie como hecha empieza la cuenta atrás y el teléfono vibra al terminar. 0 = desactivado.", 12f)
            button("Guardar") {
                val s = Fmt.parse(rest.text)?.toInt()
                if (s == null || s !in 0..900) return@button act.toast("Indica entre 0 y 900 segundos.")
                data.setRestSeconds(s)
                act.toast("Guardado")
            }.margins(top = 8)
        }

        root.card {
            h2("Datos")
            muted("Todo se guarda en este teléfono. Exporta una copia de seguridad de vez en cuando (por ejemplo a Drive).").margins(top = 4, bottom = 10)
            row {
                button("Exportar copia") { act.exportBackup() }.weighted()
                add(android.view.View(context), width = dp(10))
                button("Importar copia") { act.importBackup() }.weighted()
            }
            button("Borrar todos los datos", Btn.DANGER) {
                act.confirm("¿Borrar TODOS los datos? Esta acción no se puede deshacer.", "Borrar") {
                    data.resetAll()
                    act.restart()
                }
            }.margins(top = 10)
        }

        root.card {
            h3("¿Cómo funciona?")
            for ((head, body) in listOf(
                "Peso de tendencia" to "media móvil exponencial de tus pesadas, para ver el cambio real sin el ruido diario de agua, sal y digestión.",
                "Gasto energético" to "parte de Mifflin-St Jeor × actividad y se corrige con tus datos: ingesta media − (cambio de tendencia × 7700 kcal/kg) en las últimas 3 semanas.",
                "Progresión" to "doble progresión dentro del rango de reps de cada ejercicio; cuando completas el máximo en todas las series sube el peso, y si te estancas dos sesiones por debajo del mínimo sugiere una descarga del 10 %.",
            )) {
                text("$head: $body", 13f, Pal.muted).margins(top = 6)
            }
        }
    }
}
