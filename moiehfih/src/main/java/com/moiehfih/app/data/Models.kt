package com.moiehfih.app.data

import java.time.LocalDate

enum class Sex { M, F }

enum class ActivityLevel(val label: String, val factor: Double) {
    SEDENTARIO("Sedentario (poco o nada de ejercicio)", 1.2),
    LIGERO("Ligero (1-3 días/semana)", 1.375),
    MODERADO("Moderado (3-5 días/semana)", 1.55),
    ALTO("Alto (6-7 días/semana)", 1.725),
    MUY_ALTO("Muy alto (trabajo físico + entreno)", 1.9),
}

enum class Meal(val label: String) {
    DESAYUNO("Desayuno"), COMIDA("Comida"), CENA("Cena"), SNACKS("Snacks")
}

val MUSCLES = listOf(
    "Pecho", "Espalda", "Hombros", "Bíceps", "Tríceps", "Cuádriceps",
    "Isquiotibiales", "Glúteos", "Pantorrillas", "Abdomen", "Otro",
)

data class Profile(
    val name: String,
    val sex: Sex,
    val age: Int,
    val heightCm: Double,
    val activity: ActivityLevel,
)

/** ratePct: % del peso corporal por semana (negativo = perder). fatPct: fracción de las kcal (0.27). */
data class Goal(
    val ratePct: Double = 0.0,
    val proteinGPerKg: Double = 2.0,
    val fatPct: Double = 0.27,
)

data class MacroTargets(val kcal: Int, val p: Int, val c: Int, val f: Int)

data class Targets(
    val date: LocalDate,
    val macros: MacroTargets,
    val tdee: Int?,
    val manual: Boolean,
)

data class Macros(val kcal: Double, val p: Double, val c: Double, val f: Double) {
    operator fun plus(o: Macros) = Macros(kcal + o.kcal, p + o.p, c + o.c, f + o.f)
    operator fun times(k: Double) = Macros(kcal * k, p * k, c * k, f * k)

    companion object {
        val ZERO = Macros(0.0, 0.0, 0.0, 0.0)
        fun fromMacros(p: Double, c: Double, f: Double) = Macros(p * 4 + c * 4 + f * 9, p, c, f)
    }
}

/** Valores nutricionales por 100 g. */
data class Food(
    val id: Long,
    val name: String,
    val per100: Macros,
    val servingG: Double,
    val servingName: String,
    val builtin: Boolean,
) {
    fun forGrams(grams: Double) = per100 * (grams / 100.0)
}

data class FoodEntry(
    val id: Long,
    val date: LocalDate,
    val meal: Meal,
    val foodId: Long?,
    val name: String,
    val grams: Double?,
    val macros: Macros,
)

data class Exercise(
    val id: Long,
    val name: String,
    val muscle: String,
    val repMin: Int,
    val repMax: Int,
    val increment: Double,
    val builtin: Boolean,
)

data class Routine(val id: Long, val name: String, val exerciseIds: List<Long>)

data class WorkoutSet(
    var weight: Double?,
    var reps: Int?,
    var rpe: Double? = null,
    var done: Boolean = false,
)

data class WorkoutExercise(
    val exerciseId: Long,
    val sets: MutableList<WorkoutSet>,
)

/** Un entreno; `end == null` significa que está en curso. */
data class Workout(
    val id: Long,
    var date: LocalDate,
    var name: String,
    val start: Long,
    var end: Long?,
    var notes: String,
    val exercises: MutableList<WorkoutExercise>,
)

data class JournalEntry(
    val date: LocalDate,
    val sleepH: Double? = null,
    val sleepQ: Int? = null,
    val energy: Int? = null,
    val mood: Int? = null,
    val hunger: Int? = null,
    val stress: Int? = null,
    val steps: Int? = null,
    val notes: String = "",
) {
    val isEmpty get() = sleepH == null && sleepQ == null && energy == null && mood == null &&
        hunger == null && stress == null && steps == null && notes.isBlank()
}

data class Checkin(
    val date: LocalDate,
    val tdee: Int,
    val weight: Double,
    val macros: MacroTargets,
)
