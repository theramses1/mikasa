package com.moiehfih.app.data

import com.moiehfih.app.logic.Calc
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/** Genera ~10 semanas de datos realistas para explorar la app. */
object Demo {
    fun load(data: AppData, today: LocalDate = LocalDate.now()) {
        data.resetAll()
        val rnd = Random(42)
        val days = 70
        val start = today.minusDays((days - 1).toLong())
        data.batch {
            data.saveProfile(
                Profile("Demo", Sex.M, 29, 178.0, ActivityLevel.MODERADO),
                Goal(ratePct = -0.5, proteinGPerKg = 2.0, fatPct = 0.27),
            )
            val foods = data.foods.associateBy { it.name }
            val plan = listOf(
                Triple(Meal.DESAYUNO, "Avena en hojuelas", 60.0),
                Triple(Meal.DESAYUNO, "Leche descremada", 250.0),
                Triple(Meal.DESAYUNO, "Plátano", 120.0),
                Triple(Meal.DESAYUNO, "Proteína whey", 30.0),
                Triple(Meal.COMIDA, "Pechuga de pollo cocida", 180.0),
                Triple(Meal.COMIDA, "Arroz blanco cocido", 220.0),
                Triple(Meal.COMIDA, "Aguacate", 50.0),
                Triple(Meal.COMIDA, "Brócoli", 100.0),
                Triple(Meal.CENA, "Huevo entero", 150.0),
                Triple(Meal.CENA, "Tortilla de maíz", 90.0),
                Triple(Meal.CENA, "Frijoles cocidos", 130.0),
                Triple(Meal.SNACKS, "Yogur griego natural 0 %", 170.0),
                Triple(Meal.SNACKS, "Almendras", 28.0),
            )
            val trueTdee = 2750.0
            var real = 84.0
            for (i in 0 until days) {
                val d = start.plusDays(i.toLong())
                val skip = rnd.nextDouble() < 0.08 && i < days - 1
                if (!skip) {
                    val factor = 0.85 + rnd.nextDouble() * 0.35
                    var kcal = 0.0
                    for ((meal, name, g) in plan) {
                        val food = foods.getValue(name)
                        val grams = Calc.roundTo(g * factor, 5.0)
                        val m = food.forGrams(grams)
                        kcal += m.kcal
                        data.saveEntry(FoodEntry(0, d, meal, food.id, food.name, grams, m, food.unit))
                    }
                    if (rnd.nextDouble() < 0.2) {
                        val beer = foods.getValue("Cerveza")
                        val m = beer.forGrams(710.0)
                        kcal += m.kcal
                        data.saveEntry(FoodEntry(0, d, Meal.SNACKS, beer.id, beer.name, 710.0, m, beer.unit))
                    }
                    real += (kcal - trueTdee) / Calc.KCAL_PER_KG
                } else {
                    real += (2600 - trueTdee) / Calc.KCAL_PER_KG
                }
                if (rnd.nextDouble() < 0.85) data.setWeight(d, Calc.roundTo(real + (rnd.nextDouble() - 0.5) * 1.6, 0.1))
                repeat(4 + rnd.nextInt(6)) { data.addWater(d, if (rnd.nextBoolean()) 250 else 500) }
                if (d.dayOfWeek.value in listOf(3, 6, 7) && rnd.nextDouble() < 0.8) {
                    val type = listOf(CardioType.CORRER, CardioType.CAMINAR, CardioType.BICICLETA)[rnd.nextInt(3)]
                    val minutes = 25 + rnd.nextInt(40)
                    val km = when (type) {
                        CardioType.CORRER -> minutes / 6.0
                        CardioType.CAMINAR -> minutes / 12.0
                        else -> minutes / 2.6
                    }
                    data.saveCardio(
                        CardioSession(0, d, type, minutes, Calc.roundTo(km, 0.1), Calc.cardioKcal(type.met, real, minutes), 120 + rnd.nextInt(40)),
                    )
                }
                // Datos como los que importaría Health Connect desde un reloj.
                if (i >= days - 21) {
                    data.saveHealthDay(
                        HealthDay(
                            d, steps = 5000L + rnd.nextInt(8000), activeKcal = 300.0 + rnd.nextInt(400), distanceM = 4000.0 + rnd.nextInt(6000),
                            hrAvg = 70L + rnd.nextInt(10), hrMin = 52L + rnd.nextInt(6), hrMax = 140L + rnd.nextInt(40),
                            restingHr = 56L + rnd.nextInt(6), sleepMin = 380L + rnd.nextInt(120), spo2 = 95.0 + rnd.nextInt(4),
                            bpSys = 112.0 + rnd.nextInt(14), bpDia = 70.0 + rnd.nextInt(10),
                        ),
                    )
                }
                data.saveJournal(
                    JournalEntry(
                        d,
                        sleepH = Calc.roundTo(6 + rnd.nextDouble() * 2.5, 0.5),
                        sleepQ = 2 + rnd.nextInt(4),
                        energy = 2 + rnd.nextInt(4),
                        mood = 2 + rnd.nextInt(4),
                        hunger = 1 + rnd.nextInt(5),
                        stress = 1 + rnd.nextInt(4),
                        steps = 5000 + rnd.nextInt(7000),
                        notes = if (i % 9 == 0) "Buen día, mucha energía en el gym." else "",
                    ),
                )
            }

            // Entrenos: Torso A lunes/jueves, Pierna A martes/viernes, con progresión.
            val startWeights = mapOf(
                "Press de banca" to 70.0, "Sentadilla" to 90.0, "Peso muerto rumano" to 80.0,
                "Prensa" to 140.0, "Press militar" to 45.0, "Remo con barra" to 60.0,
            )
            val state = HashMap<Long, Pair<Double, Int>>()
            for (i in 0 until days) {
                val d = start.plusDays(i.toLong())
                val routine = when (d.dayOfWeek.value) {
                    1, 4 -> data.routines.getOrNull(0)
                    2, 5 -> data.routines.getOrNull(1)
                    else -> null
                } ?: continue
                val exercises = routine.exerciseIds.mapNotNull { data.exercise(it) }.map { ex ->
                    val (w, r) = state.getOrPut(ex.id) { (startWeights[ex.name] ?: 25.0) to ex.repMin }
                    val sets = (0 until 3).map { k ->
                        val reps = maxOf(ex.repMin - 1, r - if (k == 2 && rnd.nextBoolean()) 1 else 0)
                        WorkoutSet(w, reps, 8.0, true)
                    }
                    state[ex.id] = if (sets.all { it.reps!! >= ex.repMax }) {
                        (w + ex.increment) to ex.repMin
                    } else {
                        w to minOf(ex.repMax, r + if (rnd.nextDouble() < 0.7) 1 else 0)
                    }
                    WorkoutExercise(ex.id, sets.toMutableList())
                }
                val t = d.atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                data.saveWorkout(Workout(0, d, routine.name, t, t + 65 * 60_000, "", exercises.toMutableList()))
            }
        }
        // Check-in "vencido" para que se vea la tarjeta de check-in semanal.
        data.recalcTargets(today.minusDays(7))
        data.targets?.let { data.setTargets(it.copy(date = today.minusDays(7))) }
        data.setHealthState(false, null)
    }
}
