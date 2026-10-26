package com.moiehfih.app.logic

import com.moiehfih.app.data.ActivityLevel
import com.moiehfih.app.data.Exercise
import com.moiehfih.app.data.Goal
import com.moiehfih.app.data.Profile
import com.moiehfih.app.data.Sex
import com.moiehfih.app.data.Workout
import com.moiehfih.app.data.WorkoutExercise
import com.moiehfih.app.data.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

class CalcTest {
    private val d0 = LocalDate.of(2026, 1, 1)

    @Test
    fun trendInterpolatesAndSmooths() {
        val s = Calc.trendSeries(mapOf(d0 to 80.0, d0.plusDays(2) to 82.0), d0.plusDays(4))
        assertEquals(5, s.size)
        assertEquals(80.0, s[0].trend, 1e-9)
        assertNull(s[1].scale)
        // Día 2 interpolado = 81 → 80 + 0.1 × 1
        assertEquals(80.1, s[1].trend, 1e-9)
        assertTrue(s[4].trend in 80.0..82.0)
        assertNull(Calc.trendOn(s, d0.minusDays(1)))
        assertEquals(s[4].trend, Calc.trendOn(s, d0.plusDays(10))!!, 1e-9)
    }

    @Test
    fun expenditureConvergesToTrueValue() {
        // Estimación inicial baja (sedentario) frente a un gasto real de 2800.
        val profile = Profile("", Sex.M, 30, 180.0, ActivityLevel.SEDENTARIO)
        val weights = HashMap<LocalDate, Double>()
        val intake = HashMap<LocalDate, Double>()
        var w = 85.0
        for (i in 0 until 120) {
            val d = d0.plusDays(i.toLong())
            intake[d] = 2300.0
            w += (2300 - 2800) / Calc.KCAL_PER_KG
            weights[d] = w
        }
        val exp = Calc.expenditureSeries(profile, weights, intake, d0.plusDays(119))
        assertTrue("inicial ${exp.first().tdee}", exp.first().tdee < 2500)
        assertTrue("final ${exp.last().tdee}", abs(exp.last().tdee - 2800) < 120)
    }

    @Test
    fun expenditureIgnoresTooFewLoggedDays() {
        val profile = Profile("", Sex.F, 30, 165.0, ActivityLevel.MODERADO)
        val weights = (0 until 30).associate { d0.plusDays(it.toLong()) to 60.0 }
        val intake = mapOf(d0 to 1000.0, d0.plusDays(1) to 1000.0)
        val exp = Calc.expenditureSeries(profile, weights, intake, d0.plusDays(29))
        assertEquals(exp.first().tdee, exp.last().tdee, 1e-9)
    }

    @Test
    fun targetsForDeficitAndFloor() {
        val t = Calc.computeTargets(2800.0, 80.0, Goal(-0.5, 2.0, 0.25), Sex.M)
        // 0.5 % de 80 kg = 0.4 kg/sem ≈ 440 kcal/día
        assertEquals(2360, t.kcal)
        assertEquals(160, t.p)
        assertTrue(abs(t.p * 4 + t.c * 4 + t.f * 9 - t.kcal) < 10)
        assertEquals(1200, Calc.computeTargets(1300.0, 60.0, Goal(-1.0, 2.0, 0.25), Sex.F).kcal)
    }

    private val bench = Exercise(1, "Press de banca", "Pecho", 6, 10, 2.5, true)

    private fun session(day: Int, weight: Double, vararg reps: Int) = Workout(
        id = day.toLong(), date = d0.plusDays(day.toLong()), name = "", start = day.toLong(), end = day + 1L, notes = "",
        exercises = mutableListOf(WorkoutExercise(1, reps.map { WorkoutSet(weight, it, null, true) }.toMutableList())),
    )

    private fun suggest(vararg ws: Workout) = Calc.suggestNext(Calc.exerciseHistory(ws.toList(), 1), bench)

    @Test
    fun progressionAddsWeightAtTopOfRange() {
        val s = suggest(session(0, 60.0, 10, 10, 10))
        assertEquals(Calc.Kind.UP, s.kind)
        assertEquals(62.5, s.weight!!, 1e-9)
        assertEquals(6, s.reps)
        assertEquals(3, s.sets)
    }

    @Test
    fun progressionAddsRepInsideRange() {
        val s = suggest(session(0, 60.0, 8, 7, 7))
        assertEquals(Calc.Kind.REPS, s.kind)
        assertEquals(60.0, s.weight!!, 1e-9)
        assertEquals(8, s.reps)
    }

    @Test
    fun progressionHoldsThenDeloads() {
        val a = session(0, 70.0, 5, 5, 4)
        val b = session(3, 70.0, 5, 4, 4)
        assertEquals(Calc.Kind.HOLD, suggest(a).kind)
        val s = suggest(a, b)
        assertEquals(Calc.Kind.DELOAD, s.kind)
        assertEquals(62.5, s.weight!!, 1e-9)
    }

    @Test
    fun progressionIgnoresUnfinishedAndUndoneSets() {
        val active = session(5, 100.0, 10, 10, 10).apply { end = null }
        val undone = session(1, 90.0, 10, 10, 10).apply { exercises[0].sets.forEach { it.done = false } }
        assertEquals(Calc.Kind.NEW, suggest(active, undone).kind)
    }

    @Test
    fun oneRepMaxAndWeeklySets() {
        assertEquals(100.0, Calc.e1rm(100.0, 1), 1e-9)
        assertEquals(133.33, Calc.e1rm(100.0, 10), 0.01)
        val sets = Calc.weeklySetsByMuscle(
            listOf(session(10, 60.0, 8, 8, 8), session(0, 60.0, 8)), listOf(bench), d0.plusDays(12),
        )
        assertEquals(mapOf("Pecho" to 3), sets)
    }

    @Test
    fun formatsKg() {
        assertEquals("2.5", Calc.fmtKg(2.5))
        assertEquals("60", Calc.fmtKg(60.0))
    }
}
