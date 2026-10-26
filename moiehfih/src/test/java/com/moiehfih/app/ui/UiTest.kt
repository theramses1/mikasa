package com.moiehfih.app.ui

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.moiehfih.app.data.ActivityLevel
import com.moiehfih.app.data.AppData
import com.moiehfih.app.data.Demo
import com.moiehfih.app.data.Goal
import com.moiehfih.app.data.Meal
import com.moiehfih.app.data.Profile
import com.moiehfih.app.data.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.LocalDate

/** Pruebas de las pantallas reales con toques simulados. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiTest {

    private lateinit var data: AppData
    private val today = LocalDate.now()

    @Before
    fun setUp() {
        AppData.resetForTests()
        data = AppData.get(RuntimeEnvironment.getApplication())
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).setup().get().also { idle() }

    private fun views(root: View): List<View> =
        if (root is ViewGroup) listOf(root) + (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else listOf(root)

    private fun MainActivity.all(): List<View> = views(window.decorView)

    private fun MainActivity.texts(): List<String> = all().filterIsInstance<TextView>().map { it.text.toString() }

    private fun MainActivity.has(text: String) = texts().any { it.contains(text) }

    private fun MainActivity.view(text: String): TextView =
        all().filterIsInstance<TextView>().firstOrNull { it.text.toString() == text && it.isShown }
            ?: error("No se encontró «$text». Textos: ${texts().filter { it.isNotBlank() }.take(40)}")

    private fun MainActivity.click(text: String) {
        view(text).performClick()
        idle()
    }

    /** Toca la fila de lista (o tarjeta) que contiene [text]. */
    private fun MainActivity.clickRow(text: String) {
        var v: View? = view(text)
        while (v != null && !v.hasOnClickListeners()) v = v.parent as? View
        assertNotNull("Sin elemento tocable para «$text»", v)
        v!!.performClick()
        idle()
    }

    /** EditText del campo con la etiqueta dada (ver Kit.field). */
    private fun MainActivity.field(label: String): EditText {
        val box = view(label).parent as LinearLayout
        return box.getChildAt(1) as EditText
    }

    private fun dialogPositive() {
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
    }

    private fun setupProfile() {
        data.saveProfile(Profile("", Sex.M, 30, 178.0, ActivityLevel.MODERADO), Goal(-0.5))
        data.setWeight(today, 80.0)
        data.recalcTargets()
    }

    @Test
    fun onboardingCreatesProfileAndTargets() {
        val act = launch()
        assertTrue(act.has("Tu coach de nutrición y entreno"))
        act.field("Edad").setText("30")
        act.field("Estatura (cm)").setText("175")
        act.field("Peso actual (kg)").setText("80")
        act.click("Empezar")
        assertNotNull(data.profile)
        assertEquals(80.0, data.weights[today]!!, 1e-9)
        assertTrue(data.currentTargets().kcal > 1500)
        assertTrue(act.has("Hoy"))
        assertTrue(act.has("Gasto energético"))
    }

    @Test
    fun onboardingValidatesInput() {
        val act = launch()
        act.click("Empezar")
        assertNull(data.profile)
    }

    @Test
    fun demoDataRendersEveryScreen() {
        Demo.load(data)
        val act = launch()
        assertTrue(act.has("Check-in semanal disponible"))
        val expected = mapOf(
            MainActivity.Tab.DIET to "Resumen del día",
            MainActivity.Tab.TRAIN to "+ Nueva rutina",
            MainActivity.Tab.WEIGHT to "Gasto energético vs ingesta",
            MainActivity.Tab.JOURNAL to "Guardar bitácora",
            MainActivity.Tab.HOME to "Plan",
        )
        for ((tab, text) in expected) {
            act.selectTab(tab)
            idle()
            assertTrue("$tab debería mostrar «$text»", act.has(text))
        }
        act.selectTab(MainActivity.Tab.TRAIN)
        act.click("Historial")
        act.clickRow("Torso A")
        assertTrue(act.has("kg de volumen"))
        act.onBackPressed()
        idle()
        act.click("Ejercicios")
        act.clickRow("Press de banca")
        assertTrue(act.has("Próxima sesión"))
        assertTrue(act.has("1RM estimado por sesión"))
    }

    @Test
    fun weeklyCheckinUpdatesTargets() {
        Demo.load(data)
        val act = launch()
        val before = data.targets!!
        assertTrue(data.checkinDue())
        act.click("Hacer check-in")
        val after = data.targets!!
        assertEquals(today, after.date)
        assertFalse(data.checkinDue())
        assertEquals(1, data.checkins.size)
        assertFalse(act.has("Check-in semanal disponible"))
        // El gasto de la demo es ~2750 kcal; con −0.5 %/sem el objetivo queda claramente por debajo.
        assertTrue("${after.macros.kcal}", after.macros.kcal in 2000..2600)
        assertTrue(before.date < after.date)
    }

    @Test
    fun addFoodWithServingsAndEditIt() {
        setupProfile()
        val act = launch()
        act.selectTab(MainActivity.Tab.DIET)
        idle()
        act.all().filterIsInstance<TextView>().first { it.text == "+ Añadir alimento" }.performClick() // Desayuno
        idle()
        act.clickRow("Avena en hojuelas")
        dialogPositive() // 1 porción = 40 g
        val e = data.entries[today]!!.single()
        assertEquals(Meal.DESAYUNO, e.meal)
        assertEquals(40.0, e.grams!!, 1e-9)
        assertEquals(389 * 0.4, e.macros.kcal, 1e-6)
        assertTrue(act.has("Resumen del día"))

        // Editar: 100 g.
        act.clickRow("Avena en hojuelas")
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val amount = views(dialog.window!!.decorView).filterIsInstance<EditText>().first()
        assertEquals("40", amount.text.toString())
        amount.setText("100")
        dialogPositive()
        assertEquals(100.0, data.entries[today]!!.single().grams!!, 1e-9)
    }

    @Test
    fun quickAddAndCopyPreviousDay() {
        setupProfile()
        val act = launch()
        act.selectTab(MainActivity.Tab.DIET)
        idle()
        act.click("‹") // ayer
        act.all().filterIsInstance<TextView>().first { it.text == "+ Añadir alimento" }.performClick()
        idle()
        act.click("Entrada rápida")
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val inputs = views(dialog.window!!.decorView).filterIsInstance<EditText>()
        inputs[1].setText("30") // proteína
        inputs[2].setText("50") // carbos
        inputs[3].setText("10") // grasa
        dialogPositive()
        val e = data.entries[today.minusDays(1)]!!.single()
        assertEquals(30 * 4 + 50 * 4 + 10 * 9.0, e.macros.kcal, 1e-9)
        assertTrue(act.has("Resumen del día"))

        act.click("›") // hoy
        assertNull(data.entries[today])
        act.click("Copiar día anterior")
        val copied = data.entries[today]!!.single()
        assertEquals(e.macros, copied.macros)
        assertTrue(copied.id != e.id)
    }

    @Test
    fun workoutFlowSuggestsProgression() {
        setupProfile()
        val act = launch()
        act.selectTab(MainActivity.Tab.TRAIN)
        idle()
        act.all().filterIsInstance<TextView>().first { it.text == "Empezar" }.performClick() // Torso A
        idle()
        val w = data.activeWorkout()!!
        assertEquals("Torso A", w.name)
        assertTrue(act.has("Primera vez"))
        // Press de banca 60 kg × 10 en las 3 series → la próxima vez debe sugerir subir peso.
        w.exercises[0].sets.forEach { it.weight = 60.0; it.reps = 10 }
        data.saveWorkout(w)
        act.refresh()
        idle()
        for (i in 1..3) {
            act.all().filter { it.contentDescription == "Serie $i hecha" }.first().performClick()
            idle()
        }
        assertTrue(w.exercises[0].sets.all { it.done })
        act.click("Terminar entreno")
        assertNull(data.activeWorkout())
        val saved = data.finishedWorkouts().single()
        assertEquals(1, saved.exercises.size) // solo el ejercicio con series hechas
        assertEquals(3, saved.exercises[0].sets.size)

        // Nuevo entreno: prellenado con la sugerencia (62.5 kg × 6).
        act.selectTab(MainActivity.Tab.TRAIN)
        idle()
        act.all().filterIsInstance<TextView>().first { it.text == "Empezar" }.performClick()
        idle()
        val next = data.activeWorkout()!!.exercises[0].sets
        assertEquals(62.5, next[0].weight!!, 1e-9)
        assertEquals(6, next[0].reps)
        assertTrue(act.has("Sugerencia: 62.5 kg × 6"))
    }

    @Test
    fun weightAndJournalAreSaved() {
        setupProfile()
        val act = launch()
        act.selectTab(MainActivity.Tab.WEIGHT)
        idle()
        val kg = act.all().filterIsInstance<EditText>().first()
        kg.setText("79.4")
        act.click("Guardar")
        assertEquals(79.4, data.weights[today]!!, 1e-9)

        act.selectTab(MainActivity.Tab.JOURNAL)
        idle()
        act.field("Horas de sueño").setText("7.5")
        act.field("Notas").setText("Buen día")
        act.click("Guardar bitácora")
        val j = data.journal[today]!!
        assertEquals(7.5, j.sleepH!!, 1e-9)
        assertEquals("Buen día", j.notes)
    }

    @Test
    fun backupRoundTrip() {
        Demo.load(data)
        val json = data.exportJson()
        val entries = data.entries.values.sumOf { it.size }
        val workouts = data.workouts.size
        val weights = data.weights.size
        data.resetAll()
        assertNull(data.profile)
        data.importJson(json)
        assertNotNull(data.profile)
        assertEquals(entries, data.entries.values.sumOf { it.size })
        assertEquals(workouts, data.workouts.size)
        assertEquals(weights, data.weights.size)
        assertEquals(data.foods.size, data.foods.distinctBy { it.id }.size)
    }

    @Test
    fun dataSurvivesReload() {
        setupProfile()
        data.setWeight(today.minusDays(1), 80.6)
        AppData.resetForTests()
        val reloaded = AppData.get(RuntimeEnvironment.getApplication())
        assertEquals(2, reloaded.weights.size)
        assertEquals(Sex.M, reloaded.profile!!.sex)
        assertEquals(-0.5, reloaded.goal.ratePct, 1e-9)
    }
}
