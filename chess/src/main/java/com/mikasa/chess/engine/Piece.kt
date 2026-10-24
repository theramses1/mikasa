package com.mikasa.chess.engine

/** Codificación de piezas: tipo 1..6, positivo = blancas, negativo = negras. */
object Piece {
    const val PAWN = 1
    const val KNIGHT = 2
    const val BISHOP = 3
    const val ROOK = 4
    const val QUEEN = 5
    const val KING = 6

    fun type(piece: Int): Int = Math.abs(piece)

    fun isWhite(piece: Int): Boolean = piece > 0

    fun of(type: Int, white: Boolean): Int = if (white) type else -type

    fun fenChar(piece: Int): Char {
        val c = " pnbrqk"[type(piece)]
        return if (piece > 0) c.uppercaseChar() else c
    }

    fun fromFenChar(c: Char): Int {
        val type = " pnbrqk".indexOf(c.lowercaseChar())
        require(type > 0) { "Pieza inválida: $c" }
        return if (c.isUpperCase()) type else -type
    }
}

/** Jugada: casilla origen, destino y pieza de promoción (0 si no hay). */
data class Move(val from: Int, val to: Int, val promotion: Int = 0) {

    fun uci(): String {
        val promo = if (promotion != 0) " pnbrqk"[promotion].toString() else ""
        return Board.squareName(from) + Board.squareName(to) + promo
    }

    override fun toString(): String = uci()

    companion object {
        fun fromUci(uci: String): Move? {
            if (uci.length !in 4..5) return null
            val from = Board.parseSquare(uci.substring(0, 2))
            val to = Board.parseSquare(uci.substring(2, 4))
            if (from < 0 || to < 0) return null
            val promo = if (uci.length == 5) " pnbrqk".indexOf(uci[4]) else 0
            if (promo < 0 || promo == Piece.PAWN || promo == Piece.KING) return null
            return Move(from, to, promo)
        }
    }
}
