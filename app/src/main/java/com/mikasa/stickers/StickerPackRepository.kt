package com.mikasa.stickers

import android.content.Context
import com.mikasa.stickers.model.Sticker
import com.mikasa.stickers.model.StickerPack
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Single source of truth for sticker packs.
 *
 * Packs live on disk under `filesDir/stickerpacks/`:
 *
 *   stickerpacks/
 *     contents.json                 <- metadata for every pack (WhatsApp-compatible)
 *     <identifier>/
 *       tray.png
 *       <sticker>.webp
 *
 * Both the UI and the [StickerContentProvider] read through this repository so
 * WhatsApp always sees the same data the user edits.
 */
object StickerPackRepository {

    private const val ROOT_DIR = "stickerpacks"
    private const val CONTENTS_FILE = "contents.json"

    @Volatile
    private var cache: MutableList<StickerPack>? = null

    fun rootDir(context: Context): File =
        File(context.filesDir, ROOT_DIR).apply { if (!exists()) mkdirs() }

    fun packDir(context: Context, identifier: String): File =
        File(rootDir(context), identifier).apply { if (!exists()) mkdirs() }

    fun stickerFile(context: Context, identifier: String, fileName: String): File =
        File(packDir(context, identifier), fileName)

    @Synchronized
    fun getPacks(context: Context): List<StickerPack> {
        cache?.let { return it }
        val loaded = load(context)
        cache = loaded
        return loaded
    }

    fun getPack(context: Context, identifier: String): StickerPack? =
        getPacks(context).firstOrNull { it.identifier == identifier }

    @Synchronized
    fun addPack(context: Context, pack: StickerPack) {
        val packs = getPacks(context).toMutableList()
        packs.add(pack)
        cache = packs
        packDir(context, pack.identifier)
        save(context)
    }

    @Synchronized
    fun updatePack(context: Context, pack: StickerPack) {
        val packs = getPacks(context).toMutableList()
        val index = packs.indexOfFirst { it.identifier == pack.identifier }
        if (index >= 0) {
            // Bump data version so WhatsApp re-reads the images.
            pack.imageDataVersion = ((pack.imageDataVersion.toIntOrNull() ?: 1) + 1).toString()
            packs[index] = pack
            cache = packs
            save(context)
        }
    }

    @Synchronized
    fun deletePack(context: Context, identifier: String) {
        val packs = getPacks(context).toMutableList()
        packs.removeAll { it.identifier == identifier }
        cache = packs
        packDir(context, identifier).deleteRecursively()
        save(context)
    }

    // --- persistence -----------------------------------------------------

    private fun load(context: Context): MutableList<StickerPack> {
        val file = File(rootDir(context), CONTENTS_FILE)
        if (!file.exists()) return mutableListOf()
        return try {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("sticker_packs") ?: JSONArray()
            val result = mutableListOf<StickerPack>()
            for (i in 0 until array.length()) {
                result.add(parsePack(array.getJSONObject(i)))
            }
            result
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun save(context: Context) {
        val packs = cache ?: return
        val root = JSONObject()
        root.put("android_play_store_link", "")
        root.put("ios_app_download_link", "")
        val array = JSONArray()
        packs.forEach { array.put(serializePack(it)) }
        root.put("sticker_packs", array)
        File(rootDir(context), CONTENTS_FILE).writeText(root.toString(2))
    }

    private fun serializePack(pack: StickerPack): JSONObject {
        val obj = JSONObject()
        obj.put("identifier", pack.identifier)
        obj.put("name", pack.name)
        obj.put("publisher", pack.publisher)
        obj.put("tray_image_file", pack.trayImageFileName)
        obj.put("image_data_version", pack.imageDataVersion)
        obj.put("avoid_cache", pack.avoidCache)
        obj.put("animated_sticker_pack", pack.animatedStickerPack)
        obj.put("publisher_email", pack.publisherEmail)
        obj.put("publisher_website", pack.publisherWebsite)
        obj.put("privacy_policy_website", pack.privacyPolicyWebsite)
        obj.put("license_agreement_website", pack.licenseAgreementWebsite)
        val stickers = JSONArray()
        pack.stickers.forEach { sticker ->
            val s = JSONObject()
            s.put("image_file", sticker.imageFileName)
            val emojis = JSONArray()
            sticker.emojis.forEach { emojis.put(it) }
            s.put("emojis", emojis)
            s.put("accessibility_text", sticker.accessibilityText)
            stickers.put(s)
        }
        obj.put("stickers", stickers)
        return obj
    }

    private fun parsePack(obj: JSONObject): StickerPack {
        val stickers = mutableListOf<Sticker>()
        val stickerArray = obj.optJSONArray("stickers") ?: JSONArray()
        for (i in 0 until stickerArray.length()) {
            val s = stickerArray.getJSONObject(i)
            val emojiArray = s.optJSONArray("emojis") ?: JSONArray()
            val emojis = mutableListOf<String>()
            for (j in 0 until emojiArray.length()) emojis.add(emojiArray.getString(j))
            stickers.add(
                Sticker(
                    imageFileName = s.getString("image_file"),
                    emojis = emojis,
                    accessibilityText = s.optString("accessibility_text", "")
                )
            )
        }
        return StickerPack(
            identifier = obj.getString("identifier"),
            name = obj.getString("name"),
            publisher = obj.getString("publisher"),
            trayImageFileName = obj.optString("tray_image_file", StickerPack.TRAY_FILE_NAME),
            imageDataVersion = obj.optString("image_data_version", "1"),
            avoidCache = obj.optBoolean("avoid_cache", false),
            animatedStickerPack = obj.optBoolean("animated_sticker_pack", false),
            publisherEmail = obj.optString("publisher_email", ""),
            publisherWebsite = obj.optString("publisher_website", ""),
            privacyPolicyWebsite = obj.optString("privacy_policy_website", ""),
            licenseAgreementWebsite = obj.optString("license_agreement_website", ""),
            stickers = stickers
        )
    }
}
