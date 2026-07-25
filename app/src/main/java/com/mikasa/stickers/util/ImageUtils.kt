package com.mikasa.stickers.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Turns arbitrary user images into WhatsApp-compliant sticker assets.
 *
 * WhatsApp requirements handled here:
 *  - static stickers: 512x512 WebP, transparent background, <= 100 KB,
 *  - tray icon: 96x96 PNG, <= 50 KB.
 */
object ImageUtils {

    const val STICKER_SIZE = 512
    const val TRAY_SIZE = 96
    private const val MAX_STICKER_BYTES = 100 * 1024
    private const val MAX_TRAY_BYTES = 50 * 1024

    /**
     * Decodes [uri], scales it to fit a 512x512 transparent canvas keeping the
     * aspect ratio, and writes a compliant WebP to [destination].
     */
    fun createStickerWebp(context: Context, uri: Uri, destination: File): Boolean {
        val source = decodeBitmap(context, uri) ?: return false
        val squared = fitCenter(source, STICKER_SIZE)
        if (squared != source) source.recycle()
        val ok = compressWebp(squared, destination, MAX_STICKER_BYTES)
        squared.recycle()
        return ok
    }

    /** Generates the 96x96 PNG tray icon from an existing sticker image file. */
    fun createTrayFromSticker(stickerFile: File, trayFile: File): Boolean {
        val source = BitmapFactory.decodeFile(stickerFile.absolutePath) ?: return false
        val tray = fitCenter(source, TRAY_SIZE)
        if (tray != source) source.recycle()
        val ok = compressPng(tray, trayFile, MAX_TRAY_BYTES)
        tray.recycle()
        return ok
    }

    // --- internals -------------------------------------------------------

    private fun decodeBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            // First pass: read bounds so we can downsample large photos.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, STICKER_SIZE * 2)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null
            applyExifRotation(context, uri, bitmap)
        } catch (e: Exception) {
            null
        }
    }

    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val orientation = context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: return bitmap
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bitmap
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (e: Exception) {
            bitmap
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= target && h / 2 >= target) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    /** Scales [src] to fit a [size]x[size] transparent square, centered. */
    private fun fitCenter(src: Bitmap, size: Int): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val scale = minOf(size.toFloat() / src.width, size.toFloat() / src.height)
        val drawW = src.width * scale
        val drawH = src.height * scale
        val left = (size - drawW) / 2f
        val top = (size - drawH) / 2f
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(left, top)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        canvas.drawBitmap(src, matrix, paint)
        return output
    }

    private fun compressWebp(bitmap: Bitmap, file: File, maxBytes: Int): Boolean {
        var quality = 100
        var bytes = compressWebpToBytes(bitmap, quality)
        while (bytes.size > maxBytes && quality > 10) {
            quality -= 10
            bytes = compressWebpToBytes(bitmap, quality)
        }
        return try {
            FileOutputStream(file).use { it.write(bytes) }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun compressWebpToBytes(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
        } else {
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, quality, out)
        }
        return out.toByteArray()
    }

    private fun compressPng(bitmap: Bitmap, file: File, maxBytes: Int): Boolean {
        // PNG is lossless; if it exceeds the budget, fall back to a smaller WebP-quality
        // re-encode is not allowed for the tray (must be PNG), so we just shrink further.
        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            var bytes = out.toByteArray()
            if (bytes.size > maxBytes) {
                // Downscale the tray a bit more until it fits the 50 KB budget.
                var scaled = bitmap
                while (bytes.size > maxBytes && scaled.width > 32) {
                    val smaller = Bitmap.createScaledBitmap(
                        scaled, scaled.width * 3 / 4, scaled.height * 3 / 4, true
                    )
                    if (scaled != bitmap) scaled.recycle()
                    scaled = smaller
                    val o = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.PNG, 100, o)
                    bytes = o.toByteArray()
                }
                if (scaled != bitmap) scaled.recycle()
            }
            FileOutputStream(file).use { it.write(bytes) }
            true
        } catch (e: Exception) {
            false
        }
    }
}
