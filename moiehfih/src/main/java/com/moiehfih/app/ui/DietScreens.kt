package com.moiehfih.app.ui

import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.moiehfih.app.data.Food
import com.moiehfih.app.data.FoodEntry
import com.moiehfih.app.data.FoodUnit
import com.moiehfih.app.data.Macros
import com.moiehfih.app.data.Meal
import java.text.Normalizer
import java.time.LocalDate

class DietScreen(act: MainActivity) : Screen(act) {
    override val title = "Dieta"
    private var date = LocalDate.now()

    override fun build(root: LinearLayout) {
        root.dateNav(act, date) { date = it; act.refresh() }
        root.dayCard(act, date, "Resumen del día")
        root.waterCard(act, date)
        val entries = data.entries[date].orEmpty()
        for (meal in Meal.values()) {
            val items = entries.filter { it.meal == meal }
            val total = items.fold(Macros.ZERO) { a, e -> a + e.macros }
            root.card {
                row {
                    h3(meal.label).weighted()
                    muted("${Fmt.n(total.kcal)} kcal")
                }.margins(bottom = 4)
                for (e in items) {
                    listRow(
                        e.name,
                        entryMeta(e),
                        Fmt.n(e.macros.kcal),
                        onDelete = { data.deleteEntry(e); act.refresh() },
                        onClick = { editEntry(act, e) },
                    )
                }
                button("+ Añadir alimento", Btn.GHOST) { act.push(FoodSearchScreen(act, date, meal)) }.margins(top = 4)
            }
        }
        root.row {
            button("Copiar día anterior") { copyPrevious() }.weighted()
            if (entries.isNotEmpty()) {
                add(View(context), width = dp(10))
                button("Vaciar día", Btn.DANGER) {
                    act.confirm("¿Borrar todo lo registrado este día?", "Borrar") { data.clearDay(date); act.refresh() }
                }.weighted()
            }
        }.margins(bottom = 12)

        val week = (0L until 7L).mapNotNull { i -> date.minusDays(i).takeIf { data.entries[it]?.isNotEmpty() == true } }
            .map { data.dayTotals(it) }
        if (week.isNotEmpty()) {
            val tg = data.currentTargets()
            fun avg(f: (Macros) -> Double) = week.sumOf(f) / week.size
            root.card {
                row {
                    h3("Promedio últimos 7 días").weighted()
                    muted("${week.size} días registrados", 12f)
                }.margins(bottom = 10)
                row {
                    stat(Fmt.n(avg { it.kcal }), "kcal (${Fmt.signed(avg { it.kcal } - tg.kcal, 0)})", size = 18f).weighted()
                    stat(Fmt.n(avg { it.p }), "Proteína", size = 18f).weighted()
                    stat(Fmt.n(avg { it.c }), "Carbos", size = 18f).weighted()
                    stat(Fmt.n(avg { it.f }), "Grasa", size = 18f).weighted()
                    stat(Fmt.n(avg { it.sugar }), "Azúcares", size = 18f).weighted()
                }
            }
        }
    }

    private fun copyPrevious() {
        val prev = data.entries[date.minusDays(1)].orEmpty()
        if (prev.isEmpty()) return act.toast("El día anterior no tiene registros.")
        data.batch { prev.forEach { data.saveEntry(it.copy(id = 0, date = date)) } }
        act.toast("Copiadas ${prev.size} entradas")
        act.refresh()
    }
}

/** Cantidad y macros de una entrada, p. ej. «250 ml · P 8 · C 12 · G 0 · Az 12». */
fun entryMeta(e: FoodEntry): String =
    (e.grams?.let { "${Fmt.n(it)} ${e.unit.symbol} · " } ?: "") +
        "P ${Fmt.n(e.macros.p)} · C ${Fmt.n(e.macros.c)} · G ${Fmt.n(e.macros.f)} · Az ${Fmt.n(e.macros.sugar)}"

