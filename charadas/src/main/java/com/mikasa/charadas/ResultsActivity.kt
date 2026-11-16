package com.mikasa.charadas

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class ResultsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_WORDS = "words"
        const val EXTRA_RESULTS = "results"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val categoryId = intent.getStringExtra(GameActivity.EXTRA_CATEGORY) ?: ""
        val words = intent.getStringArrayListExtra(EXTRA_WORDS) ?: arrayListOf()
        val results = intent.getBooleanArrayExtra(EXTRA_RESULTS) ?: BooleanArray(0)
        val score = results.count { it }
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }

        fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

        root.addView(text("¡Se acabó el tiempo!", 18f, 0xFFB8ADD6.toInt()))
        root.addView(text(score.toString(), 96f, 0xFFFFC83D.toInt(), true))
        root.addView(text(
            when {
                score == 0 -> "¡Ups! A la próxima 😅"
                score == 1 -> "palabra adivinada"
                score < 5 -> "palabras adivinadas"
                score < 10 -> "palabras adivinadas · ¡muy bien! 🎉"
                else -> "palabras adivinadas · ¡son unas máquinas! 🔥"
            }, 16f, 0xFFFFFFFF.toInt()))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (16 * dp).toInt(), 0, 0) }
        words.forEachIndexed { i, w ->
            val ok = results.getOrElse(i) { false }
            list.addView(TextView(this).apply {
                text = (if (ok) "✓  " else "✗  ") + w
                textSize = 20f
                setTextColor(if (ok) 0xFF5BE38A.toInt() else 0xFF8C80AD.toInt())
                if (ok) setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            })
        }
        if (words.isEmpty()) list.addView(text("No salió ninguna palabra", 16f, 0xFF8C80AD.toInt()))

        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val again = MaterialButton(this).apply {
            text = "▶  Jugar otra vez"
            textSize = 18f
            setOnClickListener {
                startActivity(Intent(this@ResultsActivity, GameActivity::class.java)
                    .putExtra(GameActivity.EXTRA_CATEGORY, categoryId))
                finish()
            }
        }
        val back = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Elegir otra categoría"
            setOnClickListener { finish() }
        }
        root.addView(again, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (56 * dp).toInt()))
        root.addView(back, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (56 * dp).toInt()))

        setContentView(root)
    }
}
