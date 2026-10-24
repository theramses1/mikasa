package com.mikasa.chess.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.mikasa.chess.R
import com.mikasa.chess.data.PuzzleRepository
import com.mikasa.chess.data.progress
import com.mikasa.chess.puzzle.PuzzleMode

/** Pantalla de inicio: rating, estadísticas y modos de juego. */
class MainActivity : Activity() {

    private lateinit var ratingText: TextView
    private lateinit var statsText: TextView
    private lateinit var modesContainer: LinearLayout
    private val pieceFont: Typeface by lazy {
        Typeface.createFromAsset(assets, "fonts/chess_pieces.ttf")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ratingText = findViewById(R.id.rating)
        statsText = findViewById(R.id.stats)
        modesContainer = findViewById(R.id.modes)
        findViewById<TextView>(R.id.reset).setOnClickListener { confirmReset() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val progress = progress()
        val puzzles = PuzzleRepository.all(this)
        ratingText.text = progress.rating.toString()
        statsText.text = getString(
            R.string.stats_format,
            progress.solved.size,
            progress.streak,
            progress.bestStreak,
            progress.bestRating,
        )

        modesContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (mode in PuzzleMode.values()) {
            val (solved, total) = mode.solvedCount(puzzles, progress)
            if (mode == PuzzleMode.REVIEW && total == 0) continue
            val item = inflater.inflate(R.layout.item_mode, modesContainer, false)
            item.findViewById<TextView>(R.id.mode_icon).apply {
                typeface = pieceFont
                text = mode.glyph
            }
            item.findViewById<TextView>(R.id.mode_title).setText(mode.titleRes)
            item.findViewById<TextView>(R.id.mode_subtitle).setText(mode.subtitleRes)
            item.findViewById<TextView>(R.id.mode_count).text = when (mode) {
                PuzzleMode.RATED -> getString(R.string.count_total, total)
                PuzzleMode.REVIEW -> getString(R.string.count_pending, total)
                else -> getString(R.string.count_solved, solved, total)
            }
            item.setOnClickListener {
                startActivity(
                    Intent(this, PuzzleActivity::class.java).putExtra(PuzzleActivity.EXTRA_MODE, mode.name),
                )
            }
            modesContainer.addView(item)
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_title)
            .setMessage(R.string.reset_message)
            .setPositiveButton(R.string.reset_confirm) { _, _ ->
                progress().reset()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
