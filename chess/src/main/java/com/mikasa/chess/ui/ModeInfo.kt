package com.mikasa.chess.ui

import com.mikasa.chess.R
import com.mikasa.chess.puzzle.PuzzleMode

/** Texto e icono (glifo de pieza) de cada modo en la interfaz. */
val PuzzleMode.titleRes: Int
    get() = when (this) {
        PuzzleMode.RATED -> R.string.mode_rated
        PuzzleMode.MATE_1 -> R.string.mode_mate1
        PuzzleMode.MATE_2 -> R.string.mode_mate2
        PuzzleMode.MATE_3 -> R.string.mode_mate3
        PuzzleMode.MATE_4 -> R.string.mode_mate4
        PuzzleMode.FAMOUS -> R.string.mode_famous
        PuzzleMode.REVIEW -> R.string.mode_review
    }

val PuzzleMode.subtitleRes: Int
    get() = when (this) {
        PuzzleMode.RATED -> R.string.mode_rated_desc
        PuzzleMode.MATE_1 -> R.string.mode_mate1_desc
        PuzzleMode.MATE_2 -> R.string.mode_mate2_desc
        PuzzleMode.MATE_3 -> R.string.mode_mate3_desc
        PuzzleMode.MATE_4 -> R.string.mode_mate4_desc
        PuzzleMode.FAMOUS -> R.string.mode_famous_desc
        PuzzleMode.REVIEW -> R.string.mode_review_desc
    }

val PuzzleMode.glyph: String
    get() = when (this) {
        PuzzleMode.RATED -> "♛"
        PuzzleMode.MATE_1 -> "♟"
        PuzzleMode.MATE_2 -> "♞"
        PuzzleMode.MATE_3 -> "♝"
        PuzzleMode.MATE_4 -> "♜"
        PuzzleMode.FAMOUS -> "♚"
        PuzzleMode.REVIEW -> "♙"
    }
