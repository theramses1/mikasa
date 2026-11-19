package com.mikasa.visuales

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Guarda fotos y videos en la galería (Imágenes/Visuales y Películas/Visuales). */
object MediaSaver {

    private const val FOLDER = "Visuales"

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun savePhoto(context: Context, bitmap: Bitmap): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "visuales_${stamp()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(dir, "visuales_${stamp()}.jpg").absolutePath)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            finish(context, uri)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    fun saveVideo(context: Context, file: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER)
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Video.Media.DATA, File(dir, file.name).absolutePath)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            finish(context, uri)
            file.delete()
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    fun newVideoFile(context: Context) = File(context.cacheDir, "visuales_${stamp()}.mp4")

    private fun finish(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
    }
}
