package com.mikasa.stickers.model

/**
 * A single sticker inside a pack.
 *
 * @param imageFileName file name of the WebP image, stored inside the pack directory.
 * @param emojis emojis associated with the sticker (WhatsApp allows up to 3).
 * @param accessibilityText short text describing the sticker for accessibility.
 */
data class Sticker(
    val imageFileName: String,
    val emojis: List<String> = emptyList(),
    val accessibilityText: String = ""
)
