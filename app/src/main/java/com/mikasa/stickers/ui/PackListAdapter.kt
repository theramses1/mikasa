package com.mikasa.stickers.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mikasa.stickers.StickerPackRepository
import com.mikasa.stickers.databinding.ItemStickerPackBinding
import com.mikasa.stickers.model.StickerPack

class PackListAdapter(
    private var packs: List<StickerPack>,
    private val onEdit: (StickerPack) -> Unit,
    private val onAddToWhatsApp: (StickerPack) -> Unit
) : RecyclerView.Adapter<PackListAdapter.PackViewHolder>() {

    fun submit(newPacks: List<StickerPack>) {
        packs = newPacks
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PackViewHolder {
        val binding = ItemStickerPackBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PackViewHolder(binding)
    }

    override fun getItemCount() = packs.size

    override fun onBindViewHolder(holder: PackViewHolder, position: Int) {
        holder.bind(packs[position])
    }

    inner class PackViewHolder(private val binding: ItemStickerPackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(pack: StickerPack) {
            val context = binding.root.context
            binding.packName.text = pack.name
            val stickerCount = pack.stickers.size
            binding.packInfo.text = "$stickerCount stickers · ${pack.publisher}"

            val trayFile = StickerPackRepository.stickerFile(
                context, pack.identifier, pack.trayImageFileName
            )
            if (trayFile.exists()) {
                binding.trayIcon.setImageBitmap(BitmapFactory.decodeFile(trayFile.absolutePath))
            } else {
                binding.trayIcon.setImageDrawable(null)
            }

            binding.btnEdit.setOnClickListener { onEdit(pack) }
            binding.btnAddToWhatsApp.isEnabled = pack.isValidForWhatsApp
            binding.btnAddToWhatsApp.setOnClickListener { onAddToWhatsApp(pack) }
            binding.root.setOnClickListener { onEdit(pack) }
        }
    }
}