private fun normalize(s: String) =
    Normalizer.normalize(s.lowercase(Fmt.locale), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

/** Abre la edición adecuada para una entrada existente. */
fun editEntry(act: MainActivity, e: FoodEntry) {
    val food = act.data.food(e.foodId)
    if (food != null && e.grams != null) quantityDialog(act, food, e.date, e.meal, e) else quickDialog(act, e.date, e.meal, e)
}

class FoodSearchScreen(act: MainActivity, private val date: LocalDate, private val meal: Meal) : Screen(act) {
    override val title = "Añadir a ${meal.label}"
    private var query = ""

    override fun build(root: LinearLayout) {
        root.row {
            button("+ Nuevo alimento") { act.push(NewFoodScreen(act, date, meal)) }.weighted()
            add(View(context), width = dp(10))
            button("Entrada rápida") { quickDialog(act, date, meal, null) { act.pop() } }.weighted()
        }.margins(bottom = 12)
        val search = root.add(act.input(query, "Buscar alimento…"))
        search.margins(bottom = 10)
        val results = root.add(act.vertical())
        fun fill() {
            results.removeAllViews()
            val recent = data.recentFoodUse()
            val q = normalize(query.trim())
            val list = data.foods.filter { q.isEmpty() || normalize(it.name).contains(q) }
                .sortedWith(compareByDescending<Food> { recent[it.id] ?: 0 }.thenBy { normalize(it.name) })
            if (list.isEmpty()) {
                results.muted("Sin resultados. Créalo con «Nuevo alimento».")
                return
            }
            results.card {
                for (f in list.take(80)) {
                    listRow(
                        f.name + if (recent.containsKey(f.id)) "  ·  reciente" else "",
                        "100 ${f.unit.symbol}: ${Fmt.n(f.per100.kcal)} kcal · P ${Fmt.n(f.per100.p, 1)} · C ${Fmt.n(f.per100.c, 1)} · " +
                            "G ${Fmt.n(f.per100.f, 1)} · Az ${Fmt.n(f.per100.sugar, 1)}",
                        onDelete = if (f.builtin) null else ({
                            act.confirm("¿Eliminar «${f.name}» de tu lista? Los registros ya hechos se conservan.", "Eliminar") {
                                data.deleteFood(f.id); fill()
                            }
                        }),
                        onClick = { quantityDialog(act, f, date, meal, null) { act.pop() } },
                    )
                }
            }
        }
        search.onChange { query = it; fill() }
        fill()
    }
}

class NewFoodScreen(act: MainActivity, private val date: LocalDate, private val meal: Meal) : Screen(act) {
    override val title = "Nuevo alimento"

    override fun build(root: LinearLayout) {
        root.card {
            val name = field("Nombre")
            text("Tipo", 13f, Pal.muted, bold = true).margins(bottom = 4)
            var unit = FoodUnit.G
            segmented(listOf("Sólido (g)", "Líquido (ml)"), 0) { unit = if (it == 1) FoodUnit.ML else FoodUnit.G }
            val r1 = row {}
            val servingName = r1.add(context.vertical(), width = 0, weight = 1f).field("Porción (nombre)", hint = "pieza, taza…")
            r1.add(View(context), width = dp(10))
            val servingG = r1.add(context.vertical(), width = 0, weight = 1f).field("Porción (g o ml)", "100", InputKind.DECIMAL)
            text("Los valores nutricionales son por", 13f, Pal.muted, bold = true).margins(bottom = 4)
            var perServing = false
            segmented(listOf("100 g / 100 ml", "Porción"), 0) { perServing = it == 1 }
            val r2 = row {}
            val p = r2.add(context.vertical(), width = 0, weight = 1f).field("Proteína (g)", kind = InputKind.DECIMAL)
            r2.add(View(context), width = dp(10))
            val c = r2.add(context.vertical(), width = 0, weight = 1f).field("Carbos (g)", kind = InputKind.DECIMAL)
            val r3 = row {}
            val f = r3.add(context.vertical(), width = 0, weight = 1f).field("Grasa (g)", kind = InputKind.DECIMAL)
            r3.add(View(context), width = dp(10))
            val sugar = r3.add(context.vertical(), width = 0, weight = 1f).field("Azúcares (g)", kind = InputKind.DECIMAL)
            val kcal = field("Calorías", hint = "auto (4·P + 4·C + 9·G)", kind = InputKind.DECIMAL)
            button("Guardar y añadir", Btn.PRIMARY) {
                val n = name.text.toString().trim()
                if (n.isEmpty()) return@button act.toast("Ponle un nombre.")
                val pv = Fmt.parse(p.text) ?: 0.0
                val cv = Fmt.parse(c.text) ?: 0.0
                val fv = Fmt.parse(f.text) ?: 0.0
                val sv = Fmt.parse(sugar.text) ?: 0.0
                if (sv > cv && cv > 0) return@button act.toast("Los azúcares no pueden ser más que los carbohidratos.")
                val kv = Fmt.parse(kcal.text) ?: (pv * 4 + cv * 4 + fv * 9)
                val g = Fmt.parse(servingG.text)
                if (perServing && (g == null || g <= 0)) return@button act.toast("Indica el tamaño de la porción.")
                val k = if (perServing) 100.0 / g!! else 1.0
                val food = data.addFood(
                    Food(
                        0, n, Macros(kv * k, pv * k, cv * k, fv * k, sv * k), g ?: 100.0,
                        servingName.text.toString().trim().ifEmpty { "porción" }, false, unit,
                    ),
                )
                act.pop()
                quantityDialog(act, food, date, meal, null) { act.pop() }
            }.margins(top = 6)
        }
    }
}

/** Diálogo de cantidad (gramos o porciones) con vista previa de macros. */
fun quantityDialog(act: MainActivity, food: Food, date: LocalDate, meal: Meal, entry: FoodEntry?, onDone: () -> Unit = {}) {
    val hasServing = food.servingG > 0 && food.servingG != 100.0
    var unitServing = entry == null && hasServing
    lateinit var amount: android.widget.EditText
    lateinit var preview: TextView
    lateinit var mealSpinner: Spinner
    fun grams(): Double = (Fmt.parse(amount.text) ?: 0.0) * if (unitServing) food.servingG else 1.0
    fun updatePreview() {
        val m = food.forGrams(grams())
        preview.text = "${Fmt.n(m.kcal)} kcal  ·  P ${Fmt.n(m.p, 1)}  ·  C ${Fmt.n(m.c, 1)}  ·  G ${Fmt.n(m.f, 1)}  ·  Az ${Fmt.n(m.sugar, 1)}"
    }
    act.formDialog(food.name, if (entry == null) "Añadir" else "Guardar", build = {
        val initial = if (unitServing) "1" else Fmt.plain(entry?.grams ?: food.servingG)
        amount = field("Cantidad", initial, InputKind.DECIMAL, hint = food.unit.symbol)
        if (hasServing) {
            val unitName = if (food.unit == FoodUnit.ML) "mililitros" else "gramos"
            val units = listOf(unitName, "${food.servingName} (${Fmt.n(food.servingG)} ${food.unit.symbol})")
            segmented(units, if (unitServing) 1 else 0) { i ->
                // Convierte la cantidad para que el total no cambie al cambiar de unidad.
                val g = grams()
                unitServing = i == 1
                amount.setText(if (unitServing) Fmt.plain(g / food.servingG, 2) else Fmt.plain(g, 0))
                updatePreview()
            }
        }
        text("Comida", 13f, Pal.muted, bold = true).margins(bottom = 4)
        mealSpinner = add(Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, Meal.values().map { it.label })
            setSelection((entry?.meal ?: meal).ordinal)
        })
        mealSpinner.margins(bottom = 10)
        preview = text("", 15f, Pal.accent, bold = true)
        amount.onChange { updatePreview() }
        updatePreview()
    }) {
        val g = grams()
        if (g <= 0) return@formDialog "Indica una cantidad."
        act.data.saveEntry(
            FoodEntry(entry?.id ?: 0, date, Meal.values()[mealSpinner.selectedItemPosition], food.id, food.name, g, food.forGrams(g), food.unit),
        )
        onDone()
        act.refresh()
        null
    }
}

