package com.mikasa.chess.puzzle

import kotlin.math.abs
import kotlin.random.Random

/** Modos de juego y cómo eligen el siguiente puzzle. */
enum class PuzzleMode(val rated: Boolean) {
    /** Puzzles con rating, adaptados al nivel del usuario. */
    RATED(true),
    MATE_1(false),
    MATE_2(false),
    MATE_3(false),
    /** Mate en 4 o más. */
    MATE_4(false),
    /** Posiciones de partidas célebres. */
    FAMOUS(false),
    /** Puzzles fallados que aún no se han resuelto limpiamente. */
    REVIEW(false);

    fun pool(all: List<Puzzle>, progress: Progress): List<Puzzle> = when (this) {
        RATED -> all
        MATE_1 -> all.filter { it.mateIn == 1 }
        MATE_2 -> all.filter { it.mateIn == 2 }
        MATE_3 -> all.filter { it.mateIn == 3 }
        MATE_4 -> all.filter { it.mateIn >= 4 }
        FAMOUS -> all.filter { it.isFamous }
        REVIEW -> progress.failed.let { failed -> all.filter { it.id in failed } }
    }.sortedBy { it.rating }

    /**
     * Elige el siguiente puzzle (distinto de [currentId] si es posible), o null
     * si el modo no tiene puzzles.
     */
    fun next(all: List<Puzzle>, progress: Progress, currentId: Int?, random: Random = Random.Default): Puzzle? {
        val pool = pool(all, progress)
        if (pool.isEmpty()) return null
        val others = pool.filter { it.id != currentId }.ifEmpty { pool }
        if (this == RATED) {
            val target = progress.rating
            val fresh = others.filter { it.id !in progress.rated }.ifEmpty { others }
            var window = 75
            while (true) {
                val near = fresh.filter { abs(it.rating - target) <= window }
                if (near.isNotEmpty() || window > 3000) {
                    return (near.ifEmpty { fresh }).random(random)
                }
                window += 75
            }
        }
        val solved = progress.solved
        // Práctica: en orden de dificultad, el primero sin resolver.
        return others.firstOrNull { it.id !in solved } ?: others.random(random)
    }

    /** Cuántos puzzles del modo están resueltos (sin fallos) y cuántos hay. */
    fun solvedCount(all: List<Puzzle>, progress: Progress): Pair<Int, Int> {
        val pool = pool(all, progress)
        val solved = progress.solved
        return pool.count { it.id in solved } to pool.size
    }
}
