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
import com.moiehfih.app.data.CardioSession
import com.moiehfih.app.data.CardioType
import com.moiehfih.app.data.Demo
import com.moiehfih.app.data.FoodUnit
import com.moiehfih.app.data.HealthDay
import com.moiehfih.app.health.HealthSync
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

    @Test
    fun waterQuickButtonsAndUndo() {
        setupProfile()
        val act = launch()
        assertTrue(act.has("💧 Agua"))
        act.click("+250 ml")
        act.click("+500 ml")
        assertEquals(750, data.waterTotal(today))
        assertTrue(act.has("750 / 2,800 ml"))
        act.click("Deshacer")
        assertEquals(250, data.waterTotal(today))
        act.selectTab(MainActivity.Tab.JOURNAL)
        idle()
        assertTrue(act.has("250 / 2,800 ml"))
    }

    @Test
    fun liquidsUseMillilitersAndSugarIsTracked() {
        setupProfile()
        val act = launch()
        act.selectTab(MainActivity.Tab.DIET)
        idle()
        act.all().filterIsInstance<TextView>().first { it.text == "+ Añadir alimento" }.performClick()
        idle()
        assertTrue(act.has("100 ml: 34 kcal"))
        act.clickRow("Leche descremada")
        dialogPositive() // 1 vaso = 240 ml
        act.all().filterIsInstance<TextView>().first { it.text == "+ Añadir alimento" }.performClick()
        idle()
        act.clickRow("Plátano")
        dialogPositive() // 1 pieza = 118 g
        val list = data.entries[today]!!
        val milk = list.first { it.name == "Leche descremada" }
        assertEquals(FoodUnit.ML, milk.unit)
        assertEquals(240.0, milk.grams!!, 1e-9)
        assertEquals(5.0 * 2.4, milk.macros.sugar, 1e-9)
        assertEquals(12.2 * 1.18, list.first { it.name == "Plátano" }.macros.sugar, 1e-9)
        assertTrue(act.has("240 ml · "))
        assertTrue(act.has("118 g · "))
        assertTrue(act.has("Azúcares (máx.)"))
        assertEquals(5.0 * 2.4 + 12.2 * 1.18, data.dayTotals(today).sugar, 1e-9)
    }

    @Test
    fun cardioIsLoggedWithEstimatedCalories() {
        setupProfile()
        val act = launch()
        act.selectTab(MainActivity.Tab.TRAIN)
        idle()
        act.click("Cardio")
        act.click("+ Registrar cardio")
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val inputs = views(dialog.window!!.decorView).filterIsInstance<EditText>()
        inputs[0].setText("30") // minutos
        inputs[1].setText("5") // km
        dialogPositive()
        val c = data.cardio.single()
        assertEquals(CardioType.CORRER, c.type)
        assertEquals(30, c.minutes)
        assertEquals(5.0, c.distanceKm!!, 1e-9)
        assertEquals(9.8 * 80 * 0.5, c.kcal!!, 1.0)
        assertTrue(act.has("Correr · Hoy"))
        act.selectTab(MainActivity.Tab.HOME)
        idle()
        assertTrue(act.has("Cardio: 1 sesiones · 30 min"))
    }

    @Test
    fun healthImportSavesDaysWithoutDuplicates() {
        setupProfile()
        val fetched = HealthSync.Fetched(
            listOf(
                HealthDay(today, steps = 8000, restingHr = 58, sleepMin = 420, weightKg = 79.0),
                HealthDay(today.minusDays(1), steps = 6000, weightKg = 79.43),
            ),
            listOf(CardioSession(0, today, CardioType.CORRER, 40, 7.0, 450.0, 150, "Importado", "hc:abc")),
            emptyList(),
        )
        val summary = HealthSync.apply(data, fetched)
        assertEquals(2, summary.days)
        assertEquals(1, summary.weights) // hoy ya tenía peso manual
        assertEquals(80.0, data.weights[today]!!, 1e-9)
        assertEquals(79.4, data.weights[today.minusDays(1)]!!, 1e-9)
        assertEquals(8000L, data.health[today]!!.steps)
        assertTrue(data.healthConnected)
        // Volver a sincronizar no duplica la sesión.
        HealthSync.apply(data, fetched)
        assertEquals(1, data.cardio.size)
        val act = launch()
        assertTrue(act.has("Actividad y signos vitales"))
        act.push(HealthScreen(act))
        idle()
        assertTrue(act.has("Pasos (14 días)"))
    }

    @Test
    fun cardioTypesFromHealthConnect() {
        assertEquals(CardioType.CORRER, HealthSync.cardioType(android.health.connect.datatypes.ExerciseSessionType.EXERCISE_SESSION_TYPE_RUNNING))
        assertEquals(CardioType.NADAR, HealthSync.cardioType(android.health.connect.datatypes.ExerciseSessionType.EXERCISE_SESSION_TYPE_SWIMMING_POOL))
        assertNull(HealthSync.cardioType(android.health.connect.datatypes.ExerciseSessionType.EXERCISE_SESSION_TYPE_STRENGTH_TRAINING))
    }

    @Test
    fun upgradesVersion1Database() {
        AppData.resetForTests()
        val app = RuntimeEnvironment.getApplication()
        val file = app.getDatabasePath("moiehfih.db")
        app.deleteDatabase("moiehfih.db")
        file.parentFile!!.mkdirs()
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        old.execSQL(
            "CREATE TABLE foods (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, kcal REAL, p REAL, c REAL, f REAL, " +
                "serving_g REAL, serving_name TEXT, builtin INTEGER DEFAULT 0, deleted INTEGER DEFAULT 0)",
        )
        old.execSQL(
            "CREATE TABLE food_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, meal TEXT NOT NULL, " +
                "food_id INTEGER, name TEXT NOT NULL, grams REAL, kcal REAL, p REAL, c REAL, f REAL)",
        )
        for (sql in listOf(
            "CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            "CREATE TABLE weights (date TEXT PRIMARY KEY, kg REAL NOT NULL)",
            "CREATE TABLE exercises (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, muscle TEXT, rep_min INTEGER, rep_max INTEGER, increment REAL, builtin INTEGER DEFAULT 0, deleted INTEGER DEFAULT 0)",
            "CREATE TABLE routines (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, exercise_ids TEXT NOT NULL)",
            "CREATE TABLE workouts (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, name TEXT NOT NULL, start INTEGER NOT NULL, end_time INTEGER, notes TEXT, data TEXT NOT NULL)",
            "CREATE TABLE journal (date TEXT PRIMARY KEY, sleep_h REAL, sleep_q INTEGER, energy INTEGER, mood INTEGER, hunger INTEGER, stress INTEGER, steps INTEGER, notes TEXT)",
            "CREATE TABLE checkins (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, tdee INTEGER, weight REAL, kcal INTEGER, p INTEGER, c INTEGER, f INTEGER)",
            "INSERT INTO foods (id, name, kcal, p, c, f, serving_g, serving_name, builtin) VALUES (1, 'Leche descremada', 34, 3.4, 5, 0.1, 240, 'vaso', 1)",
            "INSERT INTO food_entries (date, meal, food_id, name, grams, kcal, p, c, f) VALUES ('$today', 'DESAYUNO', 1, 'Leche descremada', 200, 68, 6.8, 10, 0.2)",
            "INSERT INTO weights VALUES ('$today', 81.5)",
        )) old.execSQL(sql)
        old.version = 1
        old.close()

        data = AppData.get(app)
        val e = data.entries[today]!!.single()
        assertEquals(FoodUnit.ML, e.unit)
        assertEquals(5.0 * 2, e.macros.sugar, 1e-9)
        assertEquals(81.5, data.weights[today]!!, 1e-9)
        assertTrue(data.foods.any { it.name == "Jugo de naranja" && it.unit == FoodUnit.ML })
        assertEquals(1, data.foods.count { it.name == "Leche descremada" })
        data.addWater(today, 300)
        assertEquals(300, data.waterTotal(today))
    }

    /** Regresión: al escribir la segunda letra, la búsqueda y la lista desaparecían de la vista. */
    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi") // pantalla de teléfono real: el error solo se ve con lista corta
    fun foodSearchStaysVisibleWhileTyping() {
        setupProfile()
        val act = launch()
        act.push(FoodSearchScreen(act, today, Meal.DESAYUNO))
        idle()
        val search = act.all().filterIsInstance<EditText>().single()
        for (letter in "lec") {
            search.text.append(letter)
            idle()
            val root = act.window.decorView
            root.measure(
                View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, root.width, root.height)
            val loc = IntArray(2)
            search.getLocationInWindow(loc)
            assertTrue("La búsqueda se movió a y=${loc[1]} con «${search.text}»", loc[1] in 0..root.height / 3)
            // La lista empieza justo debajo de la búsqueda.
            val firstRow = act.all().filterIsInstance<TextView>().first { it.text.startsWith("100 ") }
            firstRow.getLocationInWindow(loc)
            assertTrue("La lista quedó fuera de la vista (y=${loc[1]}) con «${search.text}»", loc[1] in 0 until root.height)
        }
        assertTrue(act.has("Leche descremada"))
    }

    private fun addAvena(grams: Double) {
        val avena = data.foods.first { it.name == "Avena en hojuelas" }
        data.saveEntry(com.moiehfih.app.data.FoodEntry(0, today, Meal.DESAYUNO, avena.id, avena.name, grams, avena.forGrams(grams)))
    }

    @Test
    fun editFoodAndRecalculatePastEntries() {
        setupProfile()
        addAvena(40.0)
        val act = launch()
        act.push(SettingsScreen(act))
        idle()
        act.clickRow("Mis alimentos")
        act.clickRow("Avena en hojuelas")
        assertTrue(act.has("Editar alimento"))
        assertEquals("16.9", act.field("Proteína (g)").text.toString())
        act.field("Nombre").setText("Avena integral")
        act.field("Proteína (g)").setText("20")
        act.field("Azúcares (g)").setText("2")
        assertTrue(act.has("Actualizar también mis 1 registros de este alimento"))
        act.click("Guardar cambios")
        val food = data.foods.first { it.name == "Avena integral" }
        assertEquals(20.0, food.per100.p, 1e-9)
        assertEquals(2.0, food.per100.sugar, 1e-9)
        assertFalse(food.builtin) // un alimento base editado pasa a ser del usuario
        val e = data.entries[today]!!.single()
        assertEquals("Avena integral", e.name)
        assertEquals(8.0, e.macros.p, 1e-9)
        assertEquals(0.8, e.macros.sugar, 1e-9)
        assertEquals(389 * 0.4, e.macros.kcal, 1e-6) // kcal propias del alimento, distintas de las de los macros
        assertTrue(act.has("Mis alimentos"))
    }

    @Test
    fun editFoodWithoutTouchingPastEntries() {
        setupProfile()
        addAvena(40.0)
        val act = launch()
        val avena = data.foods.first { it.name == "Avena en hojuelas" }
        act.push(FoodFormScreen(act, avena, null, null))
        idle()
        act.field("Proteína (g)").setText("30")
        act.all().filterIsInstance<android.widget.CheckBox>().single().isChecked = false
        act.click("Guardar cambios")
        assertEquals(30.0, data.food(avena.id)!!.per100.p, 1e-9)
        assertEquals(16.9 * 0.4, data.entries[today]!!.single().macros.p, 1e-9)
    }

    @Test
    fun quantityDialogOpensFoodEditor() {
        setupProfile()
        addAvena(40.0)
        val act = launch()
        act.selectTab(MainActivity.Tab.DIET)
        idle()
        act.clickRow("Avena en hojuelas")
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        idle()
        assertTrue(act.has("Editar alimento"))
        assertTrue(act.has("Guardar cambios"))
    }

    private fun finishedBenchWorkout(): com.moiehfih.app.data.Workout {
        val w = WorkoutActions.start(data, data.routines.first())
        w.exercises[0].sets.forEach { it.weight = 60.0; it.reps = 8; it.done = true }
        WorkoutActions.finish(data, w)
        return data.finishedWorkouts().single()
    }

    @Test
    fun editFinishedWorkout() {
        setupProfile()
        val w = finishedBenchWorkout()
        assertEquals(3, w.exercises.single().sets.size)
        val act = launch()
        act.push(WorkoutDetailScreen(act, w.id))
        idle()
        act.click("✎ Editar entreno")
        assertTrue(act.has("Editar entreno"))
        val inputs = act.all().filterIsInstance<EditText>()
        // [0] nombre, luego por serie: kg, reps, RPE.
        assertEquals("60", inputs[1].text.toString())
        inputs[0].setText("Torso pesado")
        inputs[1].setText("62.5")
        inputs[2].setText("10")
        act.click("− Serie")
        act.click("Guardar cambios")
        val saved = data.finishedWorkouts().single()
        assertEquals("Torso pesado", saved.name)
        val sets = saved.exercises.single().sets
        assertEquals(2, sets.size)
        assertEquals(62.5, sets[0].weight!!, 1e-9)
        assertEquals(10, sets[0].reps)
        assertTrue(sets.all { it.done })
        assertNotNull(saved.end)
        // La sugerencia usa los datos corregidos: 62.5 × 10 llega al tope del rango (6-10) → subir a 65.
        val s = WorkoutActions.suggestion(data, data.exercise(saved.exercises.single().exerciseId)!!)
        assertEquals(65.0, s.weight!!, 1e-9)
        assertTrue(act.has("Torso pesado"))
    }

    @Test
    fun cancelWorkoutEditKeepsOriginal() {
        setupProfile()
        val w = finishedBenchWorkout()
        val act = launch()
        act.push(WorkoutEditScreen(act, w.id))
        idle()
        act.all().filterIsInstance<EditText>()[1].setText("100")
        act.click("Cancelar")
        assertEquals(60.0, data.finishedWorkouts().single().exercises.single().sets[0].weight!!, 1e-9)
    }

    @Test
    fun fiberIsTrackedAndGoalsAreEditable() {
        setupProfile()
        addAvena(40.0)
        assertEquals(10.6 * 0.4, data.dayTotals(today).fiber, 1e-9)
        val act = launch()
        assertTrue(act.has("Fibra (meta)"))
        val auto = data.sugarGoal()
        act.push(SettingsScreen(act))
        idle()
        act.field("Límite de azúcar (g/día)").setText("30")
        act.field("Meta de fibra (g/día)").setText("35")
        act.view("Azúcar y fibra").let { title ->
            // El botón «Guardar» de esta tarjeta es el último hijo de la tarjeta.
            val card = title.parent as LinearLayout
            card.getChildAt(card.childCount - 1).performClick()
        }
        idle()
        assertEquals(30, data.sugarGoal())
        assertEquals(35, data.fiberGoal())
        act.onBackPressed()
        idle()
        assertTrue(act.has("4 / 35 g")) // fibra de hoy frente a la meta
        // Vaciar los campos vuelve al cálculo automático.
        data.setSugarGoal(null)
        assertEquals(auto, data.sugarGoal())
    }

    @Test
    fun mexicanFoodsAreSearchable() {
        setupProfile()
        val act = launch()
        act.push(FoodSearchScreen(act, today, Meal.COMIDA))
        idle()
        val search = act.all().filterIsInstance<EditText>().single()
        search.setText("enchil")
        idle()
        for (n in listOf("Enchiladas rojas con pollo", "Enchiladas verdes con pollo", "Enchiladas suizas")) assertTrue(n, act.has(n))
        search.setText("gordita")
        idle()
        assertTrue(act.has("Gordita de chicharrón prensado"))
        act.clickRow("Gordita de chicharrón prensado")
        dialogPositive() // 1 pieza = 120 g
        val e = data.entries[today]!!.single()
        assertEquals(360.0, e.macros.kcal, 1e-6)
        assertEquals(3.6, e.macros.fiber, 1e-9)
        // Al añadir, vuelve a la pantalla anterior (Inicio); en una búsqueda nueva aparecen los chiles.
        assertTrue(act.has("Gasto energético"))
        act.push(FoodSearchScreen(act, today, Meal.COMIDA))
        idle()
        act.all().filterIsInstance<EditText>().single().setText("chile")
        idle()
        assertTrue(act.has("Chile jalapeño"))
        assertTrue(act.has("Chile relleno capeado (queso)"))
    }

    @Test
    fun upgradesVersion2DatabaseWithFiber() {
        AppData.resetForTests()
        val app = RuntimeEnvironment.getApplication()
        val file = app.getDatabasePath("moiehfih.db")
        app.deleteDatabase("moiehfih.db")
        file.parentFile!!.mkdirs()
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        for (sql in listOf(
            "CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            "CREATE TABLE foods (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, kcal REAL, p REAL, c REAL, f REAL, serving_g REAL, serving_name TEXT, builtin INTEGER DEFAULT 0, deleted INTEGER DEFAULT 0, sugar REAL NOT NULL DEFAULT 0, unit TEXT NOT NULL DEFAULT 'G')",
            "CREATE TABLE food_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, meal TEXT NOT NULL, food_id INTEGER, name TEXT NOT NULL, grams REAL, kcal REAL, p REAL, c REAL, f REAL, sugar REAL NOT NULL DEFAULT 0, unit TEXT NOT NULL DEFAULT 'G')",
            "CREATE TABLE weights (date TEXT PRIMARY KEY, kg REAL NOT NULL)",
            "CREATE TABLE exercises (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, muscle TEXT, rep_min INTEGER, rep_max INTEGER, increment REAL, builtin INTEGER DEFAULT 0, deleted INTEGER DEFAULT 0)",
            "CREATE TABLE routines (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, exercise_ids TEXT NOT NULL)",
            "CREATE TABLE workouts (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, name TEXT NOT NULL, start INTEGER NOT NULL, end_time INTEGER, notes TEXT, data TEXT NOT NULL)",
            "CREATE TABLE journal (date TEXT PRIMARY KEY, sleep_h REAL, sleep_q INTEGER, energy INTEGER, mood INTEGER, hunger INTEGER, stress INTEGER, steps INTEGER, notes TEXT)",
            "CREATE TABLE checkins (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, tdee INTEGER, weight REAL, kcal INTEGER, p INTEGER, c INTEGER, f INTEGER)",
            "CREATE TABLE water (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, ml INTEGER NOT NULL, time INTEGER NOT NULL)",
            "CREATE TABLE cardio (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, type TEXT NOT NULL, minutes INTEGER NOT NULL, distance_km REAL, kcal REAL, avg_hr INTEGER, notes TEXT, external_id TEXT UNIQUE)",
            "CREATE TABLE health_days (date TEXT PRIMARY KEY, steps INTEGER, active_kcal REAL, distance_m REAL, hr_avg INTEGER, hr_min INTEGER, hr_max INTEGER, resting_hr INTEGER, sleep_min INTEGER, spo2 REAL, bp_sys REAL, bp_dia REAL, weight REAL)",
            "INSERT INTO foods (id, name, kcal, p, c, f, serving_g, serving_name, builtin, sugar) VALUES (1, 'Avena en hojuelas', 389, 16.9, 66, 6.9, 40, 'media taza', 1, 1)",
            "INSERT INTO foods (id, name, kcal, p, c, f, serving_g, serving_name, builtin, sugar) VALUES (2, 'Mi licuado', 120, 10, 15, 2, 300, 'vaso', 0, 9)",
            "INSERT INTO food_entries (date, meal, food_id, name, grams, kcal, p, c, f, sugar) VALUES ('$today', 'DESAYUNO', 1, 'Avena en hojuelas', 50, 194.5, 8.45, 33, 3.45, 0.5)",
            "INSERT INTO food_entries (date, meal, food_id, name, grams, kcal, p, c, f, sugar) VALUES ('$today', 'SNACKS', 2, 'Mi licuado', 300, 360, 30, 45, 6, 27)",
            "INSERT INTO water (date, ml, time) VALUES ('$today', 500, 1)",
        )) old.execSQL(sql)
        old.version = 2
        old.close()

        data = AppData.get(app)
        val list = data.entries[today]!!
        assertEquals(10.6 * 0.5, list.first { it.name == "Avena en hojuelas" }.macros.fiber, 1e-9)
        val shake = list.first { it.name == "Mi licuado" }
        assertEquals(0.0, shake.macros.fiber, 1e-9)
        assertEquals(27.0, shake.macros.sugar, 1e-9) // el azúcar ya registrado no se toca
        assertEquals(500, data.waterTotal(today))
        assertTrue(data.foods.any { it.name == "Enchiladas verdes con pollo" })
        assertEquals(1, data.foods.count { it.name == "Avena en hojuelas" })
        assertTrue(data.foods.any { it.name == "Mi licuado" })
    }
}
