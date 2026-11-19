// Análisis de audio en vivo: bandas (graves / medios / agudos), detección de golpes y BPM.

class AudioEngine {
  constructor() {
    this.ctx = null;
    this.analyser = null;
    this.source = null;
    this.stream = null;
    this.mediaEl = null;
    this.data = null;
    this.sensitivity = 1;

    this.bands = { bass: 0, mid: 0, high: 0, level: 0 };
    this._floor = { bass: 1, mid: 1, high: 1, level: 1 };
    this._peak = { bass: 0, mid: 0, high: 0, level: 0 };
    this._bassAvg = 0;
    this._prevBass = 0;

    this.beat = 0;          // envolvente 1 -> 0
    this.lastBeat = 0;
    this.intervals = [];
    this.bpm = 0;
    this.onBeat = null;
    this.label = 'sin audio';
  }

  _ensureCtx() {
    if (!this.ctx) {
      this.ctx = new (window.AudioContext || window.webkitAudioContext)();
      this.analyser = this.ctx.createAnalyser();
      this.analyser.fftSize = 2048;
      this.analyser.smoothingTimeConstant = 0.55;
      this.data = new Uint8Array(this.analyser.frequencyBinCount);
      // Memoria circular con los últimos 12 s de audio, para reconocer la canción al instante.
      this.ringSec = 12;
      this.ring = new Float32Array(Math.ceil(this.ctx.sampleRate * this.ringSec));
      this.ringPos = 0;
      this.ringFilled = 0;
      this.ringTime = 0;
      this.tap = this.ctx.createScriptProcessor(4096, 1, 1);
      this.tap.onaudioprocess = (e) => {
        const x = e.inputBuffer.getChannelData(0);
        const r = this.ring;
        for (let i = 0; i < x.length; i++) { r[this.ringPos] = x[i]; this.ringPos = (this.ringPos + 1) % r.length; }
        this.ringFilled = Math.min(r.length, this.ringFilled + x.length);
        this.ringTime = performance.now();
      };
      const mute = this.ctx.createGain();
      mute.gain.value = 0;
      this.tap.connect(mute);
      mute.connect(this.ctx.destination);   // necesario para que el procesador funcione
    }
    if (this.ctx.state === 'suspended') this.ctx.resume();
  }

  _disconnect() {
    if (this.source) { try { this.source.disconnect(); } catch (e) { /* ignore */ } }
    if (this.stream) this.stream.getTracks().forEach((t) => t.stop());
    if (this.mediaEl) { this.mediaEl.pause(); URL.revokeObjectURL(this.mediaEl.src); }
    this.source = this.stream = this.mediaEl = null;
  }

  // Entrada de audio en vivo (micrófono, interfaz de audio, línea de la consola...).
  async useDevice(deviceId) {
    this._ensureCtx();
    this._disconnect();
    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        deviceId: deviceId ? { exact: deviceId } : undefined,
        // Para música hay que desactivar todo el procesado pensado para voz.
        echoCancellation: false,
        noiseSuppression: false,
        autoGainControl: false,
      },
    });
    this.source = this.ctx.createMediaStreamSource(this.stream);
    this.source.connect(this.analyser);
    this.source.connect(this.tap);
    this.ringFilled = 0;
    this.label = this.stream.getAudioTracks()[0]?.label || 'entrada de audio';
  }

  // Archivo de audio (para ensayar sin banda). Se escucha por los altavoces.
  useFile(file) {
    this._ensureCtx();
    this._disconnect();
    this.mediaEl = new Audio(URL.createObjectURL(file));
    this.mediaEl.loop = true;
    this.source = this.ctx.createMediaElementSource(this.mediaEl);
    this.source.connect(this.analyser);
    this.source.connect(this.tap);
    this.ringFilled = 0;
    this.source.connect(this.ctx.destination);
    this.mediaEl.play();
    this.label = file.name;
  }

  // Últimos `sec` segundos de audio. Devuelve { samples, sampleRate, endTime } o null si no hay bastante.
  recent(sec) {
    if (!this.source || !this.ring) return null;
    const n = Math.min(this.ringFilled, Math.floor(sec * this.ctx.sampleRate));
    if (n < this.ctx.sampleRate * 3) return null;
    const out = new Float32Array(n);
    const r = this.ring;
    let start = (this.ringPos - n + r.length) % r.length;
    for (let i = 0; i < n; i++) out[i] = r[(start + i) % r.length];
    return { samples: out, sampleRate: this.ctx.sampleRate, endTime: this.ringTime };
  }

  get hasFile() { return !!this.mediaEl; }
  get paused() { return !this.mediaEl || this.mediaEl.paused; }

  // Pausa / reanuda la canción de prueba. Devuelve true si queda sonando.
  togglePause() {
    if (!this.mediaEl) return false;
    if (this.ctx.state === 'suspended') this.ctx.resume();
    if (this.mediaEl.paused) this.mediaEl.play(); else this.mediaEl.pause();
    return !this.mediaEl.paused;
  }

  // Golpe manual (tecla T) por si el audio falla.
  tap(now) { this._triggerBeat(now); }

  _triggerBeat(now) {
    const dt = now - this.lastBeat;
    if (dt > 250 && dt < 1500) {
      this.intervals.push(dt);
      if (this.intervals.length > 12) this.intervals.shift();
      const sorted = [...this.intervals].sort((a, b) => a - b);
      this.bpm = Math.round(60000 / sorted[Math.floor(sorted.length / 2)]);
    }
    this.lastBeat = now;
    this.beat = 1;
    if (this.onBeat) this.onBeat();
  }

  update(now, dt) {
    this.beat *= Math.exp(-dt * 7);
    if (!this.analyser || !this.source) {
      for (const k in this.bands) this.bands[k] *= 0.9;
      return;
    }
    this.analyser.getByteFrequencyData(this.data);
    const hz = this.ctx.sampleRate / this.analyser.fftSize;
    const avg = (f0, f1) => {
      const a = Math.max(1, Math.floor(f0 / hz));
      const b = Math.min(this.data.length - 1, Math.ceil(f1 / hz));
      let s = 0;
      for (let i = a; i <= b; i++) s += this.data[i];
      return s / ((b - a + 1) * 255);
    };
    const raw = { bass: avg(20, 150), mid: avg(150, 2000), high: avg(2000, 10000) };
    raw.level = (raw.bass + raw.mid + raw.high) / 3;

    // Auto-ganancia: cada banda se normaliza entre su mínimo y máximo recientes,
    // así funciona igual con un micrófono lejano que con la línea de la consola.
    const norm = {};
    for (const k in raw) {
      const v = raw[k];
      this._floor[k] = v < this._floor[k] ? v : this._floor[k] + (v - this._floor[k]) * 0.002;
      this._peak[k] = v > this._peak[k] ? v : this._peak[k] - (this._peak[k] - v) * 0.002;
      const range = Math.max(this._peak[k] - this._floor[k], 0.04);
      norm[k] = Math.min(1.5, Math.max(0, ((v - this._floor[k]) / range) * this.sensitivity));
      const prev = this.bands[k];
      this.bands[k] = prev + (norm[k] - prev) * (norm[k] > prev ? 0.6 : 0.15);
    }

    // Detección de golpe: subida brusca de graves sobre su media reciente.
    const nb = norm.bass;
    this._bassAvg = this._bassAvg * 0.95 + nb * 0.05;
    const rising = nb - this._prevBass > 0.04;
    this._prevBass = nb;
    if (nb > 0.45 && nb > this._bassAvg + 0.22 / this.sensitivity && rising && now - this.lastBeat > 220) {
      this._triggerBeat(now);
    }
  }
}
