package com.mikasa.visuales

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reconocimiento de cualquier canción por internet con AudD (https://audd.io), como Shazam: se le
 * mandan unos segundos de audio y responde artista, título y en qué segundo de la canción iba la
 * muestra. Sin clave permite unas pocas consultas gratis al día; con la clave (api_token) de una
 * cuenta de audd.io, muchas más. Llamar fuera del hilo principal.
 */
object Audd {
    private const val API = "https://api.audd.io/"

    class Result(val artist: String, val title: String, val album: String?, val timecodeSec: Double)

    /** null si no la reconoce. Lanza IOException con un mensaje legible si hay un error. */
    fun recognize(samples: FloatArray, sampleRate: Int, token: String?): Result? {
        val boundary = "----visuales${System.nanoTime()}"
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        if (!token.isNullOrBlank()) field("api_token", token.trim())
        field("return", "timecode")
        body.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"muestra.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n").toByteArray()
        )
        body.write(wav(samples, sampleRate))
        body.write("\r\n--$boundary--\r\n".toByteArray())
        val bytes = body.toByteArray()

        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 20000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", "VisualesMovil/1.0")
            setFixedLengthStreamingMode(bytes.size)
        }
        val text = try {
            c.outputStream.use { it.write(bytes) }
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            stream?.bufferedReader()?.use { it.readText() } ?: throw IOException("AudD: error ${c.responseCode}")
        } catch (e: IOException) {
            throw IOException("No se pudo conectar con el servicio de reconocimiento (AudD). ¿Hay internet?", e)
        } finally {
            c.disconnect()
        }

        val j = JSONObject(text)
        if (j.optString("status") == "error") {
            val err = j.optJSONObject("error")
            val code = err?.optInt("error_code") ?: 0
            val msg = err?.optString("error_message").orEmpty()
            throw IOException(
                when {
                    code == 901 || code == 902 || msg.contains("limit", true) ->
                        "Se acabaron las consultas gratis de hoy. Pon una clave de audd.io en el panel de Letras."
                    code == 900 -> "La clave de AudD no es válida. Revísala en el panel de Letras."
                    else -> "AudD: ${msg.ifBlank { "error $code" }}"
                }
            )
        }
        val r = j.optJSONObject("result") ?: return null
        return Result(
            artist = r.optString("artist"),
            title = r.optString("title"),
            album = r.optString("album").ifBlank { null },
            timecodeSec = parseTimecode(r.optString("timecode")),
        )
    }

    /** "01:23" o "1:02:03" -> segundos. */
    fun parseTimecode(tc: String?): Double =
        (tc ?: "").split(':').fold(0.0) { acc, v -> acc * 60 + (v.trim().toDoubleOrNull() ?: 0.0) }

    /** WAV PCM 16 bits mono. */
    private fun wav(x: FloatArray, sr: Int): ByteArray {
        val data = x.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + data); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(sr); b.putInt(sr * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(data)
        // Normaliza el volumen (el micrófono sin procesar suele grabar bajo).
        var peak = 1e-6f
        for (v in x) peak = maxOf(peak, kotlin.math.abs(v))
        val g = (0.9f / peak).coerceAtMost(30f)
        for (v in x) b.putShort((v * g * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        return b.array()
    }
}
