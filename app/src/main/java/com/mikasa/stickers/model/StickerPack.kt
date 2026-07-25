package com.mikasa.stickers.model

/**
 * A sticker pack, mirroring the structure WhatsApp expects in `contents.json`.
 *
 * WhatsApp rules enforced by the app:
 *  - a pack must contain between [MIN_STICKERS] and [MAX_STICKERS] stickers to be
 *    addable to WhatsApp,
 *  - each static sticker must be a 512x512 WebP no larger than 100 KB,
 *  - the tray icon must be a 96x96 PNG no larger than 50 KB.
 */
data class StickerPack(
    val identifier: String,
    var name: String,
    var publisher: String,
    var trayImageFileName: String = TRAY_FILE_NAME,
    var imageDataVersion: String = "1",
    var avoidCache: Boolean = false,
    var animatedStickerPack: Boolean = false,
    var publisherEmail: String = "",
    var publisherWebsite: String = "",
    var privacyPolicyWebsite: String = "",
    var licenseAgreementWebsite: String = "",
    val stickers: MutableList<Sticker> = mutableListOf()
) {
    val isValidForWhatsApp: Boolean
        get() = stickers.size in MIN_STICKERS..MAX_STICKERS

    companion object {
        const val MIN_STICKERS = 3
        const val MAX_STICKERS = 30
        const val TRAY_FILE_NAME = "tray.png"
    }
}
