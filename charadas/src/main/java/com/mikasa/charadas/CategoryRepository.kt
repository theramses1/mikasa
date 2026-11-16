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

    /** Palabras pendientes por categoría, para no repetir hasta agotar la lista. */
    private val pools = HashMap<String, ArrayDeque<String>>()

    fun builtIn(ctx: Context): List<Category> {
        builtIn?.let { return it }
        val result = mutableListOf<Category>()
        var name = ""
        var emoji = ""
        var color = 0
        var words = LinkedHashSet<String>()
        fun flush() {
            if (name.isNotEmpty() && words.isNotEmpty()) {
                result += Category("b_$name", name, emoji, color, words.toList())
            }
        }
        ctx.assets.open("categorias.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.startsWith("@")) {
                    flush()
                    val parts = line.substring(1).split("|")
                    name = parts[0].trim()
                    emoji = parts.getOrElse(1) { "🎲" }.trim()
                    color = runCatching { Color.parseColor(parts[2].trim()) }.getOrDefault(0xFF6A3DE8.toInt())
                    words = LinkedHashSet()
                } else {
                    line.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { words += it }
                }
            }
        }
        flush()
        builtIn = result
        return result
    }

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
        pools.remove(cat.id)
        writeCustom(ctx, list)
    }

    fun deleteCustom(ctx: Context, id: String) {
        writeCustom(ctx, custom(ctx).filterNot { it.id == id })
        pools.remove(id)
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

    fun nextWord(category: Category): String {
        val pool = pools.getOrPut(category.id) { ArrayDeque() }
        if (pool.isEmpty()) pool.addAll(category.words.shuffled())
        return pool.removeFirst()
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
