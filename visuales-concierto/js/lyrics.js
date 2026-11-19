// Letras en la proyección:
//  - la canción que suena se reconoce con el repertorio local (ver library.js / fingerprint.js),
//  - las letras se buscan gratis en LRCLIB (sincronizadas si existen) o se pegan a mano,
//  - la mantiene sincronizada y la dibuja en una capa transparente con 5 formas de display.
// Los 5 efectos de texto se aplican después en la GPU (ver COPY_FRAG en shaders.js).

const LYRIC_DISPLAYS = ['Karaoke', 'Gran centro', 'Desplazamiento', 'Palabra a palabra', 'Palabras dispersas'];
const LYRIC_EFFECTS = ['Neón', 'Cromático', 'Ola', 'Glitch', 'Video en el texto'];

const LRCLIB = 'https://lrclib.net/api';
const LYRIC_FONT = "'Arial Black', 'Segoe UI Black', 'Segoe UI', system-ui, sans-serif";

// "[01:23.45] texto" -> [{ t: 83.45, text }]. Admite varias marcas por línea; ignora [ar:...] etc.
function parseLRC(text) {
  const out = [];
  for (const raw of (text || '').split(/\r?\n/)) {
    const stamps = [...raw.matchAll(/\[(\d+):(\d+(?:[.:]\d+)?)\]/g)];
    if (!stamps.length) continue;
    const line = raw.replace(/\[[^\]]*\]/g, '').trim();
    for (const m of stamps) out.push({ t: parseInt(m[1], 10) * 60 + parseFloat(m[2].replace(':', '.')), text: line });
  }
  return out.sort((a, b) => a.t - b.t);
}
// Texto normal: una línea por renglón. Un renglón vacío entre estrofas = pausa (pantalla limpia).
function parsePlain(text) {
  const out = [];
  for (const raw of (text || '').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line && (!out.length || out[out.length - 1].text === '')) continue;
    out.push({ t: out.length * 4, text: line });
  }
  while (out.length && out[out.length - 1].text === '') out.pop();
  return out;
}
// "01:23" o "1:02:03" -> segundos
function parseTimecode(tc) {
  if (typeof tc === 'number') return tc;
  if (!tc) return 0;
  return String(tc).split(':').reduce((acc, v) => acc * 60 + parseFloat(v || 0), 0);
}
const rnd = (n) => { const x = Math.sin(n * 127.1 + 311.7) * 43758.5453; return x - Math.floor(x); };
const clamp01 = (v) => Math.max(0, Math.min(1, v));

class LyricsEngine {
  constructor(audio, w, h) {
    this.audio = audio;
    this.canvas = document.createElement('canvas');
    this.ctx = this.canvas.getContext('2d');
    this.resize(w, h);

    this.lines = [];
    this.synced = false;
    this.title = '';
    this.duration = 0;
    this.fromFile = false;     // sincronizar con la canción de prueba en vez de con el reloj
    this.songStart = performance.now();
    this.offset = 0;
    this.manualIdx = 0;
    this.manualAt = performance.now();
    this.titleShownAt = -1e9;

    this.display = 0;
    this.effect = 0;
    this.size = 1;
    this.visible = true;
    this.busy = false;
    // Banda en vivo: las líneas solo cambian cuando el operador avanza (Espacio / clicker).
    this.manual = true;
  }

  resize(w, h) {
    this.canvas.width = Math.round(w);
    this.canvas.height = Math.round(h);
  }

  // ---------- Reconocimiento ----------
  // Carga la letra de una canción reconocida. posAtEnd = segundo de la canción en el que
  // terminaba la muestra; endTime = instante (performance.now) en que terminó.
  applyMatch(track, posAtEnd, endTime) {
    this.load(track);
    const posNow = posAtEnd + (performance.now() - endTime) / 1000;
    if (this.audio.hasFile) {
      // Con la canción de prueba: se sigue el reloj del archivo (respeta la pausa).
      this.fromFile = true;
      this.offset = posNow - this.audio.mediaEl.currentTime;
    } else {
      this.fromFile = false;
      this.offset = 0;
      this.songStart = performance.now() - posNow * 1000;
    }
  }

  // Corrige solo el desfase (misma canción, nueva medición).
  resync(posAtEnd, endTime) {
    const posNow = posAtEnd + (performance.now() - endTime) / 1000;
    this.offset += posNow - this.position(performance.now());
  }

