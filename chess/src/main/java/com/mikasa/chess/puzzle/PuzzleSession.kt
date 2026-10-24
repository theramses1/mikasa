package com.mikasa.chess.puzzle

import com.mikasa.chess.engine.Board
import com.mikasa.chess.engine.Move

/**
 * Estado de un intento de resolver un puzzle. No depende de Android: la UI
 * llama a [playSetupMove], luego a [tryMove] con cada jugada del usuario y a
 * [playReply] para la respuesta del rival.
 */
class PuzzleSession(val puzzle: Puzzle) {

    enum class Result { CORRECT, SOLVED, WRONG }

    private val solution: List<Move>

    init {
        // Convierte y valida toda la línea una sola vez.
        var b = Board.fromFen(puzzle.fen)
        solution = puzzle.moves.map { uci ->
            val move = requireNotNull(b.parseUci(uci)) { "Puzzle ${puzzle.id}: jugada ilegal $uci" }
            b = b.play(move)
            move
        }
    }

    /** Posición actual. */
    var board: Board = Board.fromFen(puzzle.fen)
        private set

    /** Índice de la siguiente jugada de [solution] que hay que hacer. */
    var index: Int = 0
        private set

    /** Jugadas hechas hasta ahora (incluida la del rival inicial), en SAN. */
    val playedSan = mutableListOf<String>()

    /** Última jugada aplicada al tablero (para resaltarla). */
    var lastMove: Move? = null
        private set

    /** El usuario se equivocó, pidió pista o vio la solución en este puzzle. */
    var failed: Boolean = false
        private set

    var hintsUsed: Int = 0
        private set

    val isSolved: Boolean get() = index >= solution.size

    val playerIsWhite: Boolean get() = puzzle.playerIsWhite

    /** Le toca al jugador (y el puzzle no ha terminado). */
    val isPlayerTurn: Boolean get() = !isSolved && index % 2 == 1

    /** Hay una respuesta del rival pendiente de jugar. */
    val isReplyPending: Boolean get() = !isSolved && index % 2 == 0 && index > 0

    /** Jugadas del jugador que quedan (incluida la actual). */
    val playerMovesLeft: Int get() = (solution.size - index + 1) / 2

    /** Aplica la jugada inicial del rival. */
    fun playSetupMove(): Move {
        check(index == 0)
        return advance(solution[0])
    }

    /**
     * Comprueba una jugada del usuario. Si es correcta se aplica; en la última
     * jugada se acepta cualquier mate aunque no sea el de la solución.
     */
    fun tryMove(move: Move): Result {
        check(isPlayerTurn)
        val expected = solution[index]
        val isLast = index == solution.size - 1
        val accepted = move == expected ||
            (isLast && board.isLegal(move) && board.play(move).isCheckmate())
        if (!accepted) {
            failed = true
            return Result.WRONG
        }
        advance(move)
        return if (isSolved) Result.SOLVED else Result.CORRECT
    }

    /** Juega la respuesta del rival. */
    fun playReply(): Move {
        check(isReplyPending)
        return advance(solution[index])
    }

    /** Jugada que espera la solución en este momento (para pistas y "ver solución"). */
    fun expectedMove(): Move? = solution.getOrNull(index)

    /** Pista: casilla de la pieza que hay que mover. Cuenta como fallo. */
    fun hint(): Int {
        check(isPlayerTurn)
        hintsUsed++
        failed = true
        return solution[index].from
    }

    /** Juega la siguiente jugada de la solución, sea de quien sea. Cuenta como fallo. */
    fun revealNext(): Move {
        check(!isSolved)
        failed = true
        return advance(solution[index])
    }

    private fun advance(move: Move): Move {
        playedSan += board.san(move)
        board = board.play(move)
        lastMove = move
        index++
        return move
    }

    /** Texto de las jugadas con numeración, p. ej. "14... De6 15. Axd7+". */
    fun movesText(): String {
        val start = Board.fromFen(puzzle.fen)
        var number = start.fullmoveNumber
        var white = start.whiteToMove
        val sb = StringBuilder()
        for ((i, san) in playedSan.withIndex()) {
            if (white) {
                sb.append(number).append(". ")
            } else if (i == 0) {
                sb.append(number).append("... ")
            }
            sb.append(san).append(' ')
            if (!white) number++
            white = !white
        }
        return sb.toString().trim()
    }
}
