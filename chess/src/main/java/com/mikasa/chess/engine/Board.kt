package com.mikasa.chess.engine

/**
 * Posición de ajedrez inmutable con generación de jugadas legales.
 *
 * Casillas: índice 0..63 con a1 = 0, h1 = 7, a8 = 56, h8 = 63.
 * Piezas: entero con signo; positivo = blancas, negativo = negras
 * (ver [Piece]). 0 = casilla vacía.
 */
class Board private constructor(
    private val squares: IntArray,
    /** true si juegan las blancas. */
    val whiteToMove: Boolean,
    /** Bits de enroque: 1 = K, 2 = Q, 4 = k, 8 = q. */
    val castling: Int,
    /** Casilla de captura al paso o -1. */
    val epSquare: Int,
    val halfmoveClock: Int,
    val fullmoveNumber: Int,
) {
    private var legalCache: List<Move>? = null

    operator fun get(square: Int): Int = squares[square]

    fun pieceAt(file: Int, rank: Int): Int = squares[rank * 8 + file]

    fun kingSquare(white: Boolean): Int {
        val king = if (white) Piece.KING else -Piece.KING
        return squares.indexOf(king)
    }

    // ---------------------------------------------------------------- ataques

    /** ¿Está [square] atacada por las piezas del bando [byWhite]? */
    fun isAttacked(square: Int, byWhite: Boolean): Boolean {
        val sign = if (byWhite) 1 else -1
        val f = file(square)
        val r = rank(square)

        // Peones: un peón blanco ataca desde la fila de abajo.
        val pawnRank = r - sign
        if (pawnRank in 0..7) {
            for (df in intArrayOf(-1, 1)) {
                val pf = f + df
                if (pf in 0..7 && squares[pawnRank * 8 + pf] == sign * Piece.PAWN) return true
            }
        }
        for ((df, dr) in KNIGHT_DELTAS) {
            val nf = f + df
            val nr = r + dr
            if (nf in 0..7 && nr in 0..7 && squares[nr * 8 + nf] == sign * Piece.KNIGHT) return true
        }
        for ((df, dr) in KING_DELTAS) {
            val nf = f + df
            val nr = r + dr
            if (nf in 0..7 && nr in 0..7 && squares[nr * 8 + nf] == sign * Piece.KING) return true
        }
        for ((df, dr) in ROOK_DIRS) {
            if (slideHits(f, r, df, dr, sign, Piece.ROOK)) return true
        }
        for ((df, dr) in BISHOP_DIRS) {
            if (slideHits(f, r, df, dr, sign, Piece.BISHOP)) return true
        }
        return false
    }

    private fun slideHits(f: Int, r: Int, df: Int, dr: Int, sign: Int, slider: Int): Boolean {
        var nf = f + df
        var nr = r + dr
        while (nf in 0..7 && nr in 0..7) {
            val p = squares[nr * 8 + nf]
            if (p != 0) {
                return p == sign * slider || p == sign * Piece.QUEEN
            }
            nf += df
            nr += dr
        }
        return false
    }

    fun inCheck(): Boolean = isAttacked(kingSquare(whiteToMove), !whiteToMove)

    fun isCheckmate(): Boolean = inCheck() && legalMoves().isEmpty()

    fun isStalemate(): Boolean = !inCheck() && legalMoves().isEmpty()

    // -------------------------------------------------------------- jugadas

    fun legalMoves(): List<Move> {
        legalCache?.let { return it }
        val result = pseudoLegalMoves().filter { move ->
            val after = applyUnchecked(move)
            !after.isAttacked(after.kingSquare(whiteToMove), !whiteToMove)
        }
        legalCache = result
        return result
    }

    fun legalMovesFrom(square: Int): List<Move> = legalMoves().filter { it.from == square }

    fun isLegal(move: Move): Boolean = legalMoves().contains(move)

    private fun pseudoLegalMoves(): List<Move> {
        val moves = ArrayList<Move>(48)
        val sign = if (whiteToMove) 1 else -1
        for (sq in 0 until 64) {
            val p = squares[sq]
            if (p == 0 || (p > 0) != whiteToMove) continue
            val f = file(sq)
            val r = rank(sq)
            when (Piece.type(p)) {
                Piece.PAWN -> pawnMoves(sq, f, r, sign, moves)
                Piece.KNIGHT -> stepMoves(sq, f, r, KNIGHT_DELTAS, moves)
                Piece.BISHOP -> slideMoves(sq, f, r, BISHOP_DIRS, moves)
                Piece.ROOK -> slideMoves(sq, f, r, ROOK_DIRS, moves)
                Piece.QUEEN -> {
                    slideMoves(sq, f, r, ROOK_DIRS, moves)
                    slideMoves(sq, f, r, BISHOP_DIRS, moves)
                }
                Piece.KING -> {
                    stepMoves(sq, f, r, KING_DELTAS, moves)
                    castlingMoves(sq, moves)
                }
            }
        }
        return moves
    }

    private fun pawnMoves(sq: Int, f: Int, r: Int, sign: Int, out: MutableList<Move>) {
        val startRank = if (sign > 0) 1 else 6
        val lastRank = if (sign > 0) 7 else 0
        val r1 = r + sign
        if (r1 !in 0..7) return
        fun add(to: Int) {
            if (rank(to) == lastRank) {
                for (promo in PROMOTIONS) out.add(Move(sq, to, promo))
            } else {
                out.add(Move(sq, to))
            }
        }
        val one = r1 * 8 + f
        if (squares[one] == 0) {
            add(one)
            val two = (r + 2 * sign) * 8 + f
            if (r == startRank && squares[two] == 0) out.add(Move(sq, two))
        }
        for (df in intArrayOf(-1, 1)) {
            val cf = f + df
            if (cf !in 0..7) continue
            val to = r1 * 8 + cf
            val target = squares[to]
            if (target != 0 && (target > 0) != (sign > 0)) add(to)
            else if (to == epSquare) out.add(Move(sq, to))
        }
    }

    private fun stepMoves(sq: Int, f: Int, r: Int, deltas: Array<IntArray>, out: MutableList<Move>) {
        for ((df, dr) in deltas) {
            val nf = f + df
            val nr = r + dr
            if (nf !in 0..7 || nr !in 0..7) continue
            val to = nr * 8 + nf
            val target = squares[to]
            if (target == 0 || (target > 0) != whiteToMove) out.add(Move(sq, to))
        }
    }

    private fun slideMoves(sq: Int, f: Int, r: Int, dirs: Array<IntArray>, out: MutableList<Move>) {
        for ((df, dr) in dirs) {
            var nf = f + df
            var nr = r + dr
            while (nf in 0..7 && nr in 0..7) {
                val to = nr * 8 + nf
                val target = squares[to]
                if (target == 0) {
                    out.add(Move(sq, to))
                } else {
                    if ((target > 0) != whiteToMove) out.add(Move(sq, to))
                    break
                }
                nf += df
                nr += dr
            }
        }
    }

    private fun castlingMoves(sq: Int, out: MutableList<Move>) {
        val home = if (whiteToMove) 4 else 60
        if (sq != home) return
        val kingSide = if (whiteToMove) CASTLE_WK else CASTLE_BK
        val queenSide = if (whiteToMove) CASTLE_WQ else CASTLE_BQ
        val rook = if (whiteToMove) Piece.ROOK else -Piece.ROOK
        val enemy = !whiteToMove
        if ((castling and (kingSide or queenSide)) == 0 || isAttacked(home, enemy)) return
        if ((castling and kingSide) != 0 && squares[home + 3] == rook &&
            squares[home + 1] == 0 && squares[home + 2] == 0 &&
            !isAttacked(home + 1, enemy) && !isAttacked(home + 2, enemy)
        ) {
            out.add(Move(home, home + 2))
        }
        if ((castling and queenSide) != 0 && squares[home - 4] == rook &&
            squares[home - 1] == 0 && squares[home - 2] == 0 && squares[home - 3] == 0 &&
            !isAttacked(home - 1, enemy) && !isAttacked(home - 2, enemy)
        ) {
            out.add(Move(home, home - 2))
        }
    }

    /** Aplica una jugada legal y devuelve la nueva posición. */
    fun play(move: Move): Board {
        require(isLegal(move)) { "Jugada ilegal: ${move.uci()} en ${toFen()}" }
        return applyUnchecked(move)
    }

    private fun applyUnchecked(move: Move): Board {
        val s = squares.copyOf()
        val piece = s[move.from]
        val type = Piece.type(piece)
        val captured = s[move.to]
        var newEp = -1

        s[move.to] = if (move.promotion != 0) Piece.of(move.promotion, piece > 0) else piece
        s[move.from] = 0

        if (type == Piece.PAWN) {
            if (move.to == epSquare && captured == 0 && file(move.to) != file(move.from)) {
                // Captura al paso: el peón capturado está detrás de la casilla destino.
                s[move.to - (if (piece > 0) 8 else -8)] = 0
            }
            if (Math.abs(move.to - move.from) == 16) newEp = (move.to + move.from) / 2
        }
        if (type == Piece.KING && Math.abs(move.to - move.from) == 2) {
            if (move.to > move.from) {
                s[move.from + 1] = s[move.from + 3]
                s[move.from + 3] = 0
            } else {
                s[move.from - 1] = s[move.from - 4]
                s[move.from - 4] = 0
            }
        }

        var rights = castling
        rights = rights and CASTLE_MASK[move.from] and CASTLE_MASK[move.to]

        val reset = type == Piece.PAWN || captured != 0
        return Board(
            s,
            !whiteToMove,
            rights,
            newEp,
            if (reset) 0 else halfmoveClock + 1,
            if (whiteToMove) fullmoveNumber else fullmoveNumber + 1,
        )
    }

    // ------------------------------------------------------------ notación

    /** Jugada a partir de notación UCI (p. ej. "e2e4", "e7e8q"), o null si no es legal. */
    fun parseUci(uci: String): Move? {
        val move = Move.fromUci(uci) ?: return null
        return legalMoves().firstOrNull { it == move }
    }

    /**
     * Notación algebraica (SAN) de una jugada legal en esta posición.
     * [letters] son las letras de rey, dama, torre, alfil y caballo
     * (por defecto en español: R, D, T, A, C).
     */
    fun san(move: Move, letters: String = "RDTAC"): String {
        val piece = squares[move.from]
        val type = Piece.type(piece)
        val sb = StringBuilder()
        if (type == Piece.KING && Math.abs(move.to - move.from) == 2) {
            sb.append(if (move.to > move.from) "O-O" else "O-O-O")
        } else {
            val capture = squares[move.to] != 0 ||
                (type == Piece.PAWN && file(move.from) != file(move.to))
            if (type == Piece.PAWN) {
                if (capture) sb.append('a' + file(move.from))
            } else {
                sb.append(letterFor(type, letters))
                val rivals = legalMoves().filter {
                    it.to == move.to && it.from != move.from && squares[it.from] == piece
                }
                if (rivals.isNotEmpty()) {
                    val sameFile = rivals.any { file(it.from) == file(move.from) }
                    val sameRank = rivals.any { rank(it.from) == rank(move.from) }
                    if (!sameFile) sb.append('a' + file(move.from))
                    else if (!sameRank) sb.append('1' + rank(move.from))
                    else sb.append(squareName(move.from))
                }
            }
            if (capture) sb.append('x')
            sb.append(squareName(move.to))
            if (move.promotion != 0) sb.append('=').append(letterFor(move.promotion, letters))
        }
        val after = applyUnchecked(move)
        if (after.inCheck()) sb.append(if (after.legalMoves().isEmpty()) '#' else '+')
        return sb.toString()
    }

    private fun letterFor(type: Int, letters: String): Char = when (type) {
        Piece.KING -> letters[0]
        Piece.QUEEN -> letters[1]
        Piece.ROOK -> letters[2]
        Piece.BISHOP -> letters[3]
        else -> letters[4]
    }

    fun toFen(): String {
        val sb = StringBuilder()
        for (r in 7 downTo 0) {
            var empty = 0
            for (f in 0..7) {
                val p = squares[r * 8 + f]
                if (p == 0) {
                    empty++
                } else {
                    if (empty > 0) sb.append(empty).also { empty = 0 }
                    sb.append(Piece.fenChar(p))
                }
            }
            if (empty > 0) sb.append(empty)
            if (r > 0) sb.append('/')
        }
        sb.append(if (whiteToMove) " w " else " b ")
        val c = buildString {
            if ((castling and CASTLE_WK) != 0) append('K')
            if ((castling and CASTLE_WQ) != 0) append('Q')
            if ((castling and CASTLE_BK) != 0) append('k')
            if ((castling and CASTLE_BQ) != 0) append('q')
        }
        sb.append(c.ifEmpty { "-" })
        sb.append(' ').append(if (epSquare >= 0) squareName(epSquare) else "-")
        sb.append(' ').append(halfmoveClock).append(' ').append(fullmoveNumber)
        return sb.toString()
    }

    companion object {
        const val CASTLE_WK = 1
        const val CASTLE_WQ = 2
        const val CASTLE_BK = 4
        const val CASTLE_BQ = 8

        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        private val KNIGHT_DELTAS = arrayOf(
            intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(1, -2),
            intArrayOf(-1, -2), intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, 2),
        )
        private val KING_DELTAS = arrayOf(
            intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1),
            intArrayOf(-1, 0), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1),
        )
        private val ROOK_DIRS = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
        private val BISHOP_DIRS = arrayOf(intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))
        private val PROMOTIONS = intArrayOf(Piece.QUEEN, Piece.ROOK, Piece.BISHOP, Piece.KNIGHT)

        /** Derechos de enroque que se conservan al mover desde/hacia cada casilla. */
        private val CASTLE_MASK = IntArray(64) { 15 }.also {
            it[0] = 15 and CASTLE_WQ.inv()
            it[7] = 15 and CASTLE_WK.inv()
            it[4] = 15 and (CASTLE_WK or CASTLE_WQ).inv()
            it[56] = 15 and CASTLE_BQ.inv()
            it[63] = 15 and CASTLE_BK.inv()
            it[60] = 15 and (CASTLE_BK or CASTLE_BQ).inv()
        }

        fun file(square: Int) = square and 7
        fun rank(square: Int) = square shr 3
        fun square(file: Int, rank: Int) = rank * 8 + file
        fun squareName(square: Int): String = "${'a' + file(square)}${'1' + rank(square)}"

        fun parseSquare(name: String): Int {
            if (name.length != 2) return -1
            val f = name[0] - 'a'
            val r = name[1] - '1'
            return if (f in 0..7 && r in 0..7) r * 8 + f else -1
        }

        fun fromFen(fen: String): Board {
            val parts = fen.trim().split(Regex("\\s+"))
            require(parts.size >= 4) { "FEN inválido: $fen" }
            val squares = IntArray(64)
            val rows = parts[0].split('/')
            require(rows.size == 8) { "FEN inválido: $fen" }
            for ((i, row) in rows.withIndex()) {
                val r = 7 - i
                var f = 0
                for (c in row) {
                    if (c.isDigit()) {
                        f += c - '0'
                    } else {
                        require(f < 8) { "FEN inválido: $fen" }
                        squares[r * 8 + f] = Piece.fromFenChar(c)
                        f++
                    }
                }
                require(f == 8) { "FEN inválido: $fen" }
            }
            var rights = 0
            for (c in parts[2]) {
                rights = rights or when (c) {
                    'K' -> CASTLE_WK
                    'Q' -> CASTLE_WQ
                    'k' -> CASTLE_BK
                    'q' -> CASTLE_BQ
                    else -> 0
                }
            }
            return Board(
                squares,
                parts[1] == "w",
                rights,
                if (parts[3] == "-") -1 else parseSquare(parts[3]),
                parts.getOrNull(4)?.toIntOrNull() ?: 0,
                parts.getOrNull(5)?.toIntOrNull() ?: 1,
            )
        }
    }
}
