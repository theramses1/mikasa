package com.mikasa.chess.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.mikasa.chess.R
import com.mikasa.chess.data.PuzzleRepository
import com.mikasa.chess.data.progress
import com.mikasa.chess.engine.Move
import com.mikasa.chess.engine.Piece
import com.mikasa.chess.puzzle.Progress
import com.mikasa.chess.puzzle.Puzzle
import com.mikasa.chess.puzzle.PuzzleMode
import com.mikasa.chess.puzzle.PuzzleSession

/** Pantalla de resolución de puzzles. */
class PuzzleActivity : Activity() {

    private lateinit var mode: PuzzleMode
    private lateinit var progress: Progress
    private lateinit var puzzles: List<Puzzle>
    private var session: PuzzleSession? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Hay una animación o respuesta del rival en curso. */
    private var busy = false

    /** Ya se registró el resultado (con o sin rating) de este puzzle. */
    private var resultRecorded = false
    private var ratingDelta: Int? = null

    private lateinit var boardView: BoardView
    private lateinit var titleText: TextView
    private lateinit var badgeText: TextView
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var deltaText: TextView
    private lateinit var infoText: TextView
    private lateinit var sourceText: TextView
    private lateinit var movesText: TextView
    private lateinit var hintButton: Button
    private lateinit var solutionButton: Button
    private lateinit var retryButton: Button
    private lateinit var nextButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_puzzle)
        mode = intent.getStringExtra(EXTRA_MODE)
            ?.let { name -> PuzzleMode.values().firstOrNull { it.name == name } }
            ?: PuzzleMode.RATED
        progress = progress()
        puzzles = PuzzleRepository.all(this)

        boardView = findViewById(R.id.board)
        titleText = findViewById(R.id.title)
        badgeText = findViewById(R.id.badge)
        statusDot = findViewById(R.id.status_dot)
        statusTitle = findViewById(R.id.status_title)
        statusDetail = findViewById(R.id.status_detail)
        deltaText = findViewById(R.id.rating_delta)
        infoText = findViewById(R.id.puzzle_info)
        sourceText = findViewById(R.id.source)
        movesText = findViewById(R.id.moves)
        hintButton = findViewById(R.id.btn_hint)
        solutionButton = findViewById(R.id.btn_solution)
        retryButton = findViewById(R.id.btn_retry)
        nextButton = findViewById(R.id.btn_next)

        titleText.setText(mode.titleRes)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        boardView.listener = BoardView.Listener { from, to, animate -> onUserMove(from, to, animate) }
        hintButton.setOnClickListener { showHint() }
        solutionButton.setOnClickListener { showSolution() }
        retryButton.setOnClickListener { session?.let { load(it.puzzle) } }
        nextButton.setOnClickListener { loadNext() }

        val savedId = savedInstanceState?.getInt(STATE_PUZZLE, -1) ?: -1
        val puzzle = PuzzleRepository.byId(this, savedId) ?: mode.next(puzzles, progress, null)
        if (puzzle == null) {
            Toast.makeText(this, R.string.no_puzzles, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        load(puzzle)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        session?.let { outState.putInt(STATE_PUZZLE, it.puzzle.id) }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------- flujo

    private fun load(puzzle: Puzzle) {
        handler.removeCallbacksAndMessages(null)
        val s = PuzzleSession(puzzle)
        session = s
        resultRecorded = false
        ratingDelta = null
        busy = true

        boardView.playerIsWhite = s.playerIsWhite
        boardView.flipped = !s.playerIsWhite
        boardView.interactive = false
        boardView.clearMark()
        boardView.setPosition(s.board, null)

        setStatus(sideColor(!s.playerIsWhite), getString(R.string.status_opponent), "")
        updateInfo()
        updateMoves()
        updateButtons()

        handler.postDelayed({
            val move = s.playSetupMove()
            updateMoves()
            boardView.setPosition(s.board, move, animate = move) { onPlayerTurn(keepStatus = false) }
        }, SETUP_DELAY_MS)
    }

    private fun loadNext() {
        val current = session?.puzzle
        // "Saltar" en práctica un puzzle empezado y no resuelto no cuenta como fallo.
        val next = mode.next(puzzles, progress, current?.id)
        if (next == null) {
            Toast.makeText(this, R.string.no_puzzles_left, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        load(next)
    }

    private fun onPlayerTurn(keepStatus: Boolean) {
        val s = session ?: return
        busy = false
        boardView.interactive = true
        if (!keepStatus) showTurnStatus(s)
        updateButtons()
    }

    private fun showTurnStatus(s: PuzzleSession) {
        val title = getString(if (s.playerIsWhite) R.string.white_to_move else R.string.black_to_move)
        val left = s.playerMovesLeft
        val detail = if (left == 1) getString(R.string.find_mate_in_1) else getString(R.string.find_mate_in_n, left)
        setStatus(sideColor(s.playerIsWhite), title, detail)
    }

    private fun onUserMove(from: Int, to: Int, animate: Boolean) {
        val s = session ?: return
        if (busy || !s.isPlayerTurn) return
        val candidates = s.board.legalMoves().filter { it.from == from && it.to == to }
        when {
            candidates.isEmpty() -> return
            candidates.size == 1 -> playUserMove(candidates[0], animate)
            else -> askPromotion(candidates, animate)
        }
    }

    private fun askPromotion(candidates: List<Move>, animate: Boolean) {
        val types = intArrayOf(Piece.QUEEN, Piece.ROOK, Piece.BISHOP, Piece.KNIGHT)
        AlertDialog.Builder(this)
            .setTitle(R.string.promotion_title)
            .setItems(resources.getStringArray(R.array.promotion_pieces)) { _, which ->
                candidates.firstOrNull { it.promotion == types[which] }?.let { playUserMove(it, animate) }
            }
            .show()
    }

    private fun playUserMove(move: Move, animate: Boolean) {
        val s = session ?: return
        val before = s.board
        val shown = if (animate) move else null
        when (s.tryMove(move)) {
            PuzzleSession.Result.WRONG -> {
                busy = true
                boardView.interactive = false
                boardView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                boardView.setPosition(before.play(move), move, shown)
                boardView.mark(move.to, BoardView.MARK_BAD)
                registerFailure()
                setStatus(COLOR_BAD, getString(R.string.wrong_title), getString(R.string.wrong_detail))
                handler.postDelayed({
                    boardView.clearMark()
                    boardView.setPosition(s.board, s.lastMove)
                    onPlayerTurn(keepStatus = true)
                }, WRONG_DELAY_MS)
            }
            PuzzleSession.Result.CORRECT -> {
                busy = true
                boardView.interactive = false
                boardView.setPosition(s.board, move, shown)
                boardView.mark(move.to, BoardView.MARK_GOOD)
                setStatus(COLOR_GOOD, getString(R.string.correct_title), getString(R.string.correct_detail))
                updateMoves()
                updateButtons()
                handler.postDelayed({
                    boardView.clearMark()
                    val reply = s.playReply()
                    updateMoves()
                    boardView.setPosition(s.board, reply, animate = reply) { onPlayerTurn(keepStatus = false) }
                }, REPLY_DELAY_MS)
            }
            PuzzleSession.Result.SOLVED -> {
                boardView.setPosition(s.board, move, shown)
                boardView.mark(move.to, BoardView.MARK_GOOD)
                onSolved()
            }
        }
    }

    private fun onSolved() {
        val s = session ?: return
        busy = false
        boardView.interactive = false
        val clean = !s.failed
        progress.recordSolved(s.puzzle, clean)
        if (!resultRecorded) {
            resultRecorded = true
            if (mode.rated) ratingDelta = progress.recordRated(s.puzzle, clean)
        }
        if (clean) {
            setStatus(COLOR_GOOD, getString(R.string.solved_title), getString(R.string.solved_detail))
        } else {
            setStatus(COLOR_NEUTRAL, getString(R.string.completed_title), getString(R.string.completed_detail))
        }
        updateMoves()
        updateInfo()
        updateButtons()
    }

    /** Primer fallo del intento: cuenta para el rating y la racha. */
    private fun registerFailure() {
        val s = session ?: return
        if (resultRecorded) return
        resultRecorded = true
        progress.recordFailed(s.puzzle)
        if (mode.rated) ratingDelta = progress.recordRated(s.puzzle, false)
        updateInfo()
    }

    private fun showHint() {
        val s = session ?: return
        if (busy || !s.isPlayerTurn) return
        boardView.showHint(s.hint())
        registerFailure()
        setStatus(COLOR_HINT, getString(R.string.hint_title), getString(R.string.hint_detail))
    }

    private fun showSolution() {
        val s = session ?: return
        if (busy || s.isSolved) return
        busy = true
        boardView.interactive = false
        registerFailure()
        setStatus(COLOR_HINT, getString(R.string.solution_title), getString(R.string.solution_detail))
        updateButtons()
        playSolutionStep(s)
    }

    private fun playSolutionStep(s: PuzzleSession) {
        if (s !== session) return
        if (s.isSolved) {
            onSolved()
            return
        }
        handler.postDelayed({
            if (s === session) {
                val move = s.revealNext()
                updateMoves()
                boardView.setPosition(s.board, move, animate = move) { playSolutionStep(s) }
            }
        }, SOLUTION_STEP_MS)
    }

    // --------------------------------------------------------------- vista

    private fun setStatus(color: Int, title: String, detail: String) {
        statusDot.background.mutate().setTint(color)
        statusTitle.text = title
        statusDetail.text = detail
        statusDetail.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        showDelta()
    }

    private fun showDelta() {
        val delta = ratingDelta
        if (delta == null || delta == 0) {
            deltaText.visibility = View.GONE
        } else {
            deltaText.visibility = View.VISIBLE
            deltaText.text = if (delta > 0) "+$delta" else delta.toString()
            deltaText.setTextColor(if (delta > 0) COLOR_GOOD else COLOR_BAD)
        }
    }

    private fun updateInfo() {
        val s = session ?: return
        val p = s.puzzle
        badgeText.text = if (mode.rated) {
            progress.rating.toString()
        } else {
            val (solved, total) = mode.solvedCount(puzzles, progress)
            getString(R.string.count_solved, solved, total)
        }
        infoText.text = getString(R.string.puzzle_info, p.id, p.rating, p.mateIn)
        sourceText.visibility = if (p.isFamous) View.VISIBLE else View.GONE
        sourceText.text = p.source
        showDelta()
    }

    private fun updateMoves() {
        val text = session?.movesText().orEmpty()
        movesText.text = text
        movesText.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateButtons() {
        val s = session ?: return
        val finished = s.isSolved && !busy
        hintButton.visibility = if (finished) View.GONE else View.VISIBLE
        solutionButton.visibility = if (finished) View.GONE else View.VISIBLE
        retryButton.visibility = if (finished) View.VISIBLE else View.GONE
        nextButton.visibility = if (finished || !mode.rated) View.VISIBLE else View.GONE
        nextButton.setText(if (finished) R.string.next else R.string.skip)
        nextButton.setBackgroundResource(if (finished) R.drawable.bg_button_primary else R.drawable.bg_button_secondary)
    }

    private fun sideColor(white: Boolean) = if (white) Color.WHITE else Color.rgb(20, 20, 20)

    companion object {
        const val EXTRA_MODE = "mode"
        private const val STATE_PUZZLE = "puzzle_id"

        private const val SETUP_DELAY_MS = 650L
        private const val REPLY_DELAY_MS = 450L
        private const val WRONG_DELAY_MS = 900L
        private const val SOLUTION_STEP_MS = 700L

        private val COLOR_GOOD = Color.rgb(129, 182, 76)
        private val COLOR_BAD = Color.rgb(224, 90, 71)
        private val COLOR_HINT = Color.rgb(80, 160, 255)
        private val COLOR_NEUTRAL = Color.rgb(186, 186, 186)
    }
}
