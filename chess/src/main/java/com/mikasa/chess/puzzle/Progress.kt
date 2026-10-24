package com.mikasa.chess.puzzle

import kotlin.math.pow
import kotlin.math.roundToInt

/** Almacenamiento clave-valor mínimo (SharedPreferences en la app, un mapa en los tests). */
interface KeyValueStore {
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun clear()
}

/** Rating, rachas y puzzles resueltos/fallados del usuario. */
class Progress(private val store: KeyValueStore) {

    var rating: Int
        get() = store.getInt(KEY_RATING, INITIAL_RATING)
        private set(value) = store.putInt(KEY_RATING, value)

    /** Puzzles con rating ya jugados (cada puzzle solo cuenta una vez). */
    val ratedAttempts: Int get() = store.getInt(KEY_RATED_GAMES, 0)

    var streak: Int
        get() = store.getInt(KEY_STREAK, 0)
        private set(value) = store.putInt(KEY_STREAK, value)

    var bestStreak: Int
        get() = store.getInt(KEY_BEST_STREAK, 0)
        private set(value) = store.putInt(KEY_BEST_STREAK, value)

    var bestRating: Int
        get() = store.getInt(KEY_BEST_RATING, INITIAL_RATING)
        private set(value) = store.putInt(KEY_BEST_RATING, value)

    val solved: Set<Int> get() = readSet(KEY_SOLVED)
    val failed: Set<Int> get() = readSet(KEY_FAILED)
    val rated: Set<Int> get() = readSet(KEY_RATED)

    /**
     * Registra el resultado con rating de un puzzle la primera vez que se juega.
     * Devuelve la variación de rating (0 si ese puzzle ya contaba).
     */
    fun recordRated(puzzle: Puzzle, success: Boolean): Int {
        val ratedSet = rated
        if (puzzle.id in ratedSet) return 0
        writeSet(KEY_RATED, ratedSet + puzzle.id)
        val games = ratedAttempts
        store.putInt(KEY_RATED_GAMES, games + 1)
        val delta = ratingDelta(rating, puzzle.rating, success, games)
        rating = (rating + delta).coerceAtLeast(MIN_RATING)
        if (rating > bestRating) bestRating = rating
        return delta
    }

    /** El usuario resolvió el puzzle ([clean] = sin fallos ni pistas). */
    fun recordSolved(puzzle: Puzzle, clean: Boolean) {
        if (clean) {
            writeSet(KEY_SOLVED, solved + puzzle.id)
            writeSet(KEY_FAILED, failed - puzzle.id)
            streak += 1
            if (streak > bestStreak) bestStreak = streak
        } else {
            recordFailed(puzzle)
        }
    }

    /** El usuario falló (jugada incorrecta, pista o solución). */
    fun recordFailed(puzzle: Puzzle) {
        if (puzzle.id !in solved) writeSet(KEY_FAILED, failed + puzzle.id)
        streak = 0
    }

    fun reset() = store.clear()

    private fun readSet(key: String): Set<Int> =
        store.getString(key)?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()

    private fun writeSet(key: String, set: Set<Int>) =
        store.putString(key, set.sorted().joinToString(","))

    companion object {
        const val INITIAL_RATING = 1000
        private const val MIN_RATING = 100

        private const val KEY_RATING = "rating"
        private const val KEY_BEST_RATING = "best_rating"
        private const val KEY_RATED_GAMES = "rated_games"
        private const val KEY_STREAK = "streak"
        private const val KEY_BEST_STREAK = "best_streak"
        private const val KEY_SOLVED = "solved"
        private const val KEY_FAILED = "failed"
        private const val KEY_RATED = "rated"

        /** Elo con K alto al principio para que el rating se ajuste rápido. */
        fun ratingDelta(player: Int, puzzle: Int, success: Boolean, gamesPlayed: Int): Int {
            val k = when {
                gamesPlayed < 10 -> 60.0
                gamesPlayed < 30 -> 40.0
                else -> 24.0
            }
            val expected = 1.0 / (1.0 + 10.0.pow((puzzle - player) / 400.0))
            val score = if (success) 1.0 else 0.0
            val delta = (k * (score - expected)).roundToInt()
            // Siempre mueve al menos un punto en la dirección del resultado.
            return when {
                success && delta < 1 -> 1
                !success && delta > -1 -> -1
                else -> delta
            }
        }
    }
}
