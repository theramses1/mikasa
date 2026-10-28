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

data class Macros(val kcal: Double, val p: Double, val c: Double, val f: Double, val sugar: Double = 0.0) {
    operator fun plus(o: Macros) = Macros(kcal + o.kcal, p + o.p, c + o.c, f + o.f, sugar + o.sugar)
    operator fun times(k: Double) = Macros(kcal * k, p * k, c * k, f * k, sugar * k)

    companion object {
        val ZERO = Macros(0.0, 0.0, 0.0, 0.0)
        fun fromMacros(p: Double, c: Double, f: Double, sugar: Double = 0.0) = Macros(p * 4 + c * 4 + f * 9, p, c, f, sugar)
    }
}

/** Unidad de cantidad de un alimento: sólidos en gramos, líquidos en mililitros. */
enum class FoodUnit(val symbol: String) { G("g"), ML("ml") }

/** Valores nutricionales por 100 g (o por 100 ml si [unit] es ML). */
data class Food(
    val id: Long,
    val name: String,
    val per100: Macros,
    val servingG: Double,
    val servingName: String,
    val builtin: Boolean,
    val unit: FoodUnit = FoodUnit.G,
) {
    /** Macros para una cantidad en la unidad del alimento (g o ml). */
    fun forGrams(amount: Double) = per100 * (amount / 100.0)
}

/** [grams] es la cantidad en la unidad [unit] (g o ml); null en entradas rápidas. */
data class FoodEntry(
    val id: Long,
    val date: LocalDate,
    val meal: Meal,
    val foodId: Long?,
    val name: String,
    val grams: Double?,
    val macros: Macros,
    val unit: FoodUnit = FoodUnit.G,
)

data class WaterEntry(val id: Long, val date: LocalDate, val ml: Int, val time: Long)

/** Tipos de cardio con su MET aproximado (Compendium of Physical Activities). */
enum class CardioType(val label: String, val met: Double) {
    CAMINAR("Caminar", 3.5),
    CORRER("Correr", 9.8),
    BICICLETA("Bicicleta", 7.5),
    BICI_FIJA("Bicicleta fija", 6.8),
    NADAR("Natación", 7.0),
    ELIPTICA("Elíptica", 5.0),
    REMO("Remo", 7.0),
    ESCALADORA("Escaladora", 9.0),
    HIIT("HIIT", 8.0),
    CUERDA("Saltar cuerda", 11.0),
    SENDERISMO("Senderismo", 6.0),
    BAILE("Baile", 5.0),
    DEPORTE("Deporte", 7.0),
    OTRO("Otro", 5.0),
}

/** Sesión de cardio. [externalId] identifica las importadas de Health Connect. */
data class CardioSession(
    val id: Long,
    val date: LocalDate,
    val type: CardioType,
    val minutes: Int,
    val distanceKm: Double?,
    val kcal: Double?,
    val avgHr: Int?,
    val notes: String = "",
    val externalId: String? = null,
)

/** Resumen diario importado de Health Connect (relojes, pulseras, Google Fit, etc.). */
data class HealthDay(
    val date: LocalDate,
    val steps: Long? = null,
    val activeKcal: Double? = null,
    val distanceM: Double? = null,
    val hrAvg: Long? = null,
    val hrMin: Long? = null,
    val hrMax: Long? = null,
    val restingHr: Long? = null,
    val sleepMin: Long? = null,
    val spo2: Double? = null,
    val bpSys: Double? = null,
    val bpDia: Double? = null,
    val weightKg: Double? = null,
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
