package com.mikasa.chess.ui

import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.mikasa.chess.R
import com.mikasa.chess.engine.Board
import com.mikasa.chess.engine.Move
import com.mikasa.chess.engine.Piece
import com.mikasa.chess.puzzle.Progress
import com.mikasa.chess.puzzle.PuzzleMode
import com.mikasa.chess.puzzle.PuzzleSession
import com.mikasa.chess.data.progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/** Pruebas de las pantallas reales, con toques simulados sobre el tablero. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiTest {

    private fun idle(ms: Long = 1500) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun launch(mode: PuzzleMode): PuzzleActivity {
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName("com.mikasa.chess", PuzzleActivity::class.java.name)
            .putExtra(PuzzleActivity.EXTRA_MODE, mode.name)
        val activity = Robolectric.buildActivity(PuzzleActivity::class.java, intent).setup().get()
        idle()
        return activity
    }

    private fun PuzzleActivity.session(): PuzzleSession {
        val f = PuzzleActivity::class.java.getDeclaredField("session")
        f.isAccessible = true
        return f.get(this) as PuzzleSession
    }

    private fun PuzzleActivity.board(): BoardView = findViewById(R.id.board)
    private fun PuzzleActivity.status(): String = findViewById<TextView>(R.id.status_title).text.toString()
    private fun PuzzleActivity.visible(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    private fun center(board: BoardView, square: Int): Pair<Float, Float> {
        val s = board.width / 8f
        val col = if (board.flipped) 7 - Board.file(square) else Board.file(square)
        val row = if (board.flipped) Board.rank(square) else 7 - Board.rank(square)
        return (col + 0.5f) * s to (row + 0.5f) * s
    }

    private fun touch(board: BoardView, action: Int, square: Int) {
        val (x, y) = center(board, square)
        val t = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(t, t, action, x, y, 0)
        board.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun tapMove(board: BoardView, move: Move) {
        touch(board, MotionEvent.ACTION_DOWN, move.from)
        touch(board, MotionEvent.ACTION_UP, move.from)
        touch(board, MotionEvent.ACTION_DOWN, move.to)
        touch(board, MotionEvent.ACTION_UP, move.to)
        choosePromotion(move)
    }

    private fun dragMove(board: BoardView, move: Move) {
        touch(board, MotionEvent.ACTION_DOWN, move.from)
        touch(board, MotionEvent.ACTION_MOVE, move.to)
        touch(board, MotionEvent.ACTION_UP, move.to)
        choosePromotion(move)
    }

    private fun choosePromotion(move: Move) {
        if (move.promotion == 0) return
        val dialog = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        val index = listOf(Piece.QUEEN, Piece.ROOK, Piece.BISHOP, Piece.KNIGHT).indexOf(move.promotion)
        dialog.listView.performItemClick(null, index, index.toLong())
    }

    /** Resuelve el puzzle con las jugadas de la solución. */
    private fun solve(activity: PuzzleActivity, drag: Boolean) {
        val session = activity.session()
        while (!session.isSolved) {
            assertTrue(session.isPlayerTurn)
            assertTrue(activity.board().interactive)
            val move = session.expectedMove()!!
            if (drag) dragMove(activity.board(), move) else tapMove(activity.board(), move)
            idle()
        }
    }

    @Test
    fun mainScreenListsModesAndOpensPuzzles() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals("${Progress.INITIAL_RATING}", main.findViewById<TextView>(R.id.rating).text.toString())
        val modes = main.findViewById<LinearLayout>(R.id.modes)
        // Todos los modos menos "Repasar fallados" (aún no hay fallos).
        assertEquals(PuzzleMode.values().size - 1, modes.childCount)
        modes.getChildAt(0).performClick()
        val started = shadowOf(main).nextStartedActivity
        assertEquals(PuzzleActivity::class.java.name, started.component!!.className)
        assertEquals(PuzzleMode.RATED.name, started.getStringExtra(PuzzleActivity.EXTRA_MODE))
    }

    @Test
    fun ratedPuzzleSolvedCleanlyRaisesRating() {
        val activity = launch(PuzzleMode.RATED)
        val session = activity.session()
        assertEquals(session.playerIsWhite, !activity.board().flipped)
        assertTrue(activity.status().startsWith("Juegan las"))
        assertFalse(activity.visible(R.id.btn_next)) // en modo con rating no se puede saltar

        solve(activity, drag = true)

        assertEquals("¡Jaque mate!", activity.status())
        assertTrue(session.board.isCheckmate())
        val rating = activity.progress().rating
        assertTrue("rating $rating", rating > Progress.INITIAL_RATING)
        assertEquals(rating.toString(), activity.findViewById<TextView>(R.id.badge).text.toString())
        assertTrue(activity.findViewById<TextView>(R.id.rating_delta).text.startsWith("+"))
        assertTrue(activity.visible(R.id.btn_next))
        assertTrue(activity.visible(R.id.btn_retry))
        assertFalse(activity.visible(R.id.btn_hint))
    }

    @Test
    fun wrongMoveIsUndoneAndPuzzleCanStillBeFinished() {
        val activity = launch(PuzzleMode.MATE_2)
        val session = activity.session()
        val fenBefore = session.board.toFen()
        val expected = session.expectedMove()!!
        val wrong = session.board.legalMoves().first { it != expected && !session.board.play(it).isCheckmate() }

        tapMove(activity.board(), wrong)
        assertEquals("Incorrecto", activity.status())
        assertFalse(activity.board().interactive)
        idle()
        // Se deshace la jugada y se puede volver a intentar.
        assertEquals(fenBefore, activity.board().board!!.toFen())
        assertTrue(activity.board().interactive)
        assertTrue(session.failed)
        assertTrue(session.puzzle.id in activity.progress().failed)

        solve(activity, drag = false)
        assertEquals("Puzzle completado", activity.status())
    }

    @Test
    fun hintThenSolutionPlaysToMate() {
        val activity = launch(PuzzleMode.FAMOUS)
        val session = activity.session()
        activity.findViewById<Button>(R.id.btn_hint).performClick()
        assertEquals("Pista", activity.status())
        activity.findViewById<Button>(R.id.btn_solution).performClick()
        idle(10_000)
        assertTrue(session.isSolved)
        assertTrue(session.board.isCheckmate())
        assertEquals("Puzzle completado", activity.status())
        assertTrue(session.puzzle.isFamous)
        assertTrue(activity.findViewById<TextView>(R.id.moves).text.contains("#"))
    }

    @Test
    fun nextLoadsAnotherPuzzle() {
        val activity = launch(PuzzleMode.MATE_1)
        val first = activity.session().puzzle.id
        solve(activity, drag = false)
        assertEquals("¡Jaque mate!", activity.status())
        activity.findViewById<Button>(R.id.btn_next).performClick()
        idle()
        assertNotEquals(first, activity.session().puzzle.id)
        assertTrue(activity.board().interactive)
        // Reintentar vuelve a la posición inicial del mismo puzzle.
        val second = activity.session().puzzle.id
        solve(activity, drag = true)
        activity.findViewById<Button>(R.id.btn_retry).performClick()
        idle()
        assertEquals(second, activity.session().puzzle.id)
        assertFalse(activity.session().isSolved)
    }
}
