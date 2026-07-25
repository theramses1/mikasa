package com.mikasa.stickers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.mikasa.stickers.model.StickerPack

/**
 * Exposes the user's sticker packs to WhatsApp.
 *
 * WhatsApp reads packs through four URIs:
 *   content://<authority>/metadata                          -> every pack
 *   content://<authority>/metadata/<identifier>             -> a single pack
 *   content://<authority>/stickers/<identifier>             -> stickers in a pack
 *   content://<authority>/stickers_asset/<identifier>/<f>   -> a sticker/tray image file
 *
 * Files are served from internal storage (see [StickerPackRepository]) rather
 * than from bundled assets, because packs here are created by the user.
 */
class StickerContentProvider : ContentProvider() {

    private lateinit var matcher: UriMatcher
    private lateinit var authority: String

    override fun onCreate(): Boolean {
        authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, METADATA, CODE_METADATA_ALL)
            addURI(authority, "$METADATA/*", CODE_METADATA_SINGLE)
            addURI(authority, "$STICKERS/*", CODE_STICKERS)
            addURI(authority, "$STICKERS_ASSET/*/*", CODE_STICKER_ASSET)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val context = context ?: return null
        return when (matcher.match(uri)) {
            CODE_METADATA_ALL ->
                metadataCursor(uri, StickerPackRepository.getPacks(context))
            CODE_METADATA_SINGLE -> {
                val identifier = uri.lastPathSegment
                val pack = StickerPackRepository.getPack(context, identifier ?: return null)
                metadataCursor(uri, listOfNotNull(pack))
            }
            CODE_STICKERS -> {
                val identifier = uri.lastPathSegment ?: return null
                stickersCursor(uri, StickerPackRepository.getPack(context, identifier))
            }
            else -> null
        }
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        CODE_METADATA_ALL -> "vnd.android.cursor.dir/vnd.$authority.$METADATA"
        CODE_METADATA_SINGLE -> "vnd.android.cursor.item/vnd.$authority.$METADATA"
        CODE_STICKERS -> "vnd.android.cursor.dir/vnd.$authority.$STICKERS"
        CODE_STICKER_ASSET -> {
            val name = uri.lastPathSegment ?: ""
            if (name.endsWith(".png")) "image/png" else "image/webp"
        }
        else -> null
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (matcher.match(uri) != CODE_STICKER_ASSET) return null
        val context = context ?: return null
        val segments = uri.pathSegments
        // .../stickers_asset/<identifier>/<fileName>
        if (segments.size < 3) return null
        val identifier = segments[segments.size - 2]
        val fileName = segments[segments.size - 1]
        val file = StickerPackRepository.stickerFile(context, identifier, fileName)
        if (!file.exists()) return null
        return try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        } catch (e: Exception) {
            null
        }
    }

    // Content provider is read-only from WhatsApp's perspective.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0

    // --- cursors ---------------------------------------------------------

    private fun metadataCursor(uri: Uri, packs: List<StickerPack>): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                "sticker_pack_identifier",
                "sticker_pack_name",
                "sticker_pack_publisher",
                "sticker_pack_icon",
                "android_play_store_link",
                "ios_app_download_link",
                "sticker_pack_publisher_email",
                "sticker_pack_publisher_website",
                "sticker_pack_privacy_policy_website",
                "sticker_pack_license_agreement_website",
                "image_data_version",
                "whatsapp_will_not_cache_stickers",
                "animated_sticker_pack"
            )
        )
        packs.forEach { pack ->
            cursor.addRow(
                arrayOf<Any?>(
                    pack.identifier,
                    pack.name,
                    pack.publisher,
                    pack.trayImageFileName,
                    "",
                    "",
                    pack.publisherEmail,
                    pack.publisherWebsite,
                    pack.privacyPolicyWebsite,
                    pack.licenseAgreementWebsite,
                    pack.imageDataVersion,
                    if (pack.avoidCache) 1 else 0,
                    if (pack.animatedStickerPack) 1 else 0
                )
            )
        }
        cursor.setNotificationUri(context!!.contentResolver, uri)
        return cursor
    }

    private fun stickersCursor(uri: Uri, pack: StickerPack?): Cursor {
        val cursor = MatrixCursor(
            arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text")
        )
        pack?.stickers?.forEach { sticker ->
            cursor.addRow(
                arrayOf<Any?>(
                    sticker.imageFileName,
                    sticker.emojis.joinToString(","),
                    sticker.accessibilityText
                )
            )
        }
        cursor.setNotificationUri(context!!.contentResolver, uri)
        return cursor
    }

    companion object {
        private const val METADATA = "metadata"
        private const val STICKERS = "stickers"
        private const val STICKERS_ASSET = "stickers_asset"

        private const val CODE_METADATA_ALL = 1
        private const val CODE_METADATA_SINGLE = 2
        private const val CODE_STICKERS = 3
        private const val CODE_STICKER_ASSET = 4

        /** Builds the asset URI WhatsApp uses to fetch an image file. */
        fun assetUri(context: Context, identifier: String, fileName: String): Uri =
            Uri.parse(
                "content://${BuildConfig.CONTENT_PROVIDER_AUTHORITY}/$STICKERS_ASSET/$identifier/$fileName"
            )
    }
}
