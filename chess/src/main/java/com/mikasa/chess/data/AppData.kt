package com.mikasa.chess.data

import android.content.Context
import com.mikasa.chess.puzzle.KeyValueStore
import com.mikasa.chess.puzzle.Progress
import com.mikasa.chess.puzzle.Puzzle

/** Puzzles incluidos en la app (assets/puzzles.tsv), cargados una sola vez. */
object PuzzleRepository {
    @Volatile
    private var cache: List<Puzzle>? = null

    fun all(context: Context): List<Puzzle> =
        cache ?: synchronized(this) {
            cache ?: context.applicationContext.assets.open("puzzles.tsv")
                .use(Puzzle::parseTsv)
                .also { cache = it }
        }

    fun byId(context: Context, id: Int): Puzzle? = all(context).firstOrNull { it.id == id }
}

/** [KeyValueStore] respaldado por SharedPreferences. */
class PrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences("progress", Context.MODE_PRIVATE)

    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun clear() = prefs.edit().clear().apply()
}

fun Context.progress(): Progress = Progress(PrefsStore(this))
