package com.moiehfih.app.logic

import com.moiehfih.app.data.Exercise
import com.moiehfih.app.data.Goal
import com.moiehfih.app.data.MacroTargets
import com.moiehfih.app.data.Profile
import com.moiehfih.app.data.Sex
import com.moiehfih.app.data.Workout
import com.moiehfih.app.data.WorkoutSet
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Cálculos puros: tendencia de peso, gasto energético, objetivos y progresión. */
object Calc {
    const val KCAL_PER_KG = 7700.0

    fun days(a: LocalDate, b: LocalDate): Int = ChronoUnit.DAYS.between(a, b).toInt()

    fun roundTo(x: Double, step: Double): Double = (x / step).roundToLong() * step

    // ---------- Gasto basal ----------

    /** Mifflin-St Jeor. */
    fun bmr(p: Profile, weightKg: Double): Double {
        val base = 10 * weightKg + 6.25 * p.heightCm - 5 * p.age
        return if (p.sex == Sex.M) base + 5 else base - 161
    }

    fun initialTdee(p: Profile, weightKg: Double) = bmr(p, weightKg) * p.activity.factor

    // ---------- Peso de tendencia ----------

    data class TrendPoint(val date: LocalDate, val scale: Double?, val trend: Double)

    /**
     * Serie diaria desde la primera pesada hasta [end]. Los días sin pesada se interpolan
     * linealmente (y la última pesada se mantiene hacia delante); la tendencia es una media
     * móvil exponencial sobre esa serie.
     */
    fun trendSeries(weights: Map<LocalDate, Double>, end: LocalDate, alpha: Double = 0.1): List<TrendPoint> {
        val dates = weights.keys.sorted()
        if (dates.isEmpty()) return emptyList()
        val last = maxOf(end, dates.last())
        val out = ArrayList<TrendPoint>()
        var trend = weights.getValue(dates.first())
        var i = 0
        var d = dates.first()
        while (!d.isAfter(last)) {
            while (i < dates.size - 1 && !dates[i + 1].isAfter(d)) i++
            val prev = dates[i]
            val next = dates.getOrNull(i + 1)
            val value = if (d == prev || next == null) {
                weights.getValue(prev)
            } else {
                val t = days(prev, d).toDouble() / days(prev, next)
                weights.getValue(prev) + (weights.getValue(next) - weights.getValue(prev)) * t
            }
            trend = if (out.isEmpty()) value else trend + alpha * (value - trend)
            out += TrendPoint(d, weights[d], trend)
            d = d.plusDays(1)
        }
        return out
    }

    fun trendOn(series: List<TrendPoint>, date: LocalDate): Double? {
        if (series.isEmpty() || date.isBefore(series.first().date)) return null
        return series[min(series.size - 1, days(series.first().date, date))].trend
    }

    // ---------- Gasto energético adaptativo ----------

    data class ExpPoint(val date: LocalDate, val tdee: Double, val raw: Double?, val logged: Int)

    /**
     * Para cada día estima el gasto como: ingesta media de los días registrados de la ventana −
     * (cambio de peso de tendencia × 7700 / días). La estimación se mueve poco a poco hacia ese
     * valor, más rápido cuantos más días registrados haya, para que unos pocos días raros no la
     * descontrolen. [intake] solo contiene los días con comida registrada.
     */
    fun expenditureSeries(
        profile: Profile,
        weights: Map<LocalDate, Double>,
        intake: Map<LocalDate, Double>,
        end: LocalDate,
        windowDays: Int = 21,
        minLogged: Int = 7,
        smoothing: Double = 0.15,
    ): List<ExpPoint> {
        val series = trendSeries(weights, end)
        if (series.isEmpty()) return emptyList()
        val start = series.first().date
        var tdee = initialTdee(profile, series.first().trend)
        val out = arrayListOf(ExpPoint(start, tdee, null, 0))
        var d = start.plusDays(1)
        val last = series.last().date
        while (!d.isAfter(last)) {
            val from = maxOf(start, d.minusDays((windowDays - 1).toLong()))
            val span = days(from, d)
            var total = 0.0
            var logged = 0
            var x = from
            while (!x.isAfter(d)) {
                intake[x]?.let { total += it; logged++ }
                x = x.plusDays(1)
            }
            var raw: Double? = null
            if (logged >= minLogged && span >= 6) {
                val dTrend = trendOn(series, d)!! - trendOn(series, from)!!
                raw = (total / logged - dTrend * KCAL_PER_KG / span).coerceIn(900.0, 6000.0)
                val confidence = min(1.0, logged / 14.0)
                tdee += smoothing * confidence * (raw - tdee)
            }
            out += ExpPoint(d, tdee, raw, logged)
            d = d.plusDays(1)
        }
        return out
    }

    // ---------- Objetivos ----------

    fun computeTargets(tdee: Double, weightKg: Double, goal: Goal, sex: Sex): MacroTargets {
        val delta = goal.ratePct / 100 * weightKg * KCAL_PER_KG / 7
        val floor = if (sex == Sex.M) 1500 else 1200
        val kcal = max(floor, (roundTo(tdee + delta, 10.0)).toInt())
        val p = (goal.proteinGPerKg * weightKg).roundToInt()
        val f = max(0.6 * weightKg, goal.fatPct * kcal / 9).roundToInt()
        val c = max(0, ((kcal - p * 4 - f * 9) / 4.0).roundToInt())
        return MacroTargets(kcal, p, c, f)
    }

    // ---------- Agua, azúcar y cardio ----------

    /** Meta de agua: ~35 ml por kg de peso, redondeada a 50 ml (mínimo 1.5 L). */
    fun waterGoalMl(weightKg: Double?): Int =
        if (weightKg == null) 2500 else max(1500, (roundTo(weightKg * 35, 50.0)).toInt())

