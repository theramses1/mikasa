package com.mikasa.stickers.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mikasa.stickers.StickerPackRepository
import com.mikasa.stickers.databinding.ActivityMainBinding
import com.mikasa.stickers.databinding.DialogCreatePackBinding
import com.mikasa.stickers.model.StickerPack
import com.mikasa.stickers.util.WhatsAppHelper
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: PackListAdapter

    private val addToWhatsAppLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Toast.makeText(this, com.mikasa.stickers.R.string.added_to_whatsapp, Toast.LENGTH_SHORT).show()
            }
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = PackListAdapter(emptyList(), ::openPack, ::addToWhatsApp)
        binding.packList.layoutManager = LinearLayoutManager(this)
        binding.packList.adapter = adapter

        binding.fabCreate.setOnClickListener { showCreatePackDialog() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val packs = StickerPackRepository.getPacks(this)
        adapter.submit(packs)
        binding.emptyState.visibility = if (packs.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showCreatePackDialog() {
        val dialogBinding = DialogCreatePackBinding.inflate(LayoutInflater.from(this))
        MaterialAlertDialogBuilder(this)
            .setTitle(com.mikasa.stickers.R.string.dialog_new_pack)
            .setView(dialogBinding.root)
            .setNegativeButton(com.mikasa.stickers.R.string.cancel, null)
            .setPositiveButton(com.mikasa.stickers.R.string.create) { _, _ ->
                val name = dialogBinding.inputName.text?.toString()?.trim().orEmpty()
                val publisher = dialogBinding.inputPublisher.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(this, com.mikasa.stickers.R.string.name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val pack = StickerPack(
                    identifier = UUID.randomUUID().toString(),
                    name = name,
                    publisher = publisher.ifEmpty { getString(com.mikasa.stickers.R.string.app_name) }
                )
                StickerPackRepository.addPack(this, pack)
                openPack(pack)
            }
            .show()
    }

    private fun openPack(pack: StickerPack) {
        val intent = Intent(this, StickerPackDetailActivity::class.java)
        intent.putExtra(StickerPackDetailActivity.EXTRA_IDENTIFIER, pack.identifier)
        startActivity(intent)
    }

    private fun addToWhatsApp(pack: StickerPack) {
        if (!WhatsAppHelper.isWhatsAppInstalled(this)) {
            Toast.makeText(this, com.mikasa.stickers.R.string.whatsapp_not_installed, Toast.LENGTH_LONG).show()
            return
        }
        if (!pack.isValidForWhatsApp) {
            Toast.makeText(this, com.mikasa.stickers.R.string.need_min_stickers, Toast.LENGTH_LONG).show()
            return
        }
        try {
            addToWhatsAppLauncher.launch(WhatsAppHelper.createAddToWhatsAppIntent(pack))
        } catch (e: Exception) {
            Toast.makeText(this, com.mikasa.stickers.R.string.add_failed, Toast.LENGTH_LONG).show()
        }
    }
}
