// Reconocimiento de canciones por "huella acústica", 100 % local y gratuito.
//
// Técnica de constelación de picos (la descrita por A. Wang, 2003):
//   1. espectrograma del audio a 11 025 Hz,
//   2. se quedan solo los picos de energía más destacados (robustos a ruido y volumen),
//   3. cada pico se empareja con unos pocos picos posteriores -> hash (f1, f2, Δt),
//   4. para reconocer, se buscan los hashes de la muestra en cada canción y se cuenta
//      cuántos coinciden con el MISMO desfase temporal: ese desfase es además la posición
//      de la muestra dentro de la canción.

const FP = (() => {
  const SR = 11025;           // frecuencia de trabajo
  const N = 1024;             // tamaño de ventana FFT (~93 ms)
  const HOP = 512;            // salto entre ventanas (~46 ms)
  const FMIN = 8, FMAX = 480; // bins útiles (~86 Hz – 5,2 kHz)
  const NB = FMAX - FMIN;
  const PEAKS_PER_FRAME = 4;
  const FANOUT = 5;
  const DT_MIN = 1, DT_MAX = 50, DF_MAX = 120;
  const WF = 10, WT = 4;      // vecindario para considerar un pico (±bins, ±frames)
  const DELTA = 2.0;          // un pico debe superar la media de su frame en e^2 (~8,7 dB)

  const hann = new Float32Array(N);
  for (let i = 0; i < N; i++) hann[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / (N - 1));
  const cosT = new Float32Array(N / 2), sinT = new Float32Array(N / 2);
  for (let i = 0; i < N / 2; i++) { cosT[i] = Math.cos((2 * Math.PI * i) / N); sinT[i] = -Math.sin((2 * Math.PI * i) / N); }
  const rev = new Uint32Array(N);
  for (let i = 0, bits = Math.log2(N); i < N; i++) {
    let r = 0;
    for (let b = 0; b < bits; b++) r |= ((i >> b) & 1) << (bits - 1 - b);
    rev[i] = r;
  }

  function fft(re, im) {
    for (let i = 0; i < N; i++) {
      const j = rev[i];
      if (j > i) { let t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
    }
    for (let size = 2; size <= N; size <<= 1) {
      const half = size >> 1, step = N / size;
      for (let start = 0; start < N; start += size) {
        for (let k = 0; k < half; k++) {
          const a = start + k, b = a + half;
          const wr = cosT[k * step], wi = sinT[k * step];
          const xr = re[b] * wr - im[b] * wi;
          const xi = re[b] * wi + im[b] * wr;
          re[b] = re[a] - xr; im[b] = im[a] - xi;
          re[a] += xr; im[a] += xi;
        }
      }
    }
  }

  // Espectrograma logarítmico: Float32Array [frames * NB]
  function spectrogram(x) {
    const frames = Math.max(0, Math.floor((x.length - N) / HOP) + 1);
    const S = new Float32Array(frames * NB);
    const re = new Float32Array(N), im = new Float32Array(N);
    for (let f = 0; f < frames; f++) {
      const off = f * HOP;
      for (let i = 0; i < N; i++) { re[i] = x[off + i] * hann[i]; im[i] = 0; }
      fft(re, im);
      const row = f * NB;
      for (let k = 0; k < NB; k++) {
        const b = k + FMIN;
        S[row + k] = Math.log(re[b] * re[b] + im[b] * im[b] + 1e-10);
      }
    }
    return { S, frames };
  }

  // Picos locales: [t, f] ordenados por tiempo.
  function findPeaks(S, frames) {
    const M1 = new Float32Array(S.length);  // máximo en frecuencia
    for (let t = 0; t < frames; t++) {
      const row = t * NB;
      for (let k = 0; k < NB; k++) {
        let m = -Infinity;
        const a = Math.max(0, k - WF), b = Math.min(NB - 1, k + WF);
        for (let j = a; j <= b; j++) if (S[row + j] > m) m = S[row + j];
        M1[row + k] = m;
      }
    }
    const peaks = [];
    const cand = [];
    for (let t = 0; t < frames; t++) {
      const row = t * NB;
      let mean = 0;
      for (let k = 0; k < NB; k++) mean += S[row + k];
      mean /= NB;
      cand.length = 0;
      const ta = Math.max(0, t - WT), tb = Math.min(frames - 1, t + WT);
      for (let k = 0; k < NB; k++) {
        const v = S[row + k];
        if (v < mean + DELTA || v < M1[row + k]) continue;   // no es máximo en frecuencia
        let isMax = true;
        for (let u = ta; u <= tb && isMax; u++) if (M1[u * NB + k] > v) isMax = false;
        if (isMax) cand.push([v, k]);
      }
      cand.sort((p, q) => q[0] - p[0]);
      const keep = cand.slice(0, PEAKS_PER_FRAME).map((c) => c[1]).sort((p, q) => p - q);
      for (const k of keep) peaks.push([t, k + FMIN]);
    }
    return peaks;
  }

  function hashPeaks(peaks) {
    const H = [], T = [];
    for (let i = 0; i < peaks.length; i++) {
      const [t1, f1] = peaks[i];
      let n = 0;
      for (let j = i + 1; j < peaks.length && n < FANOUT; j++) {
        const [t2, f2] = peaks[j];
        const dt = t2 - t1;
        if (dt < DT_MIN) continue;
        if (dt > DT_MAX) break;
        if (Math.abs(f2 - f1) > DF_MAX) continue;
        H.push(((f1 << 15) | (f2 << 6) | dt) >>> 0);
        T.push(t1);
        n++;
      }
    }
    return { hashes: Uint32Array.from(H), times: Uint32Array.from(T) };
  }

  // Huella de un audio mono a 11 025 Hz.
  function fingerprint(x) {
    const { S, frames } = spectrogram(x);
    return hashPeaks(findPeaks(S, frames));
  }

  // Ordena por hash para poder buscar con búsqueda binaria (y guardarlo compacto).
  function buildIndex(fp) {
    const n = fp.hashes.length;
    const order = Array.from({ length: n }, (_, i) => i).sort((a, b) => fp.hashes[a] - fp.hashes[b]);
    const h = new Uint32Array(n), t = new Uint32Array(n);
    order.forEach((o, i) => { h[i] = fp.hashes[o]; t[i] = fp.times[o]; });
    return { h, t };
  }

  function lowerBound(arr, v) {
    let lo = 0, hi = arr.length;
    while (lo < hi) { const mid = (lo + hi) >>> 1; if (arr[mid] < v) lo = mid + 1; else hi = mid; }
    return lo;
  }

  // Compara una muestra con las canciones [{ id, h, t }]. Devuelve candidatos ordenados.
  function match(query, songs) {
    const out = [];
    for (const song of songs) {
      const counts = new Map();
      for (let i = 0; i < query.hashes.length; i++) {
        const hv = query.hashes[i], tq = query.times[i];
        for (let j = lowerBound(song.h, hv); j < song.h.length && song.h[j] === hv; j++) {
          const off = song.t[j] - tq;
          counts.set(off, (counts.get(off) || 0) + 1);
        }
      }
      let best = 0, bestOff = 0;
      for (const [off, c] of counts) {
        const s = c + Math.max(counts.get(off - 1) || 0, counts.get(off + 1) || 0);
        if (s > best) { best = s; bestOff = off; }
      }
      out.push({ id: song.id, score: best, offset: bestOff });
    }
    return out.sort((a, b) => b.score - a.score);
  }

  return { SR, HOP, frameSec: HOP / SR, fingerprint, buildIndex, match };
})();

if (typeof module !== 'undefined') module.exports = FP;
