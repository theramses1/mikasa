package com.mikasa.visuales

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Una letra: la de LRCLIB o la pegada a mano. */
data class Track(
    val artistName: String,
    val trackName: String,
    val duration: Double = 0.0,
    val instrumental: Boolean = false,
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
) {
    val hasLyrics get() = !syncedLyrics.isNullOrBlank() || !plainLyrics.isNullOrBlank() || instrumental

    fun toJson(): JSONObject = JSONObject().apply {
        put("artistName", artistName); put("trackName", trackName); put("duration", duration)
        put("instrumental", instrumental)
        put("syncedLyrics", syncedLyrics ?: JSONObject.NULL); put("plainLyrics", plainLyrics ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject) = Track(
            artistName = o.optString("artistName", ""),
            trackName = o.optString("trackName", ""),
            duration = o.optDouble("duration", 0.0).let { if (it.isNaN()) 0.0 else it },
            instrumental = o.optBoolean("instrumental", false),
            syncedLyrics = o.optStringOrNull("syncedLyrics"),
            plainLyrics = o.optStringOrNull("plainLyrics"),
        )

        private fun JSONObject.optStringOrNull(k: String): String? =
            if (isNull(k)) null else optString(k, "").ifBlank { null }
    }
}

/**
 * Buscador de letras gratuito LRCLIB (https://lrclib.net). Necesita internet: el setlist se
 * prepara antes del show y durante el show ya no hace falta. Llamar fuera del hilo principal.
 */
object Lrclib {
    private const val API = "https://lrclib.net/api"

    private fun get(url: String): String? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", "VisualesMovil/1.0")
        }
        try {
            val code = c.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw IOException("Buscador de letras: error $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            throw IOException(e.message ?: "No se pudo conectar con el buscador de letras. ¿Hay internet?", e)
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Búsqueda libre ("artista canción"). Primero las que tienen letra sincronizada. */
    fun search(query: String): List<Track> {
        val body = get("$API/search?q=${enc(query)}") ?: return emptyList()
        val arr = JSONArray(body)
        val list = (0 until arr.length()).map { Track.fromJson(arr.getJSONObject(it)) }
        return list.sortedByDescending { if (it.syncedLyrics != null) 1 else 0 }
    }

    /** La letra exacta de artista + título, o la mejor de la búsqueda. */
    fun find(artist: String, title: String): Track? {
        try {
            val body = get("$API/get?artist_name=${enc(artist)}&track_name=${enc(title)}")
            if (body != null) {
                val t = Track.fromJson(JSONObject(body))
                if (t.hasLyrics) return t
            }
        } catch (_: Exception) { /* probar con la búsqueda */ }
        val list = search("$artist $title".trim())
        return list.firstOrNull { it.syncedLyrics != null } ?: list.firstOrNull { it.plainLyrics != null }
    }
}