    /** Límite orientativo de azúcares: 10 % de las calorías del día (recomendación de la OMS). */
    fun sugarLimitG(kcal: Int): Int = (kcal * 0.10 / 4).roundToInt()

    /** Calorías de una sesión de cardio con MET × peso × horas. */
    fun cardioKcal(met: Double, weightKg: Double, minutes: Int): Double = met * weightKg * minutes / 60.0

    // ---------- Entrenamiento ----------

    /** 1RM estimado (Epley). */
    fun e1rm(weight: Double, reps: Int): Double = when {
        reps <= 0 -> 0.0
        reps == 1 -> weight
        else -> weight * (1 + reps / 30.0)
    }

    fun volume(sets: List<WorkoutSet>) = sets.sumOf { (it.weight ?: 0.0) * (it.reps ?: 0) }

    fun workingSets(sets: List<WorkoutSet>) = sets.filter { it.done && (it.reps ?: 0) > 0 }

    data class Session(
        val date: LocalDate,
        val workoutId: Long,
        val sets: List<WorkoutSet>,
        val best1rm: Double,
        val topWeight: Double,
        val volume: Double,
    )

    /** Sesiones terminadas de un ejercicio, de la más antigua a la más reciente. */
    fun exerciseHistory(workouts: List<Workout>, exerciseId: Long): List<Session> =
        workouts.filter { it.end != null }
            .sortedWith(compareBy({ it.date }, { it.start }))
            .flatMap { w ->
                w.exercises.filter { it.exerciseId == exerciseId }.mapNotNull { ex ->
                    val sets = workingSets(ex.sets)
                    if (sets.isEmpty()) null else Session(
                        date = w.date,
                        workoutId = w.id,
                        sets = sets,
                        best1rm = sets.maxOf { e1rm(it.weight ?: 0.0, it.reps!!) },
                        topWeight = sets.maxOf { it.weight ?: 0.0 },
                        volume = volume(sets),
                    )
                }
            }

    enum class Kind { NEW, UP, REPS, HOLD, DELOAD }

    data class Suggestion(val weight: Double?, val reps: Int, val sets: Int, val kind: Kind, val reason: String)

    /**
     * Doble progresión: se trabaja dentro del rango de repeticiones del ejercicio; cuando todas las
     * series con el peso principal llegan al tope se sube el peso, si no se busca una repetición
     * más. Dos sesiones seguidas por debajo del mínimo con el mismo peso → descarga del 10 %.
     */
    fun suggestNext(history: List<Session>, ex: Exercise): Suggestion {
        val inc = if (ex.increment > 0) ex.increment else 2.5
        val last = history.lastOrNull()
            ?: return Suggestion(
                null, ex.repMin, 3, Kind.NEW,
                "Primera vez: elige un peso con el que hagas ${ex.repMin}-${ex.repMax} reps dejando 2-3 en reserva.",
            )
        val top = last.topWeight
        val topSets = last.sets.filter { (it.weight ?: 0.0) == top }
        val nSets = max(1, topSets.size)
        val minReps = topSets.minOf { it.reps!! }
        val hardRpe = topSets.any { (it.rpe ?: 0.0) > 9 }

        if (top == 0.0) {
            return Suggestion(0.0, minReps + 1, nSets, Kind.REPS, "Peso corporal: suma una repetición por serie.")
        }
        if (minReps >= ex.repMax && !hardRpe) {
            return Suggestion(
                top + inc, ex.repMin, nSets, Kind.UP,
                "Llegaste a ${ex.repMax} reps en todas las series: sube ${fmtKg(inc)} kg y vuelve a ${ex.repMin} reps.",
            )
        }
        if (minReps < ex.repMin) {
            val prev = history.getOrNull(history.size - 2)
            val prevFailed = prev != null && prev.topWeight == top &&
                prev.sets.filter { (it.weight ?: 0.0) == top }.minOf { it.reps!! } < ex.repMin
            return if (prevFailed) {
                Suggestion(
                    max(inc, roundTo(top * 0.9, inc)), ex.repMin, nSets, Kind.DELOAD,
                    "Dos sesiones por debajo del rango con este peso: descarga un 10 % y reconstruye.",
                )
            } else {
                Suggestion(
                    top, ex.repMin, nSets, Kind.HOLD,
                    "Quedaste por debajo de ${ex.repMin} reps: repite el peso y busca llegar al rango.",
                )
            }
        }
        return Suggestion(
            top, min(ex.repMax, minReps + 1), nSets, Kind.REPS,
            "Mantén ${fmtKg(top)} kg y busca +1 rep (meta: ${ex.repMax} reps en todas las series).",
        )
    }

    /** Series efectivas por grupo muscular en los 7 días que terminan en [end]. */
    fun weeklySetsByMuscle(workouts: List<Workout>, exercises: List<Exercise>, end: LocalDate): Map<String, Int> {
        val from = end.minusDays(6)
        val byId = exercises.associateBy { it.id }
        val out = LinkedHashMap<String, Int>()
        for (w in workouts) {
            if (w.end == null || w.date.isBefore(from) || w.date.isAfter(end)) continue
            for (ex in w.exercises) {
                val muscle = byId[ex.exerciseId]?.muscle ?: "Otro"
                out[muscle] = (out[muscle] ?: 0) + workingSets(ex.sets).size
            }
        }
        return out
    }

    fun fmtKg(x: Double): String =
        if (x == Math.floor(x)) x.toLong().toString() else String.format(java.util.Locale.US, "%.1f", x).trimEnd('0').trimEnd('.')
}
