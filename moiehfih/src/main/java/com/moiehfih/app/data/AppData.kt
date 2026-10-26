package com.moiehfih.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.moiehfih.app.logic.Calc
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Todos los datos de la app. Se guardan en SQLite y se mantienen en memoria (son pocos: incluso
 * años de registros caben de sobra), así las pantallas leen sin esperar a la base de datos.
 */
class AppData private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: AppData? = null

        fun get(context: Context): AppData =
            instance ?: synchronized(this) { instance ?: AppData(context.applicationContext).also { instance = it } }

        /** Solo para pruebas: cada prueba usa una aplicación (y base de datos) nueva. */
        fun resetForTests() {
            instance = null
        }
    }

    private val db: SQLiteDatabase = Helper(context).writableDatabase

    var profile: Profile? = null
        private set
    var goal = Goal()
        private set
    var targets: Targets? = null
        private set
    var restSeconds = 120
        private set

    val foods = ArrayList<Food>()
    val entries = HashMap<LocalDate, MutableList<FoodEntry>>()
    val weights = java.util.TreeMap<LocalDate, Double>()
    val exercises = ArrayList<Exercise>()
    val routines = ArrayList<Routine>()
    val workouts = ArrayList<Workout>()
    val journal = HashMap<LocalDate, JournalEntry>()
    val checkins = ArrayList<Checkin>()

    /** Cambia con cada modificación; sirve para invalidar cálculos derivados. */
    var version = 0
        private set

    init {
        loadAll()
    }

    // ---------- Derivados (tendencia, gasto, objetivos) ----------

    class Derived(
        val trend: List<Calc.TrendPoint>,
        val exp: List<Calc.ExpPoint>,
        val trendNow: Double?,
        val tdee: Double?,
    )

    private var derivedCache: Pair<Int, Derived>? = null
    private var derivedDay: LocalDate? = null

    fun derived(today: LocalDate = LocalDate.now()): Derived {
        derivedCache?.let { (v, d) -> if (v == version && derivedDay == today) return d }
        val trend = Calc.trendSeries(weights, today)
        val p = profile
        val exp = if (p == null) emptyList() else Calc.expenditureSeries(p, weights, intakeByDay(), today)
        val trendNow = trend.lastOrNull()?.trend
        val tdee = exp.lastOrNull()?.tdee ?: if (p != null && trendNow != null) Calc.initialTdee(p, trendNow) else null
        return Derived(trend, exp, trendNow, tdee).also {
            derivedCache = version to it
            derivedDay = today
        }
    }

    fun intakeByDay(): Map<LocalDate, Double> =
        entries.filterValues { it.isNotEmpty() }.mapValues { (_, list) -> list.sumOf { it.macros.kcal } }

    fun dayTotals(date: LocalDate): Macros = entries[date].orEmpty().fold(Macros.ZERO) { a, e -> a + e.macros }

    /** Objetivos vigentes (o unos genéricos si aún no hay perfil). */
    fun currentTargets(): MacroTargets = targets?.macros ?: MacroTargets(2000, 150, 200, 65)

    fun proposedTargets(today: LocalDate = LocalDate.now()): MacroTargets? {
        val p = profile ?: return null
        val d = derived(today)
        if (d.tdee == null || d.trendNow == null) return null
        return Calc.computeTargets(d.tdee, d.trendNow, goal, p.sex)
    }

    fun checkinDue(today: LocalDate = LocalDate.now()): Boolean {
        val t = targets ?: return true
        return Calc.days(t.date, today) >= 7
    }

    /** Check-in semanal: guarda el registro y, en modo coach, actualiza los objetivos. */
    fun doCheckin(today: LocalDate = LocalDate.now()): MacroTargets? {
        val d = derived(today)
        val next = proposedTargets(today) ?: return null
        val tdee = d.tdee!!.toInt()
        val c = Checkin(today, tdee, d.trendNow!!, next)
        checkins += c
        db.insert("checkins", null, ContentValues().apply {
            put("date", today.toString()); put("tdee", tdee); put("weight", c.weight)
            put("kcal", next.kcal); put("p", next.p); put("c", next.c); put("f", next.f)
        })
        val prev = targets
        setTargets(if (prev?.manual == true) prev.copy(date = today) else Targets(today, next, tdee, false))
        return next
    }

    /** Recalcula los objetivos ahora (al guardar el perfil o pasar a modo coach). */
    fun recalcTargets(today: LocalDate = LocalDate.now()) {
        val next = proposedTargets(today) ?: return
        setTargets(Targets(today, next, derived(today).tdee?.toInt(), false))
    }

    // ---------- Perfil y ajustes ----------

    fun saveProfile(p: Profile, g: Goal) {
        profile = p
        goal = g
        putKv("profile", JSONObject().apply {
            put("name", p.name); put("sex", p.sex.name); put("age", p.age)
            put("heightCm", p.heightCm); put("activity", p.activity.name)
        })
        putKv("goal", JSONObject().apply {
            put("ratePct", g.ratePct); put("proteinGPerKg", g.proteinGPerKg); put("fatPct", g.fatPct)
        })
        changed()
    }

    fun setTargets(t: Targets) {
        targets = t
        putKv("targets", JSONObject().apply {
            put("date", t.date.toString()); put("kcal", t.macros.kcal); put("p", t.macros.p)
            put("c", t.macros.c); put("f", t.macros.f); put("tdee", t.tdee ?: JSONObject.NULL); put("manual", t.manual)
        })
        changed()
    }

    fun setRestSeconds(s: Int) {
        restSeconds = s
        putKv("restSeconds", JSONObject().put("v", s))
    }

    private fun putKv(key: String, value: JSONObject) {
        db.insertWithOnConflict("kv", null, ContentValues().apply {
            put("key", key); put("value", value.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------- Comida ----------

    fun food(id: Long?): Food? = foods.firstOrNull { it.id == id }

    fun addFood(f: Food): Food {
        val id = db.insert("foods", null, foodValues(f))
        return f.copy(id = id).also { foods += it; changed() }
    }

    fun deleteFood(id: Long) {
        db.update("foods", ContentValues().apply { put("deleted", 1) }, "id=?", arrayOf("$id"))
        foods.removeAll { it.id == id }
        changed()
    }

    fun saveEntry(e: FoodEntry): FoodEntry {
        val values = ContentValues().apply {
            put("date", e.date.toString()); put("meal", e.meal.name); put("food_id", e.foodId); put("name", e.name)
            put("grams", e.grams); put("kcal", e.macros.kcal); put("p", e.macros.p); put("c", e.macros.c); put("f", e.macros.f)
        }
        val saved = if (e.id > 0) {
            db.update("food_entries", values, "id=?", arrayOf("${e.id}"))
            e
        } else {
            e.copy(id = db.insert("food_entries", null, values))
        }
        entries.values.forEach { list -> list.removeAll { it.id == saved.id } }
        entries.getOrPut(saved.date) { ArrayList() } += saved
        changed()
        return saved
    }

    fun deleteEntry(e: FoodEntry) {
        db.delete("food_entries", "id=?", arrayOf("${e.id}"))
        entries[e.date]?.removeAll { it.id == e.id }
        if (entries[e.date]?.isEmpty() == true) entries.remove(e.date)
        changed()
    }

    fun clearDay(date: LocalDate) {
        db.delete("food_entries", "date=?", arrayOf(date.toString()))
        entries.remove(date)
        changed()
    }

    /** Alimentos más usados en los últimos 30 días registrados (id → veces). */
    fun recentFoodUse(): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        entries.keys.sortedDescending().take(30).forEach { d ->
            entries[d]?.forEach { e -> e.foodId?.let { out[it] = (out[it] ?: 0) + 1 } }
        }
        return out
    }

    // ---------- Peso ----------

    fun setWeight(date: LocalDate, kg: Double) {
        db.insertWithOnConflict("weights", null, ContentValues().apply {
            put("date", date.toString()); put("kg", kg)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        weights[date] = kg
        changed()
    }

    fun deleteWeight(date: LocalDate) {
        db.delete("weights", "date=?", arrayOf(date.toString()))
        weights.remove(date)
        changed()
    }

    // ---------- Ejercicios, rutinas y entrenos ----------

    fun exercise(id: Long): Exercise? = exercises.firstOrNull { it.id == id }

    fun saveExercise(e: Exercise): Exercise {
        val values = ContentValues().apply {
            put("name", e.name); put("muscle", e.muscle); put("rep_min", e.repMin); put("rep_max", e.repMax)
            put("increment", e.increment); put("builtin", if (e.builtin) 1 else 0)
        }
        val saved = if (e.id > 0) {
            db.update("exercises", values, "id=?", arrayOf("${e.id}")); e
        } else {
            e.copy(id = db.insert("exercises", null, values))
        }
        val i = exercises.indexOfFirst { it.id == saved.id }
        if (i >= 0) exercises[i] = saved else exercises += saved
        changed()
        return saved
    }

    fun deleteExercise(id: Long) {
        db.update("exercises", ContentValues().apply { put("deleted", 1) }, "id=?", arrayOf("$id"))
        exercises.removeAll { it.id == id }
        routines.toList().forEach { r -> if (id in r.exerciseIds) saveRoutine(r.copy(exerciseIds = r.exerciseIds - id)) }
        changed()
    }

    fun saveRoutine(r: Routine): Routine {
        val values = ContentValues().apply { put("name", r.name); put("exercise_ids", r.exerciseIds.joinToString(",")) }
        val saved = if (r.id > 0) {
            db.update("routines", values, "id=?", arrayOf("${r.id}")); r
        } else {
            r.copy(id = db.insert("routines", null, values))
        }
        val i = routines.indexOfFirst { it.id == saved.id }
        if (i >= 0) routines[i] = saved else routines += saved
        changed()
        return saved
    }

    fun deleteRoutine(id: Long) {
        db.delete("routines", "id=?", arrayOf("$id"))
        routines.removeAll { it.id == id }
        changed()
    }

    fun activeWorkout(): Workout? = workouts.firstOrNull { it.end == null }

    fun finishedWorkouts(): List<Workout> = workouts.filter { it.end != null }

    fun saveWorkout(w: Workout): Workout {
        val values = ContentValues().apply {
            put("date", w.date.toString()); put("name", w.name); put("start", w.start); put("end_time", w.end)
            put("notes", w.notes); put("data", exercisesToJson(w.exercises).toString())
        }
        val saved = if (w.id > 0) {
            db.update("workouts", values, "id=?", arrayOf("${w.id}")); w
        } else {
            w.copy(id = db.insert("workouts", null, values))
        }
        val i = workouts.indexOfFirst { it.id == saved.id }
        if (i >= 0) workouts[i] = saved else workouts += saved
        changed()
        return saved
    }

    fun deleteWorkout(id: Long) {
        db.delete("workouts", "id=?", arrayOf("$id"))
        workouts.removeAll { it.id == id }
        changed()
    }

    // ---------- Bitácora ----------

    fun saveJournal(j: JournalEntry) {
        if (j.isEmpty) {
            db.delete("journal", "date=?", arrayOf(j.date.toString()))
            journal.remove(j.date)
        } else {
            db.insertWithOnConflict("journal", null, ContentValues().apply {
                put("date", j.date.toString()); put("sleep_h", j.sleepH); put("sleep_q", j.sleepQ); put("energy", j.energy)
                put("mood", j.mood); put("hunger", j.hunger); put("stress", j.stress); put("steps", j.steps); put("notes", j.notes)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            journal[j.date] = j
        }
        changed()
    }

    // ---------- Copia de seguridad ----------

    fun exportJson(): String {
        val root = JSONObject().put("app", "MoiehFih").put("version", 1)
        for (table in Helper.TABLES) {
            val rows = JSONArray()
            db.rawQuery("SELECT * FROM $table", null).use { c ->
                while (c.moveToNext()) {
                    val row = JSONObject()
                    for (i in 0 until c.columnCount) {
                        row.put(c.getColumnName(i), when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            else -> c.getString(i)
                        })
                    }
                    rows.put(row)
                }
            }
            root.put(table, rows)
        }
        return root.toString(1)
    }

    /** Reemplaza todos los datos por los de una copia. Lanza excepción si el archivo no es válido. */
    fun importJson(text: String) {
        val root = JSONObject(text)
        require(root.optString("app") == "MoiehFih") { "El archivo no es una copia de seguridad de MoiehFih." }
        db.beginTransaction()
        try {
            for (table in Helper.TABLES) {
                db.delete(table, null, null)
                val rows = root.optJSONArray(table) ?: continue
                for (r in 0 until rows.length()) {
                    val row = rows.getJSONObject(r)
                    val values = ContentValues()
                    for (key in row.keys()) {
                        when (val v = row.get(key)) {
                            JSONObject.NULL -> values.putNull(key)
                            is Int -> values.put(key, v.toLong())
                            is Long -> values.put(key, v)
                            is Double -> values.put(key, v)
                            is Boolean -> values.put(key, if (v) 1 else 0)
                            else -> values.put(key, v.toString())
                        }
                    }
                    db.insertOrThrow(table, null, values)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        loadAll()
    }

    /** Borra todo y deja la app como recién instalada. */
    fun resetAll() {
        db.beginTransaction()
        try {
            Helper.TABLES.forEach { db.delete(it, null, null) }
            Helper.seed(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        loadAll()
    }

    /** Ejecuta varias escrituras en una transacción (mucho más rápido para datos de demostración). */
    fun <T> batch(block: () -> T): T {
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    // ---------- Carga ----------

    private fun changed() {
        version++
    }

    private fun loadAll() {
        profile = null; goal = Goal(); targets = null; restSeconds = 120
        foods.clear(); entries.clear(); weights.clear(); exercises.clear()
        routines.clear(); workouts.clear(); journal.clear(); checkins.clear()

        query("SELECT key, value FROM kv") { c ->
            val o = JSONObject(c.getString(1))
            when (c.getString(0)) {
                "profile" -> profile = Profile(
                    o.optString("name"), Sex.valueOf(o.getString("sex")), o.getInt("age"),
                    o.getDouble("heightCm"), ActivityLevel.valueOf(o.getString("activity")),
                )
                "goal" -> goal = Goal(o.getDouble("ratePct"), o.getDouble("proteinGPerKg"), o.getDouble("fatPct"))
                "targets" -> targets = Targets(
                    LocalDate.parse(o.getString("date")),
                    MacroTargets(o.getInt("kcal"), o.getInt("p"), o.getInt("c"), o.getInt("f")),
                    if (o.isNull("tdee")) null else o.getInt("tdee"),
                    o.getBoolean("manual"),
                )
                "restSeconds" -> restSeconds = o.getInt("v")
            }
        }
        query("SELECT id, name, kcal, p, c, f, serving_g, serving_name, builtin FROM foods WHERE deleted=0 ORDER BY name") { c ->
            foods += Food(
                c.getLong(0), c.getString(1), Macros(c.getDouble(2), c.getDouble(3), c.getDouble(4), c.getDouble(5)),
                c.getDouble(6), c.getString(7), c.getInt(8) == 1,
            )
        }
        query("SELECT id, date, meal, food_id, name, grams, kcal, p, c, f FROM food_entries ORDER BY id") { c ->
            val e = FoodEntry(
                c.getLong(0), LocalDate.parse(c.getString(1)), Meal.valueOf(c.getString(2)),
                if (c.isNull(3)) null else c.getLong(3), c.getString(4), if (c.isNull(5)) null else c.getDouble(5),
                Macros(c.getDouble(6), c.getDouble(7), c.getDouble(8), c.getDouble(9)),
            )
            entries.getOrPut(e.date) { ArrayList() } += e
        }
        query("SELECT date, kg FROM weights") { c -> weights[LocalDate.parse(c.getString(0))] = c.getDouble(1) }
        query("SELECT id, name, muscle, rep_min, rep_max, increment, builtin FROM exercises WHERE deleted=0 ORDER BY name") { c ->
            exercises += Exercise(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4), c.getDouble(5), c.getInt(6) == 1)
        }
        query("SELECT id, name, exercise_ids FROM routines ORDER BY id") { c ->
            val ids = c.getString(2).split(',').mapNotNull { it.trim().toLongOrNull() }
            routines += Routine(c.getLong(0), c.getString(1), ids)
        }
        query("SELECT id, date, name, start, end_time, notes, data FROM workouts ORDER BY date, start") { c ->
            workouts += Workout(
                c.getLong(0), LocalDate.parse(c.getString(1)), c.getString(2), c.getLong(3),
                if (c.isNull(4)) null else c.getLong(4), c.getString(5), exercisesFromJson(JSONArray(c.getString(6))),
            )
        }
        query("SELECT date, sleep_h, sleep_q, energy, mood, hunger, stress, steps, notes FROM journal") { c ->
            fun int(i: Int) = if (c.isNull(i)) null else c.getInt(i)
            val d = LocalDate.parse(c.getString(0))
            journal[d] = JournalEntry(
                d, if (c.isNull(1)) null else c.getDouble(1), int(2), int(3), int(4), int(5), int(6), int(7), c.getString(8) ?: "",
            )
        }
        query("SELECT date, tdee, weight, kcal, p, c, f FROM checkins ORDER BY date") { c ->
            checkins += Checkin(
                LocalDate.parse(c.getString(0)), c.getInt(1), c.getDouble(2),
                MacroTargets(c.getInt(3), c.getInt(4), c.getInt(5), c.getInt(6)),
            )
        }
        changed()
    }

    private inline fun query(sql: String, row: (Cursor) -> Unit) {
        db.rawQuery(sql, null).use { c -> while (c.moveToNext()) row(c) }
    }

    private fun exercisesToJson(list: List<WorkoutExercise>) = JSONArray().apply {
        for (ex in list) {
            put(JSONObject().apply {
                put("e", ex.exerciseId)
                put("s", JSONArray().apply {
                    for (s in ex.sets) put(JSONObject().apply {
                        put("w", s.weight ?: JSONObject.NULL); put("r", s.reps ?: JSONObject.NULL)
                        put("rpe", s.rpe ?: JSONObject.NULL); put("d", s.done)
                    })
                })
            })
        }
    }

    private fun exercisesFromJson(a: JSONArray): MutableList<WorkoutExercise> = MutableList(a.length()) { i ->
        val o = a.getJSONObject(i)
        val sets = o.getJSONArray("s")
        WorkoutExercise(o.getLong("e"), MutableList(sets.length()) { j ->
            val s = sets.getJSONObject(j)
            WorkoutSet(
                if (s.isNull("w")) null else s.getDouble("w"),
                if (s.isNull("r")) null else s.getInt("r"),
                if (s.isNull("rpe")) null else s.getDouble("rpe"),
                s.optBoolean("d"),
            )
        })
    }

    private fun foodValues(f: Food) = ContentValues().apply {
        put("name", f.name); put("kcal", f.per100.kcal); put("p", f.per100.p); put("c", f.per100.c); put("f", f.per100.f)
        put("serving_g", f.servingG); put("serving_name", f.servingName); put("builtin", if (f.builtin) 1 else 0)
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "moiehfih.db", null, 1) {
        companion object {
            val TABLES = listOf(
                "kv", "foods", "food_entries", "weights", "exercises", "routines", "workouts", "journal", "checkins",
            )

            fun seed(db: SQLiteDatabase) {
                val foodValues = { f: Food ->
                    ContentValues().apply {
                        put("name", f.name); put("kcal", f.per100.kcal); put("p", f.per100.p); put("c", f.per100.c)
                        put("f", f.per100.f); put("serving_g", f.servingG); put("serving_name", f.servingName); put("builtin", 1)
                    }
                }
                Seed.foods.forEach { db.insert("foods", null, foodValues(it)) }
                val ids = HashMap<String, Long>()
                for (e in Seed.exercises) {
                    ids[e.name] = db.insert("exercises", null, ContentValues().apply {
                        put("name", e.name); put("muscle", e.muscle); put("rep_min", e.repMin); put("rep_max", e.repMax)
                        put("increment", e.increment); put("builtin", 1)
                    })
                }
                for ((name, exNames) in Seed.routines) {
                    db.insert("routines", null, ContentValues().apply {
                        put("name", name); put("exercise_ids", exNames.mapNotNull { ids[it] }.joinToString(","))
                    })
                }
            }
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL(
                "CREATE TABLE foods (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, kcal REAL, p REAL, c REAL, f REAL, " +
                    "serving_g REAL, serving_name TEXT, builtin INTEGER DEFAULT 0, deleted INTEGER DEFAULT 0)",
            )
            db.execSQL(
                "CREATE TABLE food_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, meal TEXT NOT NULL, " +
                    "food_id INTEGER, name TEXT NOT NULL, grams REAL, kcal REAL, p REAL, c REAL, f REAL)",
            )
            db.execSQL("CREATE INDEX idx_entries_date ON food_entries(date)")
            db.execSQL("CREATE TABLE weights (date TEXT PRIMARY KEY, kg REAL NOT NULL)")
            db.execSQL(
                "CREATE TABLE exercises (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, muscle TEXT, rep_min INTEGER, " +
                    "rep_max INTEGER, increment REAL, builtin INTEGER DEFAULT 0, deleted INTEGER DEFAULT 0)",
            )
            db.execSQL("CREATE TABLE routines (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, exercise_ids TEXT NOT NULL)")
            db.execSQL(
                "CREATE TABLE workouts (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, name TEXT NOT NULL, " +
                    "start INTEGER NOT NULL, end_time INTEGER, notes TEXT, data TEXT NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE journal (date TEXT PRIMARY KEY, sleep_h REAL, sleep_q INTEGER, energy INTEGER, mood INTEGER, " +
                    "hunger INTEGER, stress INTEGER, steps INTEGER, notes TEXT)",
            )
            db.execSQL(
                "CREATE TABLE checkins (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, tdee INTEGER, weight REAL, " +
                    "kcal INTEGER, p INTEGER, c INTEGER, f INTEGER)",
            )
            seed(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
