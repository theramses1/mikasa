package com.moiehfih.app.ui

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.moiehfih.app.data.AppData
import com.moiehfih.app.data.Exercise
import com.moiehfih.app.data.MUSCLES
import com.moiehfih.app.data.Routine
import com.moiehfih.app.data.Workout
import com.moiehfih.app.data.WorkoutExercise
import com.moiehfih.app.data.WorkoutSet
import com.moiehfih.app.logic.Calc
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object WorkoutActions {
    fun suggestion(data: AppData, ex: Exercise) = Calc.suggestNext(Calc.exerciseHistory(data.workouts, ex.id), ex)

    /** Ejercicio nuevo dentro de un entreno, con series prellenadas según la sugerencia. */
    fun entryFor(data: AppData, ex: Exercise): WorkoutExercise {
        val s = suggestion(data, ex)
        return WorkoutExercise(ex.id, MutableList(s.sets) { WorkoutSet(s.weight, s.reps, null, false) })
    }

    fun start(data: AppData, routine: Routine?, name: String? = null): Workout {
        val exercises = routine?.exerciseIds?.mapNotNull { data.exercise(it) }?.map { entryFor(data, it) }.orEmpty()
        return data.saveWorkout(
            Workout(0, LocalDate.now(), name ?: routine?.name ?: "Entreno", System.currentTimeMillis(), null, "", exercises.toMutableList()),
        )
    }

    /**
     * Termina el entreno: guarda solo las series marcadas como hechas (o todas las que tengan
     * repeticiones si no se marcó ninguna). Devuelve los récords nuevos, o null si no hay series.
     */
    fun finish(data: AppData, w: Workout): List<String>? {
        val anyDone = w.exercises.any { ex -> ex.sets.any { it.done } }
        val cleaned = w.exercises.map { ex ->
            WorkoutExercise(ex.exerciseId, ex.sets.filter { (if (anyDone) it.done else true) && (it.reps ?: 0) > 0 }
                .map { it.copy(weight = it.weight ?: 0.0, done = true) }.toMutableList())
        }.filter { it.sets.isNotEmpty() }
        if (cleaned.isEmpty()) return null
        val records = cleaned.mapNotNull { ex ->
            val history = Calc.exerciseHistory(data.workouts, ex.exerciseId)
            if (history.isEmpty()) return@mapNotNull null
            val best = history.maxOf { it.best1rm }
            val now = ex.sets.maxOf { Calc.e1rm(it.weight ?: 0.0, it.reps!!) }
            if (now > best) data.exercise(ex.exerciseId)?.name else null
        }
        w.exercises.clear()
        w.exercises.addAll(cleaned)
        w.end = System.currentTimeMillis()
        data.saveWorkout(w)
        return records
    }
}

