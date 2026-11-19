package com.mikasa.visuales

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteOrder

/** Una canción del setlist: nombre, letra y (si se añadió desde audio) su huella acústica. */
class Song(
    var id: Long,
    var artist: String,
    var title: String,
    var duration: Double,
    var order: Int,
    var track: Track?,
    var file: String?,
) {
    var index: Fingerprint.Index? = null
    val hasAudio get() = (index?.h?.size ?: 0) > 0
    val label get() = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" — ").ifBlank { "Sin título" }

    /** Letra en el formato que entiende LyricsEngine.load(). */
    fun trackForLyrics() = Track(
        artistName = artist.ifBlank { track?.artistName ?: "" },
        trackName = title.ifBlank { track?.trackName ?: "" },
        duration = duration,
        instrumental = track?.instrumental ?: false,
        syncedLyrics = track?.syncedLyrics,
        plainLyrics = track?.plainLyrics,
    )
}

/**
 * Repertorio guardado en el celular (carpeta interna de la app): un .json por canción con nombre
 * y letra, y un .fp con la huella. Se prepara una vez antes del show; luego funciona sin internet.
 */
class SongLibrary(context: Context) {

    private val dir = File(context.filesDir, "repertorio").apply { mkdirs() }
    private val resolver = context.contentResolver
    val songs = ArrayList<Song>()

    /** Copia de la lista para recorrerla desde la interfaz mientras otro hilo añade canciones. */
    fun snapshot(): List<Song> = synchronized(this) { songs.toList() }

    class IdResult(val song: Song, val pos: Double, val score: Int, val sure: Boolean)

