package com.mikasa.chess.puzzle

import com.mikasa.chess.engine.Board
import java.io.BufferedReader
import java.io.InputStream

/**
 * Un puzzle de mate.
 *
 * [fen] es la posición ANTES de la última jugada del rival. [moves] (UCI)
 * empieza con esa jugada del rival y sigue con la solución, alternando
 * jugador / rival y terminando con la jugada que da mate.
 */
data class Puzzle(
    val id: Int,
    val fen: String,
    val moves: List<String>,
    val rating: Int,
    val mateIn: Int,
    /** Partida de origen ("" si es una posición generada). */
    val source: String,
) {
    val isFamous: Boolean get() = source.isNotEmpty()

    /** true si el jugador juega con blancas (le toca después de la jugada del rival). */
    val playerIsWhite: Boolean get() = !Board.fromFen(fen).whiteToMove

    companion object {
        /** Lee el formato TSV generado por `tools/generate_puzzles.py`. */
        fun parseTsv(input: InputStream): List<Puzzle> =
            input.bufferedReader(Charsets.UTF_8).use(::parseLines)

        private fun parseLines(reader: BufferedReader): List<Puzzle> =
            reader.lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line ->
                    val cols = line.split('\t')
                    Puzzle(
                        id = cols[0].toInt(),
                        fen = cols[1],
                        moves = cols[2].split(' '),
                        rating = cols[3].toInt(),
                        mateIn = cols[4].toInt(),
                        source = cols.getOrElse(5) { "" },
                    )
                }
                .toList()
    }
}
