package com.mikasa.chess.puzzle

import com.mikasa.chess.engine.Board
import com.mikasa.chess.engine.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class MapStore : KeyValueStore {
    private val map = HashMap<String, Any>()
    override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
    override fun putInt(key: String, value: Int) {
        map[key] = value
    }
    override fun getString(key: String) = map[key] as? String
    override fun putString(key: String, value: String) {
        map[key] = value
    }
    override fun clear() = map.clear()
}

class PuzzleTest {

    // Legal vs. Saint Brie: 5...Axd1 6.Axf7+ Re7 7.Cd5#
    private val legal = Puzzle(
        id = 1,
        fen = "rn1qkbnr/ppp2p1p/3p2p1/4N3/2B1P1b1/2N5/PPPP1PPP/R1BQK2R b KQkq - 0 5",
        moves = listOf("g4d1", "c4f7", "e8e7", "c3d5"),
        rating = 1100,
        mateIn = 2,
        source = "Legal",
    )

    private fun uci(s: String) = Move.fromUci(s)!!

    @Test
    fun solvingCleanly() {
        val s = PuzzleSession(legal)
        assertTrue(s.playerIsWhite)
        assertFalse(s.isPlayerTurn)
        s.playSetupMove()
        assertTrue(s.isPlayerTurn)
        assertEquals(2, s.playerMovesLeft)
        assertEquals(PuzzleSession.Result.CORRECT, s.tryMove(uci("c4f7")))
        assertTrue(s.isReplyPending)
        assertEquals(uci("e8e7"), s.playReply())
        assertEquals(1, s.playerMovesLeft)
        assertEquals(PuzzleSession.Result.SOLVED, s.tryMove(uci("c3d5")))
        assertTrue(s.isSolved)
        assertFalse(s.failed)
        assertTrue(s.board.isCheckmate())
        assertEquals("5... Axd1 6. Axf7+ Re7 7. Cd5#", s.movesText())
    }

    @Test
    fun wrongMoveKeepsPositionAndMarksFailed() {
        val s = PuzzleSession(legal)
        s.playSetupMove()
        val before = s.board.toFen()
        assertEquals(PuzzleSession.Result.WRONG, s.tryMove(uci("a2a3")))
        assertEquals(before, s.board.toFen())
        assertTrue(s.failed)
        assertTrue(s.isPlayerTurn)
    }

    @Test
    fun anyMateIsAcceptedOnTheLastMove() {
        // Dos mates posibles en 1: Ta8# y Tb8#... solo uno está en la solución.
        val p = Puzzle(
            id = 2,
            fen = "6k1/5ppp/8/8/8/8/5PPP/RR4K1 b - - 0 1",
            moves = listOf("g8h8", "a1a8"),
            rating = 600,
            mateIn = 1,
            source = "",
        )
        val s = PuzzleSession(p)
        s.playSetupMove()
        assertEquals(PuzzleSession.Result.SOLVED, s.tryMove(uci("b1b8")))
        assertFalse(s.failed)
    }

    @Test
    fun hintAndSolutionCountAsFailure() {
        val s = PuzzleSession(legal)
        s.playSetupMove()
        assertEquals(Board.parseSquare("c4"), s.hint())
        assertTrue(s.failed)
        while (!s.isSolved) s.revealNext()
        assertTrue(s.board.isCheckmate())
    }

    @Test
    fun ratingGoesUpOnSuccessAndOnlyCountsOnce() {
        val progress = Progress(MapStore())
        assertEquals(Progress.INITIAL_RATING, progress.rating)
        val delta = progress.recordRated(legal, true)
        assertTrue(delta > 0)
        assertEquals(Progress.INITIAL_RATING + delta, progress.rating)
        assertEquals(0, progress.recordRated(legal, false))
        assertEquals(1, progress.ratedAttempts)
    }

    @Test
    fun ratingDeltaIsSymmetricAndBounded() {
        assertEquals(30, Progress.ratingDelta(1000, 1000, true, 0))
        assertEquals(-30, Progress.ratingDelta(1000, 1000, false, 0))
        assertEquals(1, Progress.ratingDelta(2500, 500, true, 100))
        assertTrue(Progress.ratingDelta(1000, 1400, false, 100) in -5..-1)
    }

    @Test
    fun streaksAndReview() {
        val progress = Progress(MapStore())
        progress.recordSolved(legal, clean = true)
        assertEquals(1, progress.streak)
        val other = legal.copy(id = 7)
        progress.recordFailed(other)
        assertEquals(0, progress.streak)
        assertEquals(1, progress.bestStreak)
        assertEquals(setOf(7), progress.failed)
        assertEquals(listOf(other), PuzzleMode.REVIEW.pool(listOf(legal, other), progress))
        progress.recordSolved(other, clean = true)
        assertTrue(progress.failed.isEmpty())
    }

    @Test
    fun ratedModePicksPuzzlesNearTheUserRating() {
        val puzzles = (1..50).map { legal.copy(id = it, rating = 400 + it * 50) }
        val progress = Progress(MapStore())
        repeat(20) {
            val p = PuzzleMode.RATED.next(puzzles, progress, null, Random(it))!!
            assertTrue("rating ${p.rating}", Math.abs(p.rating - progress.rating) <= 75)
        }
    }

    @Test
    fun practiceModeGoesInOrderSkippingSolved() {
        val puzzles = listOf(legal.copy(id = 1, rating = 900), legal.copy(id = 2, rating = 800))
        val progress = Progress(MapStore())
        assertEquals(2, PuzzleMode.MATE_2.next(puzzles, progress, null)!!.id)
        progress.recordSolved(puzzles[1], clean = true)
        assertEquals(1, PuzzleMode.MATE_2.next(puzzles, progress, null)!!.id)
        assertEquals(1 to 2, PuzzleMode.MATE_2.solvedCount(puzzles, progress))
    }

    /** Todos los puzzles incluidos en la app son legales y terminan en mate. */
    @Test
    fun bundledPuzzlesAreValid() {
        val file = listOf("src/main/assets/puzzles.tsv", "chess/src/main/assets/puzzles.tsv")
            .map(::File).first { it.exists() }
        val puzzles = file.inputStream().use(Puzzle::parseTsv)
        assertTrue(puzzles.isNotEmpty())
        assertEquals(puzzles.size, puzzles.map { it.id }.toSet().size)
        for (p in puzzles) {
            val s = PuzzleSession(p)
            assertEquals("puzzle ${p.id}", p.mateIn * 2, p.moves.size)
            s.playSetupMove()
            while (!s.isSolved) {
                if (s.isPlayerTurn) {
                    assertNotNull(s.expectedMove())
                    assertTrue(s.tryMove(s.expectedMove()!!) != PuzzleSession.Result.WRONG)
                } else {
                    s.playReply()
                }
            }
            assertTrue("puzzle ${p.id} no termina en mate", s.board.isCheckmate())
            assertFalse(s.failed)
        }
    }
}