    @Synchronized
    fun load() {
        songs.clear()
        dir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            try {
                val o = JSONObject(f.readText())
                val s = Song(
                    id = o.getLong("id"),
                    artist = o.optString("artist", ""),
                    title = o.optString("title", ""),
                    duration = o.optDouble("duration", 0.0),
                    order = o.optInt("order", Int.MAX_VALUE),
                    track = if (o.isNull("track")) null else Track.fromJson(o.getJSONObject("track")),
                    file = if (o.isNull("file")) null else o.optString("file"),
                )
                val fp = File(dir, "${s.id}.fp")
                if (fp.exists()) s.index = readIndex(fp)
                songs.add(s)
            } catch (_: Exception) { /* archivo dañado: se ignora */ }
        }
        songs.sortWith(compareBy<Song> { it.order }.thenBy { it.id })
        songs.forEachIndexed { i, s -> s.order = i }
    }

    @Synchronized
    fun save(song: Song) {
        val o = JSONObject().apply {
            put("id", song.id); put("artist", song.artist); put("title", song.title)
            put("duration", song.duration); put("order", song.order)
            put("track", song.track?.toJson() ?: JSONObject.NULL)
            put("file", song.file ?: JSONObject.NULL)
        }
        File(dir, "${song.id}.json").writeText(o.toString())
        song.index?.let { writeIndex(File(dir, "${song.id}.fp"), it) }
    }

    @Synchronized
    fun remove(song: Song) {
        songs.remove(song)
        File(dir, "${song.id}.json").delete()
        File(dir, "${song.id}.fp").delete()
        songs.forEachIndexed { i, s -> if (s.order != i) { s.order = i; save(s) } }
    }

    @Synchronized
    fun move(song: Song, dir: Int) {
        val i = songs.indexOf(song)
        val j = i + dir
        if (i < 0 || j < 0 || j >= songs.size) return
        songs[i] = songs[j].also { songs[j] = songs[i] }
        songs[i].order = i; songs[j].order = j
        save(songs[i]); save(songs[j])
    }

    private fun newId() = System.currentTimeMillis() * 1000 + (Math.random() * 1000).toLong()

    /** Canción sin audio (banda en vivo): solo nombre y letra. */
    @Synchronized
    fun addManual(artist: String, title: String, track: Track?): Song {
        val s = Song(newId(), artist, title, track?.duration ?: 0.0, songs.size, track, null)
        save(s)
        songs.add(s)
        return s
    }

    /** Añade un audio del celular: huella + búsqueda de letra. Llamar fuera del hilo principal. */
    fun addAudio(uri: Uri, onStep: (String) -> Unit): Song {
        val name = displayName(uri)
        onStep("Analizando $name…")
        val (mono, duration) = decodeToFingerprintRate(uri)
        val index = Fingerprint.buildIndex(Fingerprint.fingerprint(mono))
        val (artist, title) = parseName(name)
        val track = try {
            onStep("Buscando la letra de ${listOf(artist, title).filter { it.isNotBlank() }.joinToString(" – ")}…")
            if (artist.isNotBlank()) Lrclib.find(artist, title) else Lrclib.search(title).firstOrNull()
        } catch (_: Exception) {
            null // sin internet: la letra se puede añadir después
        }
        synchronized(this) {
            val s = Song(newId(), artist, title, duration, songs.size, track, name)
            s.index = index
            save(s)
            songs.add(s)
            return s
        }
    }

    /**
     * Reconoce una muestra (mono a 11 025 Hz). pos = segundo de la canción en el que TERMINA la muestra.
     * Mismos umbrales que la versión web.
     */
    fun identify(mono: FloatArray): IdResult? {
        val withAudio = synchronized(this) { songs.filter { it.hasAudio }.map { it.id to it.index!! } }
        if (withAudio.isEmpty()) return null
        val r = Fingerprint.match(Fingerprint.fingerprint(mono), withAudio)
        val best = r.firstOrNull() ?: return null
        val second = r.getOrNull(1)?.score ?: 0
        if (best.score < 6 || best.score < 1.5 * maxOf(1, second)) return null
        val song = synchronized(this) { songs.firstOrNull { it.id == best.id } } ?: return null
        return IdResult(
            song,
            pos = best.offset * Fingerprint.FRAME_SEC + mono.size.toDouble() / Fingerprint.SR,
            score = best.score,
            sure = best.score >= 10 && best.score >= 2 * maxOf(1, second),
        )
    }

    // ---------------- Archivos ----------------

    private fun displayName(uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "audio"
        }
        return uri.lastPathSegment ?: "audio"
    }

    /** Decodifica cualquier audio (mp3, m4a, wav, ogg…) a mono 11 025 Hz. Devuelve (muestras, duración). */
    private fun decodeToFingerprintRate(uri: Uri): Pair<FloatArray, Double> {
        val ex = MediaExtractor()
        resolver.openFileDescriptor(uri, "r")!!.use { pfd -> ex.setDataSource(pfd.fileDescriptor) }
        try {
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("El archivo no tiene audio")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var floatPcm = false
            var resampler = Resampler(rate)
            var frames = 0L
            val info = MediaCodec.BufferInfo()
            var inDone = false
            try {
                while (true) {
                    if (!inDone) {
                        val ii = codec.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val buf = codec.getInputBuffer(ii)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 10_000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = codec.outputFormat
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val newRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (newRate != rate && frames == 0L) { rate = newRate; resampler = Resampler(rate) }
                        floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    } else if (oi >= 0) {
                        val buf = codec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        if (floatPcm) {
                            val fb = buf.asFloatBuffer()
                            val nf = fb.remaining() / channels
                            for (i in 0 until nf) {
                                var acc = 0f
                                for (c in 0 until channels) acc += fb.get()
                                resampler.push(acc / channels)
                            }
                            frames += nf
                        } else {
                            val sb = buf.asShortBuffer()
                            val nf = sb.remaining() / channels
                            for (i in 0 until nf) {
                                var acc = 0f
                                for (c in 0 until channels) acc += sb.get()
                                resampler.push(acc / (channels * 32768f))
                            }
                            frames += nf
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }
            return resampler.result() to frames.toDouble() / rate
        } finally {
            ex.release()
        }
    }

    private fun writeIndex(f: File, idx: Fingerprint.Index) {
        DataOutputStream(f.outputStream().buffered()).use { out ->
            out.writeInt(idx.h.size)
            for (v in idx.h) out.writeInt(v)
            for (v in idx.t) out.writeInt(v)
        }
    }

    private fun readIndex(f: File): Fingerprint.Index =
        DataInputStream(f.inputStream().buffered()).use { inp ->
            val n = inp.readInt()
            val h = IntArray(n) { inp.readInt() }
            val t = IntArray(n) { inp.readInt() }
            Fingerprint.Index(h, t)
        }

    companion object {
        /** "01 Artista - Título.mp3" -> (artista, título) */
        fun parseName(fileName: String): Pair<String, String> {
            val base = fileName.replace(Regex("\\.[^.]+$"), "").replace('_', ' ')
                .replace(Regex("^\\s*\\d+[\\s.\\-_)]+"), "").trim()
            val parts = base.split(Regex("\\s+[-–—]\\s+"))
            return if (parts.size >= 2) parts[0].trim() to parts.drop(1).joinToString(" - ").trim() else "" to base
        }
    }
}