/** Entrada rápida de calorías/macros sin alimento guardado (o edición de una). */
fun quickDialog(act: MainActivity, date: LocalDate, meal: Meal, entry: FoodEntry?, onDone: () -> Unit = {}) {
    lateinit var name: android.widget.EditText
    lateinit var p: android.widget.EditText
    lateinit var c: android.widget.EditText
    lateinit var f: android.widget.EditText
    lateinit var sugar: android.widget.EditText
    lateinit var kcal: android.widget.EditText
    lateinit var mealSpinner: Spinner
    act.formDialog(if (entry == null) "Entrada rápida" else "Editar entrada", if (entry == null) "Añadir" else "Guardar", build = {
        name = field("Descripción", entry?.name ?: "", hint = "Comida rápida")
        p = field("Proteína (g)", Fmt.plain(entry?.macros?.p), InputKind.DECIMAL)
        c = field("Carbos (g)", Fmt.plain(entry?.macros?.c), InputKind.DECIMAL)
        f = field("Grasa (g)", Fmt.plain(entry?.macros?.f), InputKind.DECIMAL)
        sugar = field("Azúcares (g)", Fmt.plain(entry?.macros?.sugar?.takeIf { it > 0 }), InputKind.DECIMAL)
        kcal = field("Calorías", entry?.macros?.kcal?.let { Fmt.plain(it, 0) } ?: "", InputKind.DECIMAL, hint = "auto")
        text("Comida", 13f, Pal.muted, bold = true).margins(bottom = 4)
        mealSpinner = add(Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, Meal.values().map { it.label })
            setSelection((entry?.meal ?: meal).ordinal)
        })
    }) {
        val pv = Fmt.parse(p.text) ?: 0.0
        val cv = Fmt.parse(c.text) ?: 0.0
        val fv = Fmt.parse(f.text) ?: 0.0
        val kv = Fmt.parse(kcal.text) ?: (pv * 4 + cv * 4 + fv * 9)
        if (kv <= 0) return@formDialog "Indica calorías o macros."
        act.data.saveEntry(
            FoodEntry(
                entry?.id ?: 0, date, Meal.values()[mealSpinner.selectedItemPosition], null,
                name.text.toString().trim().ifEmpty { "Entrada rápida" }, null, Macros(kv, pv, cv, fv, Fmt.parse(sugar.text) ?: 0.0),
            ),
        )
        onDone()
        act.refresh()
        null
    }
}
