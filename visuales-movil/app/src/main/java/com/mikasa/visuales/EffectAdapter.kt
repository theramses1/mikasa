package com.mikasa.visuales

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Carrusel horizontal con los nombres de los efectos. */
class EffectAdapter(private val onPick: (Int) -> Unit) : RecyclerView.Adapter<EffectAdapter.VH>() {

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    var selected = 0
        set(value) {
            val old = field
            field = value
            notifyItemChanged(old)
            notifyItemChanged(value)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_effect, parent, false) as TextView
        return VH(v)
    }

    override fun getItemCount() = Shaders.EFFECTS.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.text = Shaders.EFFECTS[position].name
        holder.text.isSelected = position == selected
        holder.text.setOnClickListener { onPick(holder.bindingAdapterPosition) }
    }
}
