package com.mikasa.charadas

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Category(
    val id: String,
    val name: String,
    val emoji: String,
    val color: Int,
    val words: List<String>,
    val custom: Boolean = false,
)

object CategoryRepository {
    const val MIX_ID = "__mezcla__"
    private const val PREFS = "charadas"
    private const val KEY_CUSTOM = "custom_categories"

    private var builtIn: List<Category>? = null

    /** Lee todos los archivos assets/categorias*.txt; las categorías con el mismo nombre se combinan. */
    fun builtIn(ctx: Context): List<Category> {
        builtIn?.let { return it }
        class Acc(val emoji: String, val color: Int) { val words = LinkedHashMap<String, String>() }
        val cats = LinkedHashMap<String, Acc>()
        val files = (ctx.assets.list("") ?: emptyArray())
            .filter { it.startsWith("categorias") && it.endsWith(".txt") }
            .sorted()
        for (file in files) {
            var cur: Acc? = null
            ctx.assets.open(file).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    if (line.startsWith("@")) {
                        val parts = line.substring(1).split("|")
                        val name = parts[0].trim()
                        cur = cats.getOrPut(name) {
                            Acc(
                                parts.getOrElse(1) { "🎲" }.trim(),
                                runCatching { Color.parseColor(parts[2].trim()) }.getOrDefault(0xFF6A3DE8.toInt()),
                            )
                        }
                    } else {
                        val acc = cur ?: continue
                        line.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            .forEach { acc.words.putIfAbsent(key(it), it) }
                    }
                }
            }
        }
        val result = cats.filter { it.value.words.isNotEmpty() }
            .map { (name, acc) -> Category("b_$name", name, acc.emoji, acc.color, acc.words.values.toList()) }
        builtIn = result
        return result
    }

    /** Clave sin acentos ni mayúsculas, para detectar palabras repetidas. */
    fun key(s: String): String = java.text.Normalizer.normalize(s.lowercase().trim(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")

    fun custom(ctx: Context): List<Category> {
        val json = prefs(ctx).getString(KEY_CUSTOM, "[]") ?: "[]"
        val arr = runCatching { JSONArray(json) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val w = o.getJSONArray("words")
            Category(
                id = o.getString("id"),
                name = o.getString("name"),
                emoji = o.optString("emoji", "⭐"),
                color = o.optInt("color", 0xFF6A3DE8.toInt()),
                words = (0 until w.length()).map { w.getString(it) },
                custom = true,
            )
        }
    }

    fun all(ctx: Context): List<Category> = custom(ctx) + builtIn(ctx)

    fun mix(ctx: Context): Category = Category(
        MIX_ID, "Mezcla total", "🎲", 0xFF6A3DE8.toInt(),
        all(ctx).flatMap { it.words }.distinct(),
    )

    fun find(ctx: Context, id: String): Category? =
        if (id == MIX_ID) mix(ctx) else all(ctx).firstOrNull { it.id == id }

    fun saveCustom(ctx: Context, name: String, emoji: String, words: List<String>, existingId: String?) {
        val list = custom(ctx).toMutableList()
        val colors = listOf(0xFF6A3DE8, 0xFF00897B, 0xFFD81B60, 0xFFF4511E, 0xFF3949AB, 0xFF7CB342)
        val idx = list.indexOfFirst { it.id == existingId }
        val cat = Category(
            id = existingId ?: ("c_" + UUID.randomUUID()),
            name = name,
            emoji = emoji.ifBlank { "⭐" },
            color = if (idx >= 0) list[idx].color else colors[list.size % colors.size].toInt(),
            words = words,
            custom = true,
        )
        if (idx >= 0) list[idx] = cat else list.add(0, cat)
        clearHistory(ctx, cat.id)
        writeCustom(ctx, list)
    }

    fun deleteCustom(ctx: Context, id: String) {
        writeCustom(ctx, custom(ctx).filterNot { it.id == id })
        clearHistory(ctx, id)
    }

    private fun writeCustom(ctx: Context, list: List<Category>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id); put("name", c.name); put("emoji", c.emoji); put("color", c.color)
                put("words", JSONArray(c.words))
            })
        }
        prefs(ctx).edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    // ---- Sin repeticiones ----

    /** Juegos anteriores que se toman en cuenta: con el actual suman 4 juegos sin repetir. */
    const val GAMES_WITHOUT_REPEAT = 4

    /**
     * Una partida. Las palabras se sacan de una baraja barajada que se guarda en el teléfono
     * (así se recorren todas antes de volver a empezar) y nunca sale una palabra usada en los
     * últimos 3 juegos de esta categoría ni en el juego actual.
     */
    class Session internal constructor(private val ctx: Context, private val category: Category) {
        private val used = LinkedHashSet<String>()
        private var committed = false
        private val history: MutableList<List<String>> = loadHistory(ctx, category.id)
        private val deck: ArrayDeque<String> = loadDeck(ctx, category)

        private fun blocked(): Set<String> = HashSet<String>().apply {
            history.forEach { addAll(it) }
            addAll(used)
        }

        fun next(): String {
            val blocked = blocked()
            var word: String? = null
            // 1) siguiente palabra de la baraja que no esté bloqueada
            val skipped = ArrayList<String>()
            while (deck.isNotEmpty()) {
                val w = deck.removeFirst()
                if (w in blocked) skipped += w else { word = w; break }
            }
            // las bloqueadas regresan al final de la baraja para salir más adelante
            deck.addAll(skipped)
            // 2) baraja agotada: nueva baraja con todas las palabras permitidas
            if (word == null) {
                deck.clear()
                val fresh = category.words.filter { it !in blocked }.shuffled()
                if (fresh.isNotEmpty()) {
                    word = fresh.first()
                    deck.addAll(fresh.drop(1))
                }
                deck.addAll(category.words.filter { it in blocked && it !in used }.shuffled())
            }
            // 3) categoría demasiado pequeña: se repite la palabra del juego más antiguo
            if (word == null) {
                word = history.asSequence().flatten().firstOrNull { it !in used }
                    ?: category.words.random()
            }
            used += word
            saveDeck(ctx, category.id, deck)
            return word
        }

        /** Guarda las palabras de este juego en el historial (llamar al terminar). */
        fun commit() {
            if (committed || used.isEmpty()) return
            committed = true
            history += used.toList()
            while (history.size > GAMES_WITHOUT_REPEAT - 1) history.removeAt(0)
            saveHistory(ctx, category.id, history)
        }
    }

    fun startSession(ctx: Context, category: Category) = Session(ctx.applicationContext, category)

    private fun loadHistory(ctx: Context, id: String): MutableList<List<String>> {
        val arr = runCatching { JSONArray(prefs(ctx).getString("hist_$id", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { i ->
            val g = arr.getJSONArray(i); (0 until g.length()).map { g.getString(it) }
        }.toMutableList()
    }

    private fun saveHistory(ctx: Context, id: String, h: List<List<String>>) {
        val arr = JSONArray(); h.forEach { arr.put(JSONArray(it)) }
        prefs(ctx).edit().putString("hist_$id", arr.toString()).apply()
    }

    private fun loadDeck(ctx: Context, c: Category): ArrayDeque<String> {
        val valid = c.words.toHashSet()
        val arr = runCatching { JSONArray(prefs(ctx).getString("deck_${c.id}", "[]")) }.getOrDefault(JSONArray())
        return ArrayDeque((0 until arr.length()).map { arr.getString(it) }.filter { it in valid })
    }

    private fun saveDeck(ctx: Context, id: String, d: Collection<String>) {
        prefs(ctx).edit().putString("deck_$id", JSONArray(d).toString()).apply()
    }

    private fun clearHistory(ctx: Context, id: String) {
        prefs(ctx).edit().remove("hist_$id").remove("deck_$id").apply()
    }

    // ---- Ajustes ----
    val DURATIONS = intArrayOf(30, 45, 60, 90, 120)

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun duration(ctx: Context) = prefs(ctx).getInt("duration", 60)
    fun setDuration(ctx: Context, s: Int) = prefs(ctx).edit().putInt("duration", s).apply()
    fun tiltMode(ctx: Context) = prefs(ctx).getBoolean("tilt", true)
    fun setTiltMode(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("tilt", v).apply()
    fun sound(ctx: Context) = prefs(ctx).getBoolean("sound", true)
    fun setSound(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("sound", v).apply()
}