  // ---------- Letras (LRCLIB) ----------
  async search(query) {
    let r;
    try {
      r = await fetch(`${LRCLIB}/search?q=${encodeURIComponent(query)}`);
    } catch (e) {
      throw new Error('No se pudo conectar con el buscador de letras (LRCLIB). ¿Hay internet?');
    }
    if (!r.ok) throw new Error(`Buscador de letras: error ${r.status}`);
    const list = await r.json();
    // Primero las que tienen letra sincronizada.
    return list.sort((a, b) => (b.syncedLyrics ? 1 : 0) - (a.syncedLyrics ? 1 : 0));
  }

  async findLyrics(artist, title, album) {
    const qs = new URLSearchParams({ artist_name: artist, track_name: title });
    if (album) qs.set('album_name', album);
    try {
      const r = await fetch(`${LRCLIB}/get?${qs}`);
      if (r.ok) {
        const j = await r.json();
        if (j && (j.syncedLyrics || j.plainLyrics || j.instrumental)) return j;
      }
    } catch (e) { /* probar con la búsqueda */ }
    const list = await this.search(`${artist} ${title}`);
    return list.find((x) => x.syncedLyrics) || list.find((x) => x.plainLyrics) || null;
  }

  // Carga un resultado de LRCLIB (o null si no hay letra).
  load(track, info = {}) {
    const artist = (track && track.artistName) || info.artist || '';
    const title = (track && track.trackName) || info.title || '';
    this.title = [artist, title].filter(Boolean).join(' — ');
    this.titleShownAt = performance.now();
    this.duration = (track && track.duration) || 0;
    this.offset = 0;
    this.manualIdx = -1;          // antes de la primera línea: solo el título
    this.manualAt = performance.now();
    this.fromFile = this.audio.hasFile;
    this.songStart = performance.now();
    if (!track) { this.lines = []; this.synced = false; return; }
    if (track.instrumental) { this.lines = [{ t: 0, text: '♪ instrumental ♪' }]; this.synced = false; return; }
    const synced = parseLRC(track.syncedLyrics);
    this.synced = synced.length > 0;
    this.lines = this.synced ? synced : parsePlain(track.plainLyrics);
  }

  // ---------- Sincronización ----------
  position(now) {
    if (this.fromFile && this.audio.hasFile) return this.audio.mediaEl.currentTime + this.offset;
    return (now - this.songStart) / 1000 + this.offset;
  }

  // La canción (en vivo) empieza ahora mismo.
  startNow(now) {
    this.fromFile = false;
    this.songStart = now;
    this.offset = 0;
    this.manualIdx = -1;
    this.manualAt = now;
  }

  // Salta a la línea siguiente / anterior. Con letra sincronizada corrige el desfase para que
  // esa línea empiece justo ahora; así se sincroniza a mano con dos o tres pulsaciones.
  step(dir, now) {
    if (!this.lines.length) return;
    if (this.manual || !this.synced) {
      this.manualIdx = Math.max(-1, Math.min(this.lines.length, this.manualIdx + dir));
      this.manualAt = now;
      return;
    }
    const st = this.state(now);
    const target = Math.max(0, Math.min(this.lines.length - 1, st.idx + dir));
    this.offset += this.lines[target].t - st.pos + 0.01;
  }

  nudge(sec) { this.offset += sec; }

  state(now) {
    const L = this.lines;
    if (this.manual || !this.synced) {
      const idx = this.manualIdx;
      // Duración estimada de la línea (para animaciones): la de la letra con tiempos, o 3,5 s.
      const dur = this.synced && L[idx] && L[idx + 1] ? Math.min(8, Math.max(1.5, L[idx + 1].t - L[idx].t)) : 3.5;
      const p = clamp01((now - this.manualAt) / 1000 / dur);
      return { idx, p, pos: 0, t0: 0, t1: dur, dur, text: L[idx]?.text || '', next: L[idx + 1]?.text || '' };
    }
    const pos = this.position(now);
    let idx = -1;
    for (let i = 0; i < L.length; i++) { if (L[i].t <= pos) idx = i; else break; }
    const t0 = idx >= 0 ? L[idx].t : 0;
    const t1 = L[idx + 1] ? L[idx + 1].t : t0 + 5;
    const dur = Math.max(0.3, t1 - t0);
    return { idx, p: idx >= 0 ? clamp01((pos - t0) / dur) : 0, pos, t0, t1, dur,
      text: idx >= 0 ? L[idx].text : '', next: L[idx + 1]?.text || '' };
  }