private fun normalize(s: String) =
    Normalizer.normalize(s.lowercase(Fmt.locale), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

private fun kindColor(k: Calc.Kind) = when (k) {
    Calc.Kind.UP -> Pal.good
    Calc.Kind.DELOAD -> Pal.bad
    Calc.Kind.HOLD -> Pal.warn
    else -> Pal.accent
}

fun LinearLayout.suggestionBox(s: Calc.Suggestion, prefix: String = "Sugerencia") {
    add(context.vertical {
        background = rounded(Pal.surface2, context.dp(10).toFloat())
        setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
        val w = if (s.weight != null) "${Fmt.plain(s.weight, 2)} kg" else "elige peso"
        text("$prefix: $w × ${s.reps} (${s.sets} series)", 14f, kindColor(s.kind), bold = true)
        text(s.reason, 13f, Pal.muted)
    }).margins(top = 6, bottom = 4)
}

class TrainScreen(act: MainActivity) : Screen(act) {
    override val title = "Entreno"
    private var view = 0

    override fun build(root: LinearLayout) {
        root.segmented(listOf("Entrenar", "Historial", "Ejercicios"), view) { view = it; act.refresh() }
        when (view) {
            0 -> buildTrain(root)
            1 -> buildHistory(root)
            else -> buildExercises(root)
        }
    }

    private fun buildTrain(root: LinearLayout) {
        val active = data.activeWorkout()
        if (active != null) {
            root.card({ act.push(ActiveWorkoutScreen(act)) }) {
                h3("Entreno en curso: ${active.name}")
                muted("${active.exercises.size} ejercicios · ${elapsed(active.start)}").margins(top = 4, bottom = 10)
                button("Continuar", Btn.PRIMARY) { act.push(ActiveWorkoutScreen(act)) }
            }
        } else {
            root.button("Empezar entreno vacío", Btn.PRIMARY) { start(null) }.margins(bottom = 4)
        }
        root.sectionTitle("Rutinas")
        for (r in data.routines) {
            root.card {
                row {
                    h3(r.name).weighted()
                    button("Editar", Btn.GHOST, small = true) { act.push(RoutineEditScreen(act, r.id)) }
                    if (active == null) button("Empezar", Btn.PRIMARY, small = true) { start(r) }
                }
                muted(r.exerciseIds.mapNotNull { data.exercise(it)?.name }.joinToString(" · ")).margins(top = 6)
            }
        }
        root.button("+ Nueva rutina") { act.push(RoutineEditScreen(act, null)) }
        root.sectionTitle("Series por músculo (7 días)")
        val sets = Calc.weeklySetsByMuscle(data.workouts, data.exercises, LocalDate.now())
        root.card {
            val items = MUSCLES.mapNotNull { m -> sets[m]?.let { m to it } }
            if (items.isEmpty()) muted("Aún no hay series esta semana.")
            else add(HBarsView(context, items))
            muted("Referencia general: 10-20 series semanales por grupo muscular.", 12f).margins(top = 8)
        }
    }

    private fun start(r: Routine?) {
        WorkoutActions.start(data, r)
        act.push(ActiveWorkoutScreen(act))
    }

    private fun buildHistory(root: LinearLayout) {
        val list = data.finishedWorkouts().sortedWith(compareByDescending<Workout> { it.date }.thenByDescending { it.start })
        if (list.isEmpty()) {
            root.card { muted("Aún no hay entrenos guardados.") }
            return
        }
        root.card {
            for (w in list) {
                val sets = w.exercises.sumOf { it.sets.size }
                val minutes = w.end?.let { (it - w.start) / 60000 }
                listRow(
                    w.name,
                    "${Fmt.date(w.date)} · $sets series" + (minutes?.let { " · $it min" } ?: ""),
                    "${Fmt.n(Calc.volume(w.exercises.flatMap { it.sets }))} kg",
                    onClick = { act.push(WorkoutDetailScreen(act, w.id)) },
                )
            }
        }
    }

    private fun buildExercises(root: LinearLayout) {
        root.button("+ Nuevo ejercicio") { exerciseDialog(act, null) }.margins(bottom = 4)
        for (m in MUSCLES) {
            val list = data.exercises.filter { it.muscle == m }
            if (list.isEmpty()) continue
            root.sectionTitle(m)
            root.card {
                for (e in list) {
                    val h = Calc.exerciseHistory(data.workouts, e.id)
                    listRow(
                        e.name,
                        if (h.isEmpty()) "Sin registros" else "${h.size} sesiones · último ${Fmt.date(h.last().date)}",
                        h.maxOfOrNull { it.best1rm }?.let { "${Fmt.n(it, 1)} kg" },
                        if (h.isEmpty()) null else "1RM est.",
                        onClick = { act.push(ExerciseDetailScreen(act, e.id)) },
                    )
                }
            }
        }
    }
}

fun elapsed(start: Long): String {
    val m = (System.currentTimeMillis() - start) / 60000
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

class ActiveWorkoutScreen(act: MainActivity) : Screen(act) {
    override val title = "Entreno en curso"
    override val keepScreenOn = true

    override fun build(root: LinearLayout) {
        val w = data.activeWorkout()
        if (w == null) {
            root.card { muted("No hay ningún entreno en curso.") }
            return
        }
        val save = { data.saveWorkout(w); Unit }
        root.card {
            val name = add(act.input(w.name, "Nombre del entreno"))
            name.onChange { w.name = it; save() }
            val started = java.time.Instant.ofEpochMilli(w.start).atZone(java.time.ZoneId.systemDefault())
            muted("Iniciado ${DateTimeFormatter.ofPattern("HH:mm").format(started)} · ${elapsed(w.start)}").margins(top = 8)
        }

        w.exercises.forEachIndexed { i, entry ->
            val ex = data.exercise(entry.exerciseId)
            root.card {
                row {
                    val head = add(context.vertical(), width = 0, weight = 1f)
                    head.h3(ex?.name ?: "Ejercicio eliminado")
                    if (ex != null) head.muted("${ex.muscle} · rango ${ex.repMin}-${ex.repMax} reps", 12f)
                    button("✕", Btn.GHOST, small = true) {
                        act.confirm("¿Quitar este ejercicio del entreno?", "Quitar") {
                            w.exercises.removeAt(i); save(); act.refresh()
                        }
                    }
                }
                if (ex != null) suggestionBox(WorkoutActions.suggestion(data, ex))
                setsTable(this, w, entry, save)
                row {
                    button("+ Serie", Btn.GHOST, small = true) {
                        val last = entry.sets.lastOrNull()
                        entry.sets += last?.copy(done = false, rpe = null) ?: WorkoutSet(null, null)
                        save(); act.refresh()
                    }
                    if (entry.sets.isNotEmpty()) button("− Serie", Btn.GHOST, small = true) {
                        entry.sets.removeAt(entry.sets.size - 1); save(); act.refresh()
                    }
                }.margins(top = 4)
            }
        }
        root.button("+ Añadir ejercicio") {
            act.push(ExercisePickerScreen(act) { ex ->
                w.exercises += WorkoutActions.entryFor(data, ex)
                save()
            })
        }.margins(bottom = 12)
        root.text("Notas", 13f, Pal.muted, bold = true).margins(bottom = 4)
        val notes = root.add(act.input(w.notes, "Sensaciones, técnica, molestias…", InputKind.MULTILINE))
        notes.onChange { w.notes = it; save() }
        root.row {
            button("Descartar", Btn.DANGER) {
                act.confirm("¿Descartar este entreno? No se guardará.", "Descartar") {
                    data.deleteWorkout(w.id); act.pop()
                }
            }.weighted()
            add(View(context), width = dp(10))
            button("Terminar entreno", Btn.PRIMARY) { finish(w) }.weighted()
        }.margins(top = 14)
    }

    private fun setsTable(card: LinearLayout, w: Workout, entry: WorkoutExercise, save: () -> Unit) {
        val ctx = card.context
        val header = card.row {}
        header.margins(top = 8, bottom = 2)
        for ((label, weight) in listOf("#" to 0.45f, "kg" to 1.3f, "Reps" to 1f, "RPE" to 1f)) {
            header.add(TextView(ctx).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(Pal.muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
            }, width = 0, weight = weight)
        }
        header.add(View(ctx), width = ctx.dp(44))

        entry.sets.forEachIndexed { j, set ->
            val r = card.row {}
            r.margins(top = 3, bottom = 3)
            r.add(TextView(ctx).apply {
                text = "${j + 1}"
                gravity = Gravity.CENTER
                setTextColor(Pal.muted)
            }, width = 0, weight = 0.45f)
            fun cell(value: String, kind: InputKind, weight: Float, hint: String = "", update: (String) -> Unit): EditText {
                val e = ctx.input(value, hint, kind).apply {
                    gravity = Gravity.CENTER
                    setPadding(ctx.dp(4), ctx.dp(8), ctx.dp(4), ctx.dp(8))
                    if (set.done) background = rounded(Pal.soft(Pal.good, 45), ctx.dp(10).toFloat(), Pal.border, ctx.dp(1))
                }
                e.onChange(update)
                r.add(e, width = 0, weight = weight).margins(end = 4)
                return e
            }
            cell(Fmt.plain(set.weight), InputKind.DECIMAL, 1.3f) { set.weight = Fmt.parse(it); save() }
            cell(set.reps?.toString() ?: "", InputKind.INT, 1f) { set.reps = Fmt.parse(it)?.toInt(); save() }
            cell(Fmt.plain(set.rpe), InputKind.DECIMAL, 1f, "–") { set.rpe = Fmt.parse(it); save() }
            r.add(TextView(ctx).apply {
                text = "✓"
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(if (set.done) Pal.onAccent else Pal.muted)
                background = rounded(if (set.done) Pal.good else Pal.surface2, ctx.dp(10).toFloat(), if (set.done) Pal.good else Pal.border, ctx.dp(1))
                contentDescription = "Serie ${j + 1} hecha"
                setOnClickListener {
                    set.done = !set.done
                    save()
                    if (set.done) act.startRest()
                    act.refresh()
                }
            }, width = ctx.dp(40), height = ctx.dp(40))
        }
    }

    private fun finish(w: Workout) {
        val records = WorkoutActions.finish(data, w)
        if (records == null) return act.toast("Registra al menos una serie con repeticiones.")
        act.pop()
        act.toast(if (records.isNotEmpty()) "🏆 Nuevo récord: ${records.joinToString(", ")}" else "Entreno guardado 💪")
    }
}

class WorkoutDetailScreen(act: MainActivity, private val id: Long) : Screen(act) {
    override val title = "Entreno"

    override fun build(root: LinearLayout) {
        val w = data.workouts.firstOrNull { it.id == id }
        if (w == null) {
            root.card { muted("Este entreno ya no existe.") }
            return
        }
        root.card {
            h2(w.name)
            muted("${Fmt.date(w.date, longForm = true)} · ${w.exercises.sumOf { it.sets.size }} series · ${Fmt.n(Calc.volume(w.exercises.flatMap { it.sets }))} kg de volumen").margins(top = 4)
            if (w.notes.isNotBlank()) text(w.notes, 14f).margins(top = 8)
        }
        for (entry in w.exercises) {
            val ex = data.exercise(entry.exerciseId)
            root.card({ if (ex != null) act.push(ExerciseDetailScreen(act, ex.id)) }) {
                h3(ex?.name ?: "Ejercicio eliminado")
                entry.sets.forEachIndexed { j, s ->
                    listRow(
                        "${j + 1}.  ${Fmt.n(s.weight, 1)} kg × ${s.reps}" + (s.rpe?.let { " @ RPE ${Fmt.n(it, 1)}" } ?: ""),
                        trailing = Fmt.n(Calc.e1rm(s.weight ?: 0.0, s.reps ?: 0), 1),
                        trailingSub = "1RM est.",
                    )
                }
            }
        }
        root.row {
            button("Eliminar", Btn.DANGER) {
                act.confirm("¿Eliminar este entreno del historial?", "Eliminar") { data.deleteWorkout(w.id); act.pop() }
            }.weighted()
            add(View(context), width = dp(10))
            button("Repetir", Btn.PRIMARY) {
                if (data.activeWorkout() != null) return@button act.toast("Ya tienes un entreno en curso.")
                WorkoutActions.start(data, Routine(0, w.name, w.exercises.map { it.exerciseId }))
                act.replace(ActiveWorkoutScreen(act))
            }.weighted()
        }
    }
}

class ExerciseDetailScreen(act: MainActivity, private val id: Long) : Screen(act) {
    override val title = "Ejercicio"

    override fun build(root: LinearLayout) {
        val e = data.exercise(id)
        if (e == null) {
            root.card { muted("Este ejercicio ya no existe.") }
            return
        }
        val h = Calc.exerciseHistory(data.workouts, e.id)
        root.card {
            row {
                h2(e.name).weighted()
                button("Editar", Btn.GHOST, small = true) { exerciseDialog(act, e) }
            }
            muted("${e.muscle} · rango ${e.repMin}-${e.repMax} reps · +${Fmt.n(e.increment, 1)} kg por subida").margins(top = 2)
            suggestionBox(WorkoutActions.suggestion(data, e), "Próxima sesión")
        }
        root.statCards(
            Triple(Fmt.n(h.maxOfOrNull { it.best1rm }, 1), "Mejor 1RM est.", Pal.text),
            Triple(Fmt.n(h.maxOfOrNull { it.topWeight }, 1), "Peso máximo", Pal.text),
            Triple(Fmt.n(h.maxOfOrNull { it.volume }), "Volumen máx.", Pal.text),
        )
        root.card {
            h3("1RM estimado por sesión").margins(bottom = 8)
            val labels = if (h.isNotEmpty()) shortDate(h.first().date) to shortDate(h.last().date) else null
            add(LineChartView(context, listOf(LineChartView.Series(h.map { it.best1rm }, Pal.accent, dots = true)), labels), height = dp(180))
        }
        root.card {
            h3("Historial")
            if (h.isEmpty()) muted("Sin registros.").margins(top = 6)
            for (s in h.reversed().take(30)) {
                listRow(
                    Fmt.date(s.date),
                    s.sets.joinToString(" · ") { "${Fmt.n(it.weight, 1)}×${it.reps}" },
                    Fmt.n(s.best1rm, 1),
                    onClick = { act.push(WorkoutDetailScreen(act, s.workoutId)) },
                )
            }
        }
        if (!e.builtin) {
            root.button("Eliminar ejercicio", Btn.DANGER) {
                act.confirm("¿Eliminar este ejercicio? Su historial dejará de mostrarse.", "Eliminar") {
                    data.deleteExercise(e.id); act.pop()
                }
            }
        }
    }
}

fun shortDate(d: LocalDate): String = "${d.dayOfMonth}/${d.monthValue}"

/** Crear o editar un ejercicio. */
fun exerciseDialog(act: MainActivity, e: Exercise?, onSaved: (Exercise) -> Unit = {}) {
    lateinit var name: EditText
    lateinit var muscle: Spinner
    lateinit var repMin: EditText
    lateinit var repMax: EditText
    lateinit var inc: EditText
    act.formDialog(if (e == null) "Nuevo ejercicio" else "Editar ejercicio", build = {
        name = field("Nombre", e?.name ?: "")
        text("Grupo muscular", 13f, Pal.muted, bold = true).margins(bottom = 4)
        muscle = add(Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, MUSCLES)
            setSelection(maxOf(0, MUSCLES.indexOf(e?.muscle ?: "Pecho")))
        })
        muscle.margins(bottom = 10)
        repMin = field("Repeticiones mínimas", "${e?.repMin ?: 8}", InputKind.INT)
        repMax = field("Repeticiones máximas", "${e?.repMax ?: 12}", InputKind.INT)
        inc = field("Subida de peso (kg)", Fmt.plain(e?.increment ?: 2.5), InputKind.DECIMAL)
        muted("Cuando completes las repeticiones máximas en todas las series, la app te sugerirá subir este peso.", 12f)
    }) {
        val n = name.text.toString().trim()
        if (n.isEmpty()) return@formDialog "Ponle un nombre."
        val lo = Fmt.parse(repMin.text)?.toInt()?.coerceAtLeast(1) ?: return@formDialog "Indica las repeticiones mínimas."
        val hi = Fmt.parse(repMax.text)?.toInt()?.coerceAtLeast(lo) ?: return@formDialog "Indica las repeticiones máximas."
        val saved = act.data.saveExercise(
            Exercise(e?.id ?: 0, n, MUSCLES[muscle.selectedItemPosition], lo, hi, Fmt.parse(inc.text)?.takeIf { it > 0 } ?: 2.5, e?.builtin ?: false),
        )
        onSaved(saved)
        act.refresh()
        null
    }
}

