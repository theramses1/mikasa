package com.mikasa.charadas

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private sealed class Item {
        data class Cat(val c: Category) : Item()
        object Create : Item()
    }

    private lateinit var adapter: Adapter
    private lateinit var search: EditText
    private lateinit var btnTime: MaterialButton
    private lateinit var btnMode: MaterialButton
    private lateinit var btnSound: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnTime = findViewById(R.id.btnTime)
        btnMode = findViewById(R.id.btnMode)
        btnSound = findViewById(R.id.btnSound)
        search = findViewById(R.id.search)

        btnTime.setOnClickListener {
            val d = CategoryRepository.DURATIONS
            val cur = d.indexOf(CategoryRepository.duration(this))
            CategoryRepository.setDuration(this, d[(cur + 1) % d.size])
            refreshSettings()
        }
        btnMode.setOnClickListener {
            val tilt = !CategoryRepository.tiltMode(this)
            CategoryRepository.setTiltMode(this, tilt)
            refreshSettings()
            Toast.makeText(
                this,
                if (tilt) "Inclina hacia abajo = correcto · hacia arriba = paso"
                else "Toca la derecha = correcto · la izquierda = paso",
                Toast.LENGTH_LONG,
            ).show()
        }
        btnSound.setOnClickListener {
            CategoryRepository.setSound(this, !CategoryRepository.sound(this))
            refreshSettings()
        }

        val grid = findViewById<RecyclerView>(R.id.grid)
        val span = if (resources.configuration.screenWidthDp >= 600) 3 else 2
        grid.layoutManager = GridLayoutManager(this, span)
        adapter = Adapter()
        grid.adapter = adapter

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = reload()
        })
        refreshSettings()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun refreshSettings() {
        btnTime.text = "⏱ ${CategoryRepository.duration(this)} s"
        btnMode.text = if (CategoryRepository.tiltMode(this)) "📱 Inclinar" else "👆 Tocar"
        btnSound.text = if (CategoryRepository.sound(this)) "🔊" else "🔇"
    }

    private fun reload() {
        val all = CategoryRepository.all(this)
        val total = all.sumOf { it.words.size }
        findViewById<TextView>(R.id.subtitle).text =
            "${all.size} categorías · $total palabras · todo gratis"
        val q = normalize(search.text.toString())
        val items = mutableListOf<Item>()
        if (q.isEmpty()) {
            items += Item.Cat(CategoryRepository.mix(this))
            items += Item.Create
        }
        all.filter { q.isEmpty() || normalize(it.name).contains(q) }.forEach { items += Item.Cat(it) }
        adapter.submit(items)
    }

    private fun normalize(s: String) = java.text.Normalizer.normalize(s.lowercase().trim(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")

    private fun play(c: Category) {
        if (c.words.isEmpty()) return
        startActivity(Intent(this, GameActivity::class.java).putExtra(GameActivity.EXTRA_CATEGORY, c.id))
    }

    private fun editDialog(existing: Category?) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val name = EditText(this).apply { hint = "Nombre de la categoría"; setText(existing?.name ?: "") }
        val emoji = EditText(this).apply { hint = "Emoji (opcional) ⭐"; setText(existing?.emoji ?: "") }
        val words = EditText(this).apply {
            hint = "Palabras: una por línea o separadas por comas"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 5; maxLines = 10
            setText(existing?.words?.joinToString("\n") ?: "")
        }
        box.addView(name); box.addView(emoji); box.addView(words)

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Nueva categoría" else "Editar categoría")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                val list = words.text.toString().split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                val n = name.text.toString().trim()
                if (n.isEmpty() || list.size < 3) {
                    Toast.makeText(this, "Pon un nombre y al menos 3 palabras", Toast.LENGTH_LONG).show()
                } else {
                    CategoryRepository.saveCustom(this, n, emoji.text.toString().trim(), list, existing?.id)
                    reload()
                }
            }
            .setNegativeButton("Cancelar", null)
        if (existing != null) {
            builder.setNeutralButton("Borrar") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setMessage("¿Borrar \"${existing.name}\"?")
                    .setPositiveButton("Borrar") { _, _ -> CategoryRepository.deleteCustom(this, existing.id); reload() }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
        builder.show()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        private var items: List<Item> = emptyList()

        fun submit(list: List<Item>) { items = list; notifyDataSetChanged() }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val card: View = v.findViewById(R.id.card)
            val emoji: TextView = v.findViewById(R.id.emoji)
            val name: TextView = v.findViewById(R.id.name)
            val count: TextView = v.findViewById(R.id.count)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val bg = GradientDrawable().apply { cornerRadius = 18 * resources.displayMetrics.density }
            when (val item = items[position]) {
                is Item.Cat -> {
                    val c = item.c
                    h.emoji.text = c.emoji
                    h.name.text = c.name
                    h.count.text = "${c.words.size} palabras" + if (c.custom) " · tuya ✏️" else ""
                    bg.setColor(c.color)
                    h.card.setOnClickListener { play(c) }
                    h.card.setOnLongClickListener {
                        if (c.custom) editDialog(c) else
                            Toast.makeText(this@MainActivity, c.words.shuffled().take(6).joinToString(", ") + "…", Toast.LENGTH_LONG).show()
                        true
                    }
                }
                Item.Create -> {
                    h.emoji.text = "➕"
                    h.name.text = "Crea tu categoría"
                    h.count.text = "con tus propias palabras"
                    bg.setColor(0xFF3A2A66.toInt())
                    bg.setStroke((2 * resources.displayMetrics.density).toInt(), 0xFFFFC83D.toInt(), 12f, 8f)
                    h.card.setOnClickListener { editDialog(null) }
                    h.card.setOnLongClickListener(null)
                }
            }
            h.card.background = bg
        }
    }
}
