// Repertorio: canciones con su huella acústica y su letra, guardadas en el navegador (IndexedDB).
// Se prepara una vez antes del show; durante el show no hace falta internet.

class SongLibrary {
  constructor(lyrics) {
    this.lyrics = lyrics;   // para buscar letras en LRCLIB
    this.songs = [];        // { id, artist, title, duration, h, t, track }
    this.db = null;
    this.persistent = false;
  }

  async open() {
    try {
      this.db = await new Promise((res, rej) => {
        const req = indexedDB.open('visuales-repertorio', 1);
        req.onupgradeneeded = () => req.result.createObjectStore('songs', { keyPath: 'id', autoIncrement: true });
        req.onsuccess = () => res(req.result);
        req.onerror = () => rej(req.error);
      });
      this.songs = await this._tx('readonly', (s) => s.getAll());
      this.persistent = true;
    } catch (e) {
      console.warn('Sin almacenamiento permanente:', e);
      this.db = null;
    }
    return this.songs;
  }

  _tx(mode, fn) {
    return new Promise((res, rej) => {
      const tx = this.db.transaction('songs', mode);
      const req = fn(tx.objectStore('songs'));
      tx.oncomplete = () => res(req && req.result);
      tx.onerror = () => rej(tx.error);
    });
  }

  async _save(song) {
    if (this.db) song.id = await this._tx('readwrite', (s) => s.put(song));
    else if (song.id == null) song.id = Date.now() + Math.random();
    return song;
  }

  // Audio decodificado -> mono a 11 025 Hz
  static async toMono(audioBuffer) {
    const len = Math.max(1, Math.ceil(audioBuffer.duration * FP.SR));
    const off = new OfflineAudioContext(1, len, FP.SR);
    const src = off.createBufferSource();
    src.buffer = audioBuffer;
    src.connect(off.destination);
    src.start();
    return (await off.startRendering()).getChannelData(0);
  }

  // "01 Artista - Título.mp3" -> { artist, title }
  static parseName(fileName) {
    const base = fileName.replace(/\.[^.]+$/, '').replace(/_/g, ' ').replace(/^\s*\d+[\s.\-_)]+/, '').trim();
    const m = base.split(/\s+[-–—]\s+/);
    return m.length >= 2 ? { artist: m[0].trim(), title: m.slice(1).join(' - ').trim() } : { artist: '', title: base };
  }

  // Añade un archivo de audio: huella + búsqueda de letra.
  async addFile(file, onStep = () => {}) {
    onStep(`Analizando ${file.name}…`);
    const data = await file.arrayBuffer();
    const decoded = await new OfflineAudioContext(1, 1, 44100).decodeAudioData(data);
    const mono = await SongLibrary.toMono(decoded);
    await new Promise((r) => setTimeout(r, 0));   // dejar respirar a la interfaz
    const { h, t } = FP.buildIndex(FP.fingerprint(mono));
    const { artist, title } = SongLibrary.parseName(file.name);
    const song = { artist, title, duration: decoded.duration, h, t, track: null, file: file.name };
    onStep(`Buscando letra de ${artist ? artist + ' – ' : ''}${title}…`);
    try {
      song.track = artist ? await this.lyrics.findLyrics(artist, title) : (await this.lyrics.search(title))[0] || null;
    } catch (e) { /* sin internet: se puede añadir la letra después */ }
    await this._save(song);
    this.songs.push(song);
    return song;
  }

  async update(song) { await this._save(song); }

  async remove(id) {
    if (this.db) await this._tx('readwrite', (s) => s.delete(id));
    this.songs = this.songs.filter((s) => s.id !== id);
  }

  // Letra en el formato que entiende LyricsEngine.load()
  trackFor(song) {
    const t = song.track || {};
    return {
      artistName: song.artist || t.artistName || '',
      trackName: song.title || t.trackName || '',
      duration: song.duration,
      instrumental: !!t.instrumental,
      syncedLyrics: t.syncedLyrics || null,
      plainLyrics: t.plainLyrics || null,
    };
  }

  // Reconoce una muestra (mono 11 025 Hz). Devuelve { song, pos, score, sure } o null.
  // pos = segundo de la canción en el que TERMINA la muestra.
  identify(mono) {
    if (!this.songs.length) return null;
    const q = FP.fingerprint(mono);
    const r = FP.match(q, this.songs);
    const best = r[0], second = r[1] || { score: 0 };
    if (!best || best.score < 6 || best.score < 1.5 * Math.max(1, second.score)) return null;
    const song = this.songs.find((s) => s.id === best.id);
    return {
      song,
      pos: best.offset * FP.frameSec + mono.length / FP.SR,
      score: best.score,
      sure: best.score >= 10 && best.score >= 2 * Math.max(1, second.score),
    };
  }
}
