package com.mikasa.stickers.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.mikasa.stickers.BuildConfig
import com.mikasa.stickers.model.StickerPack

/**
 * Helpers to talk to WhatsApp: build the "add sticker pack" intent and check
 * whether a pack has already been added (whitelisted).
 */
object WhatsAppHelper {

    const val CONSUMER_PACKAGE = "com.whatsapp"
    const val BUSINESS_PACKAGE = "com.whatsapp.w4b"

    private const val ENABLE_ACTION = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
    private const val EXTRA_PACK_ID = "sticker_pack_id"
    private const val EXTRA_AUTHORITY = "sticker_pack_authority"
    private const val EXTRA_PACK_NAME = "sticker_pack_name"

    private const val AUTHORITY_QUERY_PARAM = "authority"
    private const val IDENTIFIER_QUERY_PARAM = "identifier"
    private const val QUERY_PATH = "is_whitelisted"
    private const val QUERY_RESULT_COLUMN = "result"

    fun isWhatsAppInstalled(context: Context): Boolean =
        isInstalled(context, CONSUMER_PACKAGE) || isInstalled(context, BUSINESS_PACKAGE)

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Intent that asks WhatsApp to add [pack] to the user's stickers. */
    fun createAddToWhatsAppIntent(pack: StickerPack): Intent = Intent().apply {
        action = ENABLE_ACTION
        putExtra(EXTRA_PACK_ID, pack.identifier)
        putExtra(EXTRA_AUTHORITY, BuildConfig.CONTENT_PROVIDER_AUTHORITY)
        putExtra(EXTRA_PACK_NAME, pack.name)
    }

    /**
     * Returns true if [identifier] is already added in at least one installed
     * WhatsApp flavour.
     */
    fun isPackAdded(context: Context, identifier: String): Boolean {
        var addedSomewhere = false
        var installedSomewhere = false
        for (pkg in listOf(CONSUMER_PACKAGE, BUSINESS_PACKAGE)) {
            if (!isInstalled(context, pkg)) continue
            installedSomewhere = true
            if (isWhitelisted(context, pkg, identifier)) addedSomewhere = true
        }
        return installedSomewhere && addedSomewhere
    }

    private fun isWhitelisted(context: Context, pkg: String, identifier: String): Boolean {
        return try {
            val authority = "$pkg.provider.sticker_whitelist_check"
            val uri = android.net.Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendPath(QUERY_PATH)
                .appendQueryParameter(AUTHORITY_QUERY_PARAM, BuildConfig.CONTENT_PROVIDER_AUTHORITY)
                .appendQueryParameter(IDENTIFIER_QUERY_PARAM, identifier)
                .build()
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(QUERY_RESULT_COLUMN)
                    if (index >= 0) return cursor.getInt(index) == 1
                }
                false
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
}