class ExercisePickerScreen(act: MainActivity, private val onPick: (Exercise) -> Unit) : Screen(act) {
    override val title = "Añadir ejercicio"
    private var query = ""

    override fun build(root: LinearLayout) {
        root.button("+ Nuevo ejercicio") {
            exerciseDialog(act, null) { ex -> onPick(ex); act.pop() }
        }.margins(bottom = 10)
        val search = root.add(act.input(query, "Buscar ejercicio…"))
        search.margins(bottom = 4)
        val results = root.add(act.vertical())
        fun fill() {
            results.removeAllViews()
            val q = normalize(query.trim())
            for (m in MUSCLES) {
                val list = data.exercises.filter { it.muscle == m && (q.isEmpty() || normalize(it.name).contains(q)) }
                if (list.isEmpty()) continue
                results.sectionTitle(m)
                results.card {
                    for (e in list) listRow(e.name, onClick = { onPick(e); act.pop() })
                }
            }
        }
        search.onChange { query = it; fill() }
        fill()
    }
}

class RoutineEditScreen(act: MainActivity, private val id: Long?) : Screen(act) {
    override val title = if (id == null) "Nueva rutina" else "Editar rutina"
    private val selected = ArrayList<Long>()
    private var name = ""
    private var loaded = false

    override fun build(root: LinearLayout) {
        if (!loaded) {
            data.routines.firstOrNull { it.id == id }?.let { selected += it.exerciseIds; name = it.name }
            loaded = true
        }
        root.card {
            field("Nombre", name).onChange { name = it }
            muted("Los ejercicios se hacen en el orden en que los marques.", 12f)
        }
        for (m in MUSCLES) {
            val list = data.exercises.filter { it.muscle == m }
            if (list.isEmpty()) continue
            root.sectionTitle(m)
            root.card {
                for (e in list) {
                    add(CheckBox(context).apply {
                        text = e.name
                        setTextColor(Pal.text)
                        buttonTintList = ColorStateList.valueOf(Pal.accent)
                        isChecked = e.id in selected
                        setPadding(dp(6), dp(8), 0, dp(8))
                        setOnCheckedChangeListener { _, on -> if (on) selected += e.id else selected.remove(e.id) }
                    })
                }
            }
        }
        root.button("Guardar rutina", Btn.PRIMARY) {
            if (name.isBlank()) return@button act.toast("Ponle un nombre a la rutina.")
            if (selected.isEmpty()) return@button act.toast("Elige al menos un ejercicio.")
            data.saveRoutine(Routine(id ?: 0, name.trim(), selected.toList()))
            act.pop()
        }.margins(bottom = 10)
        if (id != null) root.button("Eliminar rutina", Btn.DANGER) {
            act.confirm("¿Eliminar esta rutina?", "Eliminar") { data.deleteRoutine(id); act.pop() }
        }
    }
}
