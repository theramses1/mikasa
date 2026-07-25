package com.mikasa.stickers.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mikasa.stickers.StickerPackRepository
import com.mikasa.stickers.databinding.ItemStickerBinding
import com.mikasa.stickers.model.Sticker

class StickerGridAdapter(
    private val context: Context,
    private val packId: String,
    private var stickers: List<Sticker>,
    private val onClick: (Sticker) -> Unit
) : RecyclerView.Adapter<StickerGridAdapter.StickerViewHolder>() {

    fun submit(newStickers: List<Sticker>) {
        stickers = newStickers
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StickerViewHolder {
        val binding = ItemStickerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return StickerViewHolder(binding)
    }

    override fun getItemCount() = stickers.size

    override fun onBindViewHolder(holder: StickerViewHolder, position: Int) {
        holder.bind(stickers[position])
    }

    inner class StickerViewHolder(private val binding: ItemStickerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(sticker: Sticker) {
            val file = StickerPackRepository.stickerFile(context, packId, sticker.imageFileName)
            if (file.exists()) {
                binding.stickerImage.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
            } else {
                binding.stickerImage.setImageDrawable(null)
            }
            binding.stickerImage.setOnClickListener { onClick(sticker) }
        }
    }
}