  // ---------- Dibujo ----------
  _font(px) { return `800 ${Math.round(px)}px ${LYRIC_FONT}`; }

  _wrap(text, maxW) {
    const ctx = this.ctx;
    const words = text.split(/\s+/).filter(Boolean);
    const rows = [];
    let cur = '';
    for (const w of words) {
      const test = cur ? `${cur} ${w}` : w;
      if (ctx.measureText(test).width > maxW && cur) { rows.push(cur); cur = w; } else cur = test;
    }
    if (cur) rows.push(cur);
    return rows;
  }

  // Dibuja la capa de letras. Devuelve true si hay algo que mostrar.
  draw(now, beat) {
    const ctx = this.ctx, W = this.canvas.width, H = this.canvas.height;
    ctx.clearRect(0, 0, W, H);
    let drew = false;

    // Cartel con artista y título durante 5 s al cargar una canción.
    const tc = now - this.titleShownAt;
    if (this.title && tc < 5000) {
      ctx.globalAlpha = clamp01(Math.min(tc / 400, (5000 - tc) / 600));
      ctx.fillStyle = '#fff';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'top';
      ctx.font = this._font(H * 0.045);
      ctx.fillText(this.title, W / 2, H * 0.06);
      ctx.globalAlpha = 1;
      drew = true;
    }
    if (!this.visible || !this.lines.length) return drew;

    const st = this.state(now);
    ctx.textBaseline = 'alphabetic';
    ctx.textAlign = 'center';
    const fn = [this._karaoke, this._center, this._scroll, this._word, this._scatter][this.display];
    if (st.idx < 0 && this.display !== 2) return drew;      // intro: solo el modo desplazamiento la anticipa
    if (!st.text && this.display !== 2) return drew;        // pausa / instrumental: pantalla limpia
    if (st.idx >= this.lines.length) return drew;           // fin de la letra
    fn.call(this, ctx, W, H, st, beat);
    return true;
  }

  // 1. Karaoke: la línea actual se va "pintando" de izquierda a derecha; la siguiente debajo.
  _karaoke(ctx, W, H, st) {
    const fs = H * 0.075 * this.size;
    ctx.font = this._font(fs);
    const rows = this._wrap(st.text, W * 0.86);
    const lh = fs * 1.15;
    const y0 = H * 0.80 - (rows.length - 1) * lh;
    const widths = rows.map((r) => ctx.measureText(r).width);
    let filled = st.p * widths.reduce((a, b) => a + b, 0);
    ctx.textAlign = 'left';
    rows.forEach((row, i) => {
      const w = widths[i], x = (W - w) / 2, y = y0 + i * lh;
      ctx.fillStyle = 'rgba(255,255,255,0.42)';
      ctx.fillText(row, x, y);
      const f = Math.max(0, Math.min(w, filled));
      if (f > 0) {
        ctx.save();
        ctx.beginPath();
        ctx.rect(x, y - fs * 1.1, f, fs * 1.6);
        ctx.clip();
        ctx.fillStyle = '#fff';
        ctx.fillText(row, x, y);
        ctx.restore();
      }
      filled -= w;
    });
    if (st.next) {
      ctx.textAlign = 'center';
      ctx.font = this._font(fs * 0.55);
      ctx.fillStyle = 'rgba(255,255,255,0.35)';
      ctx.fillText(this._wrap(st.next, W * 0.9)[0] || '', W / 2, y0 + (rows.length - 1) * lh + fs * 0.95);
    }
  }

  // 2. Gran centro: una línea enorme en el centro que aparece y desaparece, y late con el ritmo.
  _center(ctx, W, H, st, beat) {
    const elapsed = st.p * st.dur, left = (1 - st.p) * st.dur;
    const a = clamp01(Math.min(elapsed / 0.25, this.synced ? left / 0.3 : 1));
    const s = 1 + 0.08 * beat;
    const fs = H * 0.11 * this.size;
    ctx.save();
    ctx.translate(W / 2, H / 2);
    ctx.scale(s, s);
    ctx.font = this._font(fs);
    const rows = this._wrap(st.text, (W * 0.82) / s);
    const lh = fs * 1.12;
    ctx.globalAlpha = a;
    ctx.fillStyle = '#fff';
    rows.forEach((row, i) => ctx.fillText(row, 0, (i - (rows.length - 1) / 2) * lh + fs * 0.35));
    ctx.restore();
  }

