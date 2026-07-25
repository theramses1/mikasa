package com.mikasa.stickers.ui

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mikasa.stickers.R
import com.mikasa.stickers.StickerPackRepository
import com.mikasa.stickers.databinding.ActivityStickerPackDetailBinding
import com.mikasa.stickers.model.Sticker
import com.mikasa.stickers.model.StickerPack
import com.mikasa.stickers.util.ImageUtils
import com.mikasa.stickers.util.WhatsAppHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class StickerPackDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStickerPackDetailBinding
    private lateinit var adapter: StickerGridAdapter
    private lateinit var pack: StickerPack

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) processImage(uri)
        }

    private val addToWhatsAppLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStickerPackDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val identifier = intent.getStringExtra(EXTRA_IDENTIFIER)
        val loaded = identifier?.let { StickerPackRepository.getPack(this, it) }
        if (loaded == null) {
            finish()
            return
        }
        pack = loaded

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = pack.name
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = StickerGridAdapter(this, pack.identifier, pack.stickers, ::onStickerClicked)
        binding.stickerGrid.layoutManager = GridLayoutManager(this, 3)
        binding.stickerGrid.adapter = adapter

        binding.fabAddSticker.setOnClickListener { onAddStickerClicked() }
        refresh()
    }

    private fun refresh() {
        // Re-read the fresh copy in case something changed elsewhere.
        StickerPackRepository.getPack(this, pack.identifier)?.let { pack = it }
        adapter.submit(pack.stickers)
        supportActionBar?.title = pack.name
        val count = pack.stickers.size
        binding.statusText.text = if (pack.isValidForWhatsApp) {
            getString(R.string.status_ready, count)
        } else {
            getString(R.string.status_need_more, count, (StickerPack.MIN_STICKERS - count).coerceAtLeast(0))
        }
    }

    private fun onAddStickerClicked() {
        if (pack.stickers.size >= StickerPack.MAX_STICKERS) {
            Toast.makeText(this, R.string.max_stickers_reached, Toast.LENGTH_SHORT).show()
            return
        }
        pickImage.launch(
            PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                .build()
        )
    }

    private fun processImage(uri: Uri) {
        val progress = MaterialAlertDialogBuilder(this)
            .setView(android.widget.ProgressBar(this).apply { setPadding(48, 48, 48, 48) })
            .setMessage(R.string.processing)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val fileName = "${UUID.randomUUID()}.webp"
            val destination = StickerPackRepository.stickerFile(this@StickerPackDetailActivity, pack.identifier, fileName)
            val ok = withContext(Dispatchers.IO) {
                val created = ImageUtils.createStickerWebp(this@StickerPackDetailActivity, uri, destination)
                if (created && (pack.stickers.isEmpty() || !trayExists())) {
                    val trayFile = StickerPackRepository.stickerFile(
                        this@StickerPackDetailActivity, pack.identifier, pack.trayImageFileName
                    )
                    ImageUtils.createTrayFromSticker(destination, trayFile)
                }
                created
            }
            progress.dismiss()
            if (ok) {
                pack.stickers.add(Sticker(imageFileName = fileName))
                StickerPackRepository.updatePack(this@StickerPackDetailActivity, pack)
                refresh()
            } else {
                Toast.makeText(this@StickerPackDetailActivity, R.string.image_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun trayExists(): Boolean =
        StickerPackRepository.stickerFile(this, pack.identifier, pack.trayImageFileName).exists()

    private fun onStickerClicked(sticker: Sticker) {
        val options = arrayOf(
            getString(R.string.set_as_tray),
            getString(R.string.edit_emojis),
            getString(R.string.delete_sticker)
        )
        MaterialAlertDialogBuilder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> setAsTray(sticker)
                    1 -> editEmojis(sticker)
                    2 -> confirmDeleteSticker(sticker)
                }
            }
            .show()
    }

    private fun setAsTray(sticker: Sticker) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val stickerFile = StickerPackRepository.stickerFile(this@StickerPackDetailActivity, pack.identifier, sticker.imageFileName)
                val trayFile = StickerPackRepository.stickerFile(this@StickerPackDetailActivity, pack.identifier, pack.trayImageFileName)
                ImageUtils.createTrayFromSticker(stickerFile, trayFile)
            }
            StickerPackRepository.updatePack(this@StickerPackDetailActivity, pack)
            refresh()
        }
    }

    private fun editEmojis(sticker: Sticker) {
        val input = EditText(this).apply {
            setText(sticker.emojis.joinToString(" "))
            hint = getString(R.string.emojis_optional)
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_emojis)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val emojis = input.text.toString().trim()
                    .split(Regex("\\s+"))
                    .filter { it.isNotEmpty() }
                    .take(3)
                val index = pack.stickers.indexOfFirst { it.imageFileName == sticker.imageFileName }
                if (index >= 0) {
                    pack.stickers[index] = sticker.copy(emojis = emojis)
                    StickerPackRepository.updatePack(this, pack)
                    refresh()
                }
            }
            .show()
    }

    private fun confirmDeleteSticker(sticker: Sticker) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_sticker)
            .setMessage(R.string.delete_sticker_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                StickerPackRepository.stickerFile(this, pack.identifier, sticker.imageFileName).delete()
                pack.stickers.removeAll { it.imageFileName == sticker.imageFileName }
                StickerPackRepository.updatePack(this, pack)
                refresh()
            }
            .show()
    }

    // --- toolbar menu ----------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_ADD_WA, 0, R.string.add_to_whatsapp)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_EDIT, 1, R.string.edit_pack)
        menu.add(0, MENU_DELETE, 2, R.string.delete_pack)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_ADD_WA -> { addToWhatsApp(); true }
            MENU_EDIT -> { editPackInfo(); true }
            MENU_DELETE -> { confirmDeletePack(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun editPackInfo() {
        val dialogBinding = com.mikasa.stickers.databinding.DialogCreatePackBinding.inflate(layoutInflater)
        dialogBinding.inputName.setText(pack.name)
        dialogBinding.inputPublisher.setText(pack.publisher)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_pack)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = dialogBinding.inputName.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pack.name = name
                pack.publisher = dialogBinding.inputPublisher.text?.toString()?.trim()
                    ?.ifEmpty { getString(R.string.app_name) } ?: pack.publisher
                StickerPackRepository.updatePack(this, pack)
                refresh()
            }
            .show()
    }

    private fun confirmDeletePack() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_pack)
            .setMessage(R.string.delete_pack_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                StickerPackRepository.deletePack(this, pack.identifier)
                finish()
            }
            .show()
    }

    private fun addToWhatsApp() {
        if (!WhatsAppHelper.isWhatsAppInstalled(this)) {
            Toast.makeText(this, R.string.whatsapp_not_installed, Toast.LENGTH_LONG).show()
            return
        }
        if (!pack.isValidForWhatsApp) {
            Toast.makeText(this, R.string.need_min_stickers, Toast.LENGTH_LONG).show()
            return
        }
        try {
            addToWhatsAppLauncher.launch(WhatsAppHelper.createAddToWhatsAppIntent(pack))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.add_failed, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_IDENTIFIER = "extra_identifier"
        private const val MENU_ADD_WA = 1
        private const val MENU_EDIT = 2
        private const val MENU_DELETE = 3
    }
}
