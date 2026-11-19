package com.mikasa.visuales

import android.net.Uri
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mikasa.visuales.databinding.ActivityMainBinding
import com.mikasa.visuales.databinding.DialogSongBinding
import com.mikasa.visuales.databinding.ItemSongBinding
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Todo lo de las letras en la interfaz: setlist, editor de canciones, búsqueda en LRCLIB,
 * reconocimiento de la canción que suena, sincronización y el teleprompter del operador.
 */
class LyricsController(
    private val activity: AppCompatActivity,
    private val b: ActivityMainBinding,
    private val lyrics: LyricsEngine,
    private val audio: AudioEngine,
) {
    private val library = SongLibrary(activity)
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs = activity.getSharedPreferences("visuales", AppCompatActivity.MODE_PRIVATE)
    private var currentSongId: Long? = null
        set(v) { field = v; recognizer.currentSongId = v }
    private var identifying = false
    /** Reconocimiento en tiempo real activado por el usuario (solo actúa en modo música grabada). */
    private var liveId = true
    private var liveMessage = ""
    private val recognizer = LiveRecognizer(audio, library, object : LiveRecognizer.Listener {
        override fun onMatch(song: Song, pos: Double, endMs: Double, score: Int) = onLiveMatch(song, pos, endMs)
        override fun onListening(message: String, secondsWithoutMatch: Int) {
            liveMessage = if (secondsWithoutMatch >= 15 && currentSongId != null) "🎤 Sin coincidencia hace ${secondsWithoutMatch} s" else message
            updateLiveChip()
        }
    })

    /** Indicador en la pantalla principal; MainActivity decide si se ve (controles visibles). */
    var liveChipAllowed = true
        set(v) { field = v; updateLiveChip() }

    /** true mientras hay letra cargada (el teleprompter y las teclas de volumen la controlan). */
    val active get() = lyrics.lines.isNotEmpty() || lyrics.title.isNotEmpty()

    /** Se llama cuando hay que mostrar u ocultar el teleprompter. */
    var onActiveChanged: (() -> Unit)? = null

    private val pickAudio = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addAudios(uris)
    }

    private val promptTicker = object : Runnable {
        override fun run() {
            updatePrompt()
            updateLiveChip()
            b.teleprompter.postDelayed(this, 250)
        }
    }

    fun setup() {
        worker.execute {
            library.load()
            activity.runOnUiThread { renderSetlist() }
        }

        b.groupLyrMode.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            setMode(live = id == R.id.modeLive)
        }
        setMode(live = prefs.getBoolean("lyr_live", true))

        b.btnAddSong.setOnClickListener { editSong(null) }
        b.btnAddAudio.setOnClickListener { pickAudio.launch(arrayOf("audio/*")) }
        b.btnIdentify.setOnClickListener { identify(silent = false) }
        liveId = prefs.getBoolean("live_id", true)
        b.switchAutoId.isChecked = liveId
        b.switchAutoId.setOnCheckedChangeListener { _, on ->
            liveId = on
            prefs.edit().putBoolean("live_id", on).apply()
            updateRecognizer()
        }
        b.btnStartNow.setOnClickListener { lyrics.startNow(); status("⏱ Letra sincronizada desde el inicio") }
        b.btnNudgeMinus.setOnClickListener { lyrics.nudge(-0.25) }
        b.btnNudgePlus.setOnClickListener { lyrics.nudge(0.25) }

        b.btnSearch.setOnClickListener { search() }
        b.etSearch.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }

        b.spDisplay.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, LyricsEngine.DISPLAYS)
        b.spLyrFx.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, LyricsEngine.EFFECTS)
        b.spDisplay.setSelection(prefs.getInt("lyr_display", 0).coerceIn(0, 4))
        b.spLyrFx.setSelection(prefs.getInt("lyr_fx", 0).coerceIn(0, 4))
        b.spDisplay.onItemSelectedListener = selected { lyrics.display = it; prefs.edit().putInt("lyr_display", it).apply() }
        b.spLyrFx.onItemSelectedListener = selected { lyrics.effect = it; prefs.edit().putInt("lyr_fx", it).apply() }
        b.sliderLyrSize.addOnChangeListener { _, v, _ -> lyrics.size = v }
        b.switchLyrVisible.setOnCheckedChangeListener { _, on -> lyrics.visible = on }

        b.btnLineNext.setOnClickListener { lyrics.step(1); updatePrompt() }
        b.btnLinePrev.setOnClickListener { lyrics.step(-1); updatePrompt() }
        b.btnSongNext.setOnClickListener { stepSong(1) }
        b.btnSongPrev.setOnClickListener { stepSong(-1) }
        b.promptText.setOnClickListener { lyrics.step(1); updatePrompt() }

        b.teleprompter.post(promptTicker)
    }

    private fun selected(f: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = f(position)
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    private fun setMode(live: Boolean) {
        lyrics.manual = live
        prefs.edit().putBoolean("lyr_live", live).apply()
        val target = if (live) R.id.modeLive else R.id.modeRec
        if (b.groupLyrMode.checkedButtonId != target) b.groupLyrMode.check(target)
        b.recBox.visibility = if (live) View.GONE else View.VISIBLE
        b.tvModeHelp.setText(if (live) R.string.mode_live_help else R.string.mode_rec_help)
        updateRecognizer()
    }

    // ---------------- Tiempo real ----------------

    private var resumed = false

    fun onResume() { resumed = true; updateRecognizer() }
    fun onPause() { resumed = false; updateRecognizer() }

    /** Escucha continuamente mientras la app está abierta, en modo música grabada y con el interruptor activado. */
    private fun updateRecognizer() {
        val want = resumed && liveId && !lyrics.manual
        if (want && !recognizer.running) { liveMessage = "🎤 Escuchando…"; recognizer.start() }
        if (!want && recognizer.running) recognizer.stop()
        updateLiveChip()
    }

    private fun updateLiveChip() {
        val show = liveChipAllowed && recognizer.running
        b.tvLive.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val songOk = currentSongId != null && lyrics.title.isNotEmpty() && !liveMessage.startsWith("🎤 Sin")
        b.tvLive.text = if (songOk && !liveMessage.startsWith("🎤 Parece")) {
            val pos = lyrics.position(LyricsEngine.nowMs()).coerceAtLeast(0.0)
            "🎵 ${lyrics.title.take(28)}  %d:%02d".format((pos / 60).toInt(), (pos % 60).toInt())
        } else liveMessage
    }

    /** Coincidencia confirmada por el reconocimiento en tiempo real. */
    private fun onLiveMatch(song: Song, pos: Double, endMs: Double) {
        liveMessage = ""
        if (lyrics.manual) return
        if (song.id == currentSongId && lyrics.title.isNotEmpty()) {
            // Misma canción: corrige si se ha desviado medio segundo o más (deja los ajustes finos de ±0,25 s).
            val now = LyricsEngine.nowMs()
            val drift = pos + (now - endMs) / 1000 - lyrics.position(now)
            if (abs(drift) >= 0.5) lyrics.resync(pos, endMs)
        } else {
            currentSongId = song.id
            lyrics.applyMatch(song.trackForLyrics(), pos, endMs)
            renderSetlist()
            status("✓ ${song.label} — reconocida en %d:%02d".format((pos / 60).toInt(), (pos % 60).toInt()) +
                if (lyrics.lines.isEmpty()) " (sin letra)" else "")
        }
        updatePrompt()
        updateLiveChip()
    }

    private fun status(msg: String, error: Boolean = false) {
        b.tvLyrStatus.text = msg
        b.tvLyrStatus.setTextColor(if (error) 0xFFFF8A8A.toInt() else 0xAAFFFFFF.toInt())
    }

    // ---------------- Teclas (volumen y clicker de presentaciones) ----------------

    /** true si la tecla se usó para la letra. */
    fun onKey(keyCode: Int, event: KeyEvent): Boolean {
        if (lyrics.lines.isEmpty()) return false
        val shift = event.isShiftPressed
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> lyrics.step(1)
            KeyEvent.KEYCODE_PAGE_DOWN -> if (shift) stepSong(1) else lyrics.step(1)
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> lyrics.step(-1)
            KeyEvent.KEYCODE_PAGE_UP -> if (shift) stepSong(-1) else lyrics.step(-1)
            KeyEvent.KEYCODE_RIGHT_BRACKET -> stepSong(1)
            KeyEvent.KEYCODE_LEFT_BRACKET -> stepSong(-1)
            else -> return false
        }
        updatePrompt()
        return true
    }

    // ---------------- Teleprompter ----------------

    private var wasActive = false

    private fun updatePrompt() {
        val isActive = active
        if (isActive != wasActive) { wasActive = isActive; onActiveChanged?.invoke() }
        b.tvSong.text = if (lyrics.title.isNotEmpty()) "🎵 ${lyrics.title}" else activity.getString(R.string.no_song)
        if (!lyrics.manual && lyrics.synced) {
            val o = lyrics.offset
            b.tvOffset.text = "desfase ${if (o >= 0) "+" else ""}${"%.2f".format(o).replace('.', ',')} s"
        }
        if (!isActive) return
        val tp = lyrics.teleprompter()
        if (lyrics.lines.isEmpty()) {
            b.tvPromptPrev.text = ""
            b.tvPromptCur.text = lyrics.title
            b.tvPromptNext.text = "Sin letra: añádela con ✎"
            b.tvPromptNext2.text = ""
            return
        }
        val st = lyrics.state(LyricsEngine.nowMs())
        b.tvPromptPrev.text = tp[0]
        b.tvPromptCur.text = when {
            st.idx < 0 -> "▶ Pulsa para empezar"
            st.idx >= lyrics.lines.size -> "— fin —"
            tp[1].isEmpty() -> "· · ·"
            else -> tp[1]
        }
        b.tvPromptNext.text = tp[2]
        b.tvPromptNext2.text = if (lyrics.atEnd()) nextSongLabel() else tp[3]
    }

    private fun nextSongLabel(): String {
        val songs = library.snapshot()
        val i = songs.indexOfFirst { it.id == currentSongId }
        val next = songs.getOrNull(i + 1) ?: return ""
        return "Siguiente: ${next.label}"
    }

    // ---------------- Setlist ----------------

    private fun badge(s: Song): String {
        val t = s.track
        val lyr = when {
            t == null || !t.hasLyrics -> "sin letra"
            t.syncedLyrics != null -> "✓ con tiempos"
            else -> "solo texto"
        }
        return if (s.hasAudio) "$lyr · 🎵 reconocible" else lyr
    }

    private fun renderSetlist() {
        val box = b.setlistBox
        box.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        val songs = library.snapshot()
        if (songs.isEmpty()) {
            box.addView(TextView(activity).apply {
                text = "El setlist está vacío."
                setTextColor(0x88FFFFFF.toInt())
                setPadding(8, 8, 8, 8)
            })
            return
        }
        songs.forEachIndexed { i, s ->
            val row = ItemSongBinding.inflate(inflater, box, false)
            val playing = s.id == currentSongId
            row.tvName.text = "${i + 1}. ${if (playing) "▶ " else ""}${s.label}"
            row.tvName.setTextColor(if (playing) ContextCompat.getColor(activity, R.color.accent) else 0xFFFFFFFF.toInt())
            row.tvBadge.text = badge(s)
            row.root.setOnClickListener { showSong(s) }
            row.btnUp.setOnClickListener { worker.execute { library.move(s, -1); activity.runOnUiThread { renderSetlist() } } }
            row.btnDown.setOnClickListener { worker.execute { library.move(s, 1); activity.runOnUiThread { renderSetlist() } } }
            row.btnEdit.setOnClickListener { editSong(s) }
            box.addView(row.root)
        }
    }

    private fun showSong(s: Song) {
        currentSongId = s.id
        lyrics.load(s.trackForLyrics(), s.label)
        renderSetlist()
        updatePrompt()
        status(
            when {
                lyrics.lines.isEmpty() -> "Esta canción no tiene letra: añádela con ✎"
                lyrics.manual -> "✓ Lista. Pasa a la primera línea cuando empiece a cantar."
                lyrics.synced -> "Letra cargada desde 0:00. Pulsa 🎤 o ⏱ \"Empieza ya\" para sincronizar."
                else -> "Letra sin tiempos: pásala con ◀ ▶."
            },
            error = lyrics.lines.isEmpty(),
        )
    }

    private fun stepSong(dir: Int) {
        val songs = library.snapshot()
        if (songs.isEmpty()) return
        val i = songs.indexOfFirst { it.id == currentSongId }
        val next = if (i < 0) songs.first() else songs[(i + dir + songs.size) % songs.size]
        showSong(next)
    }

    /** Editor de canción: nombre y letra (buscar en LRCLIB o pegar a mano). song = null para una nueva. */
    private fun editSong(song: Song?) {
        val d = DialogSongBinding.inflate(LayoutInflater.from(activity))
        d.etArtist.setText(song?.artist ?: "")
        d.etTitle.setText(song?.title ?: "")
        d.etLyrics.setText(song?.track?.let { it.syncedLyrics ?: it.plainLyrics } ?: "")
        var found: Track? = song?.track

        fun doSearch() {
            val artist = d.etArtist.text.toString().trim()
            val title = d.etTitle.text.toString().trim()
            if (title.isEmpty()) { d.tvDlgStatus.text = "Escribe al menos el título."; return }
            d.tvDlgStatus.text = "Buscando…"
            worker.execute {
                val result = runCatching {
                    if (artist.isNotEmpty()) Lrclib.find(artist, title) else Lrclib.search(title).firstOrNull()
                }
                activity.runOnUiThread {
                    result.onSuccess { t ->
                        if (t == null) {
                            d.tvDlgStatus.text = "No se encontró. Puedes pegar la letra a mano."
                        } else {
                            found = t
                            d.etLyrics.setText(t.syncedLyrics ?: t.plainLyrics ?: "")
                            if (d.etArtist.text.isBlank()) d.etArtist.setText(t.artistName)
                            d.tvDlgStatus.text = if (t.syncedLyrics != null) "✓ Encontrada con tiempos" else "✓ Encontrada (sin tiempos)"
                        }
                    }.onFailure { d.tvDlgStatus.text = it.message ?: "Sin conexión" }
                }
            }
        }
        d.btnDlgSearch.setOnClickListener { doSearch() }
        d.etTitle.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }

        val dlg = AlertDialog.Builder(activity)
            .setTitle(if (song == null) "Nueva canción del setlist" else "Editar canción")
            .setView(d.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .apply { if (song != null) setNeutralButton(R.string.delete, null) }
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val artist = d.etArtist.text.toString().trim()
                val title = d.etTitle.text.toString().trim()
                if (artist.isEmpty() && title.isEmpty()) { d.tvDlgStatus.text = "Escribe el título."; return@setOnClickListener }
                val text = d.etLyrics.text.toString().trimEnd()
                val hasTimes = LyricsEngine.parseLrc(text).isNotEmpty()
                val track = if (text.isBlank()) null else Track(
                    artistName = artist, trackName = title, duration = found?.duration ?: 0.0,
                    syncedLyrics = if (hasTimes) text else null, plainLyrics = if (hasTimes) null else text,
                )
                worker.execute {
                    val saved = if (song == null) {
                        library.addManual(artist, title, track)
                    } else {
                        song.artist = artist; song.title = title; song.track = track
                        library.save(song); song
                    }
                    activity.runOnUiThread {
                        renderSetlist()
                        if (song == null && currentSongId == null || saved.id == currentSongId) showSong(saved)
                    }
                }
                dlg.dismiss()
            }
            if (song != null) {
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    AlertDialog.Builder(activity)
                        .setMessage("¿Quitar \"${song.label}\" del setlist?")
                        .setPositiveButton(R.string.delete) { _, _ ->
                            worker.execute {
                                library.remove(song)
                                activity.runOnUiThread {
                                    if (song.id == currentSongId) { currentSongId = null; lyrics.clear(); updatePrompt() }
                                    renderSetlist()
                                }
                            }
                            dlg.dismiss()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        }
        dlg.show()
    }

    private fun addAudios(uris: List<Uri>) {
        worker.execute {
            var noLyrics = 0
            uris.forEachIndexed { k, uri ->
                try {
                    val s = library.addAudio(uri) { msg -> activity.runOnUiThread { status("(${k + 1}/${uris.size}) $msg") } }
                    if (s.track == null) noLyrics++
                    activity.runOnUiThread { renderSetlist() }
                } catch (e: Exception) {
                    activity.runOnUiThread { status("No se pudo leer un audio: ${e.message}", true) }
                }
            }
            activity.runOnUiThread {
                status(
                    if (noLyrics > 0) "✓ Listo. $noLyrics canción(es) quedaron sin letra: añádela con ✎."
                    else "✓ Listo. Ya se pueden reconocer.",
                )
            }
        }
    }

    // ---------------- Búsqueda libre ----------------

    private fun search() {
        val q = b.etSearch.text.toString().trim()
        if (q.isEmpty()) return
        status("Buscando…")
        worker.execute {
            val r = runCatching { Lrclib.search(q).take(15) }
            activity.runOnUiThread {
                r.onFailure { status(it.message ?: "Sin conexión", true) }
                r.onSuccess { list ->
                    if (list.isEmpty()) { status("No se encontró nada.", true); return@onSuccess }
                    status("Elige una letra:")
                    val labels = list.map {
                        "${it.artistName} — ${it.trackName}" + when {
                            it.syncedLyrics != null -> "  ✓ con tiempos"
                            it.plainLyrics != null -> "  (solo texto)"
                            else -> "  (sin letra)"
                        }
                    }.toTypedArray()
                    AlertDialog.Builder(activity)
                        .setTitle("Resultados")
                        .setItems(labels) { _, i -> pickResult(list[i]) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    private fun pickResult(t: Track) {
        AlertDialog.Builder(activity)
            .setTitle("${t.artistName} — ${t.trackName}")
            .setItems(arrayOf("Mostrar ahora", "Añadir al setlist")) { _, which ->
                if (which == 0) {
                    currentSongId = null
                    lyrics.load(t)
                    renderSetlist()
                    status(if (lyrics.manual) "✓ Letra cargada. Pasa líneas con ◀ ▶." else "✓ Letra cargada. Pulsa ⏱ \"Empieza ya\" cuando arranque.")
                } else {
                    worker.execute {
                        val s = library.addManual(t.artistName, t.trackName, t)
                        activity.runOnUiThread { renderSetlist(); if (currentSongId == null) showSong(s) }
                    }
                }
            }
            .show()
    }

    // ---------------- Reconocimiento ----------------

    private fun identify(silent: Boolean) {
        if (identifying) return
        if (library.snapshot().none { it.hasAudio }) {
            if (!silent) status("Para reconocer canciones, añádelas con 🎵 Desde audio.", true)
            return
        }
        val rec = audio.recentForId(8.0)
        if (rec == null) {
            if (!silent) status("Escuchando… vuelve a intentarlo en unos segundos.", true)
            return
        }
        identifying = true
        if (!silent) status("🎤 Reconociendo…")
        val (samples, endNs) = rec
        worker.execute {
            val m = runCatching { library.identify(samples) }.getOrNull()
            activity.runOnUiThread {
                identifying = false
                applyMatch(m, endNs / 1e6, silent)
            }
        }
    }

    private fun applyMatch(m: SongLibrary.IdResult?, endMs: Double, silent: Boolean) {
        if (m == null) {
            if (!silent) status("No se reconoció. ¿Está en el setlist (desde audio) y se oye bien?", true)
            return
        }
        if (m.song.id == currentSongId && lyrics.lines.isNotEmpty()) {
            // Misma canción: solo corrige el desfase si es grande (no pisa los ajustes finos a mano).
            val posNow = m.pos + (LyricsEngine.nowMs() - endMs) / 1000
            val drift = posNow - lyrics.position(LyricsEngine.nowMs())
            if (abs(drift) > 1.5 || !silent) lyrics.resync(m.pos, endMs)
            if (!silent) status("✓ ${m.song.label} (sincronizada)")
            return
        }
        if (silent && !m.sure) return
        currentSongId = m.song.id
        lyrics.applyMatch(m.song.trackForLyrics(), m.pos, endMs)
        renderSetlist()
        updatePrompt()
        val min = (m.pos / 60).toInt()
        val sec = (m.pos % 60).toInt()
        status("✓ ${m.song.label} — en %d:%02d".format(min, sec) + if (lyrics.lines.isEmpty()) " (sin letra)" else "")
    }

    fun release() {
        recognizer.release()
        worker.shutdown()
    }
}