  // 3. Desplazamiento: varias líneas como un teleprompter; la actual resaltada y el resto atenuado.
  _scroll(ctx, W, H, st) {
    const fs = H * 0.06 * this.size;
    const lh = fs * 1.7;
    // Antes de cambiar de línea, desliza suavemente hacia la siguiente.
    let s = this.synced ? clamp01((st.p * st.dur - (st.dur - 0.35)) / 0.35) : 0;
    s = s * s * (3 - 2 * s);
    const ci = st.idx + s;
    for (let k = Math.floor(ci) - 4; k <= Math.floor(ci) + 5; k++) {
      if (k < 0 || k >= this.lines.length) continue;
      const d = Math.abs(k - ci);
      const isCur = k === st.idx;
      const size = isCur ? fs * 1.25 : fs;
      ctx.font = this._font(size);
      const text = this.lines[k].text;
      if (!text) continue;
      const w = ctx.measureText(text).width;
      const sc = Math.min(1, (W * 0.9) / w);
      ctx.save();
      ctx.translate(W / 2, H / 2 + (k - ci) * lh + size * 0.35);
      ctx.scale(sc, sc);
      ctx.globalAlpha = isCur ? 1 : Math.max(0, 0.55 - d * 0.1);
      ctx.fillStyle = '#fff';
      ctx.fillText(text, 0, 0);
      ctx.restore();
    }
  }

  // 4. Palabra a palabra: cada palabra aparece sola y enorme, con la línea completa abajo como guía.
  _word(ctx, W, H, st, beat) {
    const words = (st.text).split(/\s+/).filter(Boolean);
    const n = words.length;
    const wi = Math.min(n - 1, Math.floor(st.p * n));
    const local = st.p * n - wi;
    const pop = 1 + 0.25 * (1 - Math.min(1, local * 4)) + 0.1 * beat;
    let fs = H * 0.17 * this.size;
    ctx.font = this._font(fs);
    const w = ctx.measureText(words[wi]).width;
    const fit = Math.min(1, (W * 0.88) / (w * pop));
    ctx.save();
    ctx.translate(W / 2, H * 0.5);
    ctx.scale(pop * fit, pop * fit);
    ctx.fillStyle = '#fff';
    ctx.fillText(words[wi], 0, fs * 0.35);
    ctx.restore();
    // Línea completa de guía con la palabra actual resaltada.
    fs = H * 0.035 * this.size;
    ctx.font = this._font(fs);
    const gap = ctx.measureText(' ').width;
    const ws = words.map((x) => ctx.measureText(x).width);
    let x = (W - (ws.reduce((a, b) => a + b, 0) + gap * (n - 1))) / 2;
    ctx.textAlign = 'left';
    words.forEach((word, i) => {
      ctx.fillStyle = i === wi ? '#fff' : 'rgba(255,255,255,0.35)';
      ctx.fillText(word, x, H * 0.9);
      x += ws[i] + gap;
    });
  }

  // 5. Palabras dispersas: las palabras saltan a posiciones y ángulos al azar por toda la pantalla.
  _scatter(ctx, W, H, st, beat) {
    const words = (st.text).split(/\s+/).filter(Boolean);
    const n = words.length;
    const seed = (st.idx + 1) * 97;
    words.forEach((word, k) => {
      const appear = k / n;
      if (st.p < appear) return;
      const age = (st.p - appear) * st.dur;
      const r = (j) => rnd(seed + k * 13 + j);
      const pop = age < 0.15 ? (age / 0.15) * 1.25 : 1 + 0.25 * Math.max(0, 1 - (age - 0.15) * 5);
      const fs = H * (0.07 + 0.08 * r(4)) * this.size;
      ctx.save();
      ctx.translate(W * (0.15 + 0.7 * r(1)), H * (0.2 + 0.62 * r(2)));
      ctx.rotate((r(3) - 0.5) * 0.7);
      ctx.scale(pop + 0.08 * beat, pop + 0.08 * beat);
      ctx.font = this._font(fs);
      ctx.fillStyle = r(5) > 0.6 ? '#fff' : 'rgba(255,255,255,0.75)';
      ctx.fillText(word, 0, 0);
      ctx.restore();
    });
  }
}
