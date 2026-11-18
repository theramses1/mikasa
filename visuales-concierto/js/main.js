// Interfaz del operador: 3 cámaras, mezclador, audio, 20 efectos y ventana del proyector.

const $ = (id) => document.getElementById(id);
const canvas = $('out');

let renderer;
try {
  renderer = new Renderer(canvas);
} catch (err) {
  $('status').textContent = err.message;
  $('status').classList.add('error');
  throw err;
}
const audio = new AudioEngine();

const state = {
  started: false,
  effect: 0,
  intensity: 0.7,
  hue: 0.8,
  mirror: false,
  strobe: false,
  blackout: false,
  autoCycle: false,
  autoEvery: 16,
  beatsSinceSwitch: 0,
  master: 1,
  // mezclador de cámaras
  layout: 0,
  sel: 0,
  prevSel: 0,
  fade: 1,
  camFade: true,
  autoCam: false,
  autoCamEvery: 4,
  beatsSinceCam: 0,
};

// ---------- Cámaras (3 ranuras) ----------
const slots = [0, 1, 2].map((i) => {
  const video = document.createElement('video');
  video.muted = true;
  video.playsInline = true;
  return { i, video, stream: null, info: '', error: '' };
});
const slotReady = (s) => s.stream && s.video.readyState >= 2 && s.video.videoWidth > 0;

// ---------- Simulador: 3 patrones de prueba distintos ----------
const testCanvases = [0, 1, 2].map(() => {
  const c = document.createElement('canvas');
  c.width = 640; c.height = 360;
  return c;
});
function drawTestPattern(c, t, v) {
  const ctx = c.getContext('2d');
  const w = c.width, h = c.height;
  const base = v * 120;
  const g = ctx.createLinearGradient(0, 0, w, h);
  g.addColorStop(0, `hsl(${(t * 20 + base) % 360},70%,25%)`);
  g.addColorStop(1, `hsl(${(t * 20 + base + 160) % 360},70%,12%)`);
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, w, h);
  // "músico" estilizado que se mueve, para ver bordes y estelas
  const x = w / 2 + Math.sin(t * (1.1 + v * 0.4) + v * 2) * w * 0.25;
  ctx.fillStyle = '#f4d7c0';
  ctx.beginPath(); ctx.arc(x, h * 0.35, 32, 0, Math.PI * 2); ctx.fill();
  ctx.fillStyle = ['#222', '#1d2b55', '#4a1d3a'][v];
  ctx.fillRect(x - 40, h * 0.35 + 36, 80, 120);
  ctx.save(); ctx.translate(x + 40, h * 0.35 + 50); ctx.rotate(Math.sin(t * (4 + v)) * 0.8);
  ctx.fillStyle = ['#c33', '#3c3', '#39f'][v]; ctx.fillRect(0, -6, 90, 12); ctx.restore();
  for (let i = 0; i < 6; i++) {
    ctx.fillStyle = `hsla(${i * 60 + base},90%,60%,.8)`;
    ctx.fillRect((i * 110 + t * 60 * (v + 1)) % (w + 100) - 100, h - 40, 60, 40);
  }
  ctx.fillStyle = 'rgba(255,255,255,.85)';
  ctx.font = 'bold 34px system-ui, sans-serif';
  ctx.fillText(`CÁM ${v + 1}`, 18, 46);
}

// ---------- Dispositivos ----------
async function listDevices() {
  const devices = await navigator.mediaDevices.enumerateDevices();
  const cams = devices.filter((d) => d.kind === 'videoinput');
  const mics = devices.filter((d) => d.kind === 'audioinput');
  const camGroups = new Set(cams.map((d) => d.groupId).filter(Boolean));

  for (let i = 0; i < 3; i++) {
    const sel = $(`cam${i}`);
    const prev = sel.value;
    sel.innerHTML = '<option value="">— ninguna —</option>';
    cams.forEach((d, k) => sel.add(new Option(d.label || `Cámara ${k + 1}`, d.deviceId)));
    if ([...sel.options].some((o) => o.value === prev)) sel.value = prev;
  }

  const msel = $('micSelect');
  const prevMic = msel.value;
  msel.innerHTML = '<option value="">— sin audio —</option>';
  mics.forEach((d, k) => {
    // El micrófono de una cámara USB comparte groupId con su video.
    const icon = camGroups.has(d.groupId) ? '🎥' : '💻';
    msel.add(new Option(`${icon} ${d.label || `Micrófono ${k + 1}`}`, d.deviceId));
  });
  if ([...msel.options].some((o) => o.value === prevMic)) msel.value = prevMic;
  return { cams, mics };
}

async function startCamera(slot, deviceId) {
  const s = slots[slot];
  if (s.stream) s.stream.getTracks().forEach((t) => t.stop());
  s.stream = null;
  s.video.srcObject = null;
  s.info = '';
  s.error = '';
  if (!deviceId) return;
  const [w, h] = $('camRes').value.split('x').map(Number);
  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      video: { deviceId: { exact: deviceId }, width: { ideal: w }, height: { ideal: h }, frameRate: { ideal: 60 } },
      audio: false,
    });
    s.stream = stream;
    s.video.srcObject = stream;
    await s.video.play();
    const st = stream.getVideoTracks()[0].getSettings();
    s.info = `${st.width}×${st.height}@${Math.round(st.frameRate || 0)}`;
  } catch (err) {
    console.error(err);
    s.error = err.name === 'NotReadableError'
      ? 'no se pudo abrir (¿la usa otra app, o falta ancho de banda USB? prueba 720p u otro puerto)'
      : err.message;
  }
}

function setStatus(msg, error = false) {
  $('status').textContent = msg;
  $('status').classList.toggle('error', error);
}

function reportStatus() {
  const parts = slots.map((s) => {
    if (s.error) return `Cám ${s.i + 1}: ${s.error}`;
    if (s.stream) return `Cám ${s.i + 1}: ${s.info}`;
    return null;
  }).filter(Boolean);
  const anyErr = slots.some((s) => s.error);
  if (!parts.length) parts.push('Sin cámaras: modo simulador');
  parts.push(`Audio: ${audio.label}`);
  setStatus(parts.join(' · '), anyErr);
}

$('startBtn').addEventListener('click', async () => {
  try {
    if (!state.started) {
      state.started = true;
      // Pedir permiso una vez para que el navegador muestre los nombres de los dispositivos.
      const tmp = await navigator.mediaDevices.getUserMedia({ video: true, audio: true }).catch(() =>
        navigator.mediaDevices.getUserMedia({ video: true }).catch(() => navigator.mediaDevices.getUserMedia({ audio: true })));
      tmp.getTracks().forEach((t) => t.stop());
      const { cams } = await listDevices();
      // Asignar automáticamente las cámaras detectadas a las ranuras vacías.
      const used = new Set([0, 1, 2].map((i) => $(`cam${i}`).value).filter(Boolean));
      const free = cams.filter((c) => !used.has(c.deviceId));
      for (let i = 0; i < 3; i++) {
        if (!$(`cam${i}`).value && free.length) $(`cam${i}`).value = free.shift().deviceId;
      }
      if (!$('micSelect').value && $('micSelect').options[1]) $('micSelect').selectedIndex = 1;
    }
    setStatus('Conectando cámaras…');
    for (let i = 0; i < 3; i++) await startCamera(i, $(`cam${i}`).value);
    if ($('micSelect').value) { await audio.useDevice($('micSelect').value); updatePlayer(); }
    reportStatus();
    $('startBtn').textContent = 'Reconectar / aplicar cambios';
    $('hint').style.display = 'none';
  } catch (err) {
    console.error(err);
    setStatus(`No se pudo iniciar: ${err.message}`, true);
  }
});

for (let i = 0; i < 3; i++) {
  $(`cam${i}`).addEventListener('change', async () => {
    if (!state.started) return;
    await startCamera(i, $(`cam${i}`).value);
    reportStatus();
  });
}
$('camRes').addEventListener('change', async () => {
  if (!state.started) return;
  for (let i = 0; i < 3; i++) if (slots[i].stream) await startCamera(i, $(`cam${i}`).value);
  reportStatus();
});
$('micSelect').addEventListener('change', () => {
  if (!$('micSelect').value) return;
  audio.useDevice($('micSelect').value)
    .then(() => { updatePlayer(); reportStatus(); })
    .catch((e) => setStatus(`Micrófono: ${e.message}`, true));
});
navigator.mediaDevices?.addEventListener?.('devicechange', () => { if (state.started) listDevices(); });

// ---------- Canción de prueba (simulador) ----------
function updatePlayer() {
  $('playerRow').hidden = !audio.hasFile;
  $('playPauseBtn').innerHTML = audio.paused ? '▶ Reproducir <kbd>P</kbd>' : '⏸ Pausa <kbd>P</kbd>';
}
$('audioFile').addEventListener('change', (e) => {
  const f = e.target.files[0];
  if (!f) return;
  audio.useFile(f);
  $('songName').textContent = f.name;
  updatePlayer();
  reportStatus();
});
function togglePlay() {
  if (!audio.hasFile) return;
  audio.togglePause();
  setTimeout(updatePlayer, 50);
}
$('playPauseBtn').addEventListener('click', togglePlay);

// ---------- Mezclador de cámaras ----------
function activeOrder() {
  const real = slots.filter(slotReady).map((s) => s.i);
  return real.length ? { order: real, sim: false } : { order: [0, 1, 2], sim: true };
}
function selectCam(i) {
  const { order } = activeOrder();
  if (!order.includes(i) || i === state.sel) return;
  state.prevSel = state.sel;
  state.sel = i;
  state.fade = state.camFade ? 0 : 1;
  state.beatsSinceCam = 0;
}
for (let i = 0; i < 3; i++) $(`sel${i}`).addEventListener('click', () => selectCam(i));
$('layout').addEventListener('change', (e) => (state.layout = Number(e.target.value)));
$('camFade').addEventListener('change', (e) => (state.camFade = e.target.checked));
$('autoCam').addEventListener('change', (e) => { state.autoCam = e.target.checked; state.beatsSinceCam = 0; });
$('autoCamEvery').addEventListener('change', (e) => (state.autoCamEvery = Number(e.target.value)));

// ---------- Efectos ----------
const keyLabel = (i) => (i < 10 ? '' : '⇧') + String((i % 10) + 1).replace('10', '0');
const effectBtns = EFFECTS.map((e, i) => {
  const b = document.createElement('button');
  b.innerHTML = `<span>${e.name}</span><kbd>${keyLabel(i)}</kbd>`;
  b.addEventListener('click', () => selectEffect(i));
  $('effects').appendChild(b);
  return b;
});
function selectEffect(i) {
  state.effect = (i + EFFECTS.length) % EFFECTS.length;
  state.beatsSinceSwitch = 0;
  effectBtns.forEach((b, j) => b.classList.toggle('active', j === state.effect));
  $('effectDesc').textContent = `${state.effect + 1}. ${EFFECTS[state.effect].desc}`;
}
selectEffect(0);

audio.onBeat = () => {
  if (state.autoCycle && ++state.beatsSinceSwitch >= state.autoEvery) {
    let next;
    do { next = Math.floor(Math.random() * EFFECTS.length); } while (next === state.effect);
    selectEffect(next);
  }
  if (state.autoCam && ++state.beatsSinceCam >= state.autoCamEvery) {
    const { order } = activeOrder();
    if (order.length > 1) selectCam(order[(order.indexOf(state.sel) + 1) % order.length]);
  }
};

// ---------- Controles ----------
function bindRange(id, outId, fn) {
  const el = $(id);
  const apply = () => { fn(parseFloat(el.value)); if (outId) $(outId).textContent = Number(el.value).toFixed(2); };
  el.addEventListener('input', apply);
  apply();
}
bindRange('intensity', 'intensityVal', (v) => (state.intensity = v));
bindRange('sensitivity', 'sensVal', (v) => (audio.sensitivity = v));
bindRange('hue', null, (v) => (state.hue = v));
$('autoCycle').addEventListener('change', (e) => { state.autoCycle = e.target.checked; state.beatsSinceSwitch = 0; });
$('autoEvery').addEventListener('change', (e) => (state.autoEvery = Number(e.target.value)));

function toggle(key) {
  state[key] = !state[key];
  $({ strobe: 'strobeBtn', mirror: 'mirrorBtn', blackout: 'blackoutBtn' }[key]).classList.toggle('on', state[key]);
}
$('strobeBtn').addEventListener('click', () => toggle('strobe'));
$('mirrorBtn').addEventListener('click', () => toggle('mirror'));
$('blackoutBtn').addEventListener('click', () => toggle('blackout'));
$('tapBtn').addEventListener('click', () => audio.tap(performance.now()));

$('outRes').addEventListener('change', () => {
  const [w, h] = $('outRes').value.split('x').map(Number);
  renderer.resize(w, h);
});

function nudge(id, delta) {
  const el = $(id);
  el.value = Math.min(+el.max, Math.max(+el.min, +el.value + delta));
  el.dispatchEvent(new Event('input'));
}
function flipCheckbox(id) {
  $(id).checked = !$(id).checked;
  $(id).dispatchEvent(new Event('change'));
}

function handleKey(e) {
  const tag = e.target && e.target.tagName;
  if (tag === 'SELECT' || (tag === 'INPUT' && e.target.type === 'file')) return;
  const code = e.code || '';
  const k = (e.key || '').toLowerCase();
  if (/^Digit\d$/.test(code)) {
    const n = Number(code[5]);
    selectEffect((n === 0 ? 9 : n - 1) + (e.shiftKey ? 10 : 0));
  } else if (k === 'arrowright') selectEffect(state.effect + 1);
  else if (k === 'arrowleft') selectEffect(state.effect - 1);
  else if (k === 'arrowup') nudge('intensity', 0.05);
  else if (k === 'arrowdown') nudge('intensity', -0.05);
  else if (k === 'z') selectCam(0);
  else if (k === 'x') selectCam(1);
  else if (k === 'c') selectCam(2);
  else if (k === 'v') { $('layout').value = String((state.layout + 1) % 4); $('layout').dispatchEvent(new Event('change')); }
  else if (k === 'n') flipCheckbox('autoCam');
  else if (k === 'a') flipCheckbox('autoCycle');
  else if (k === 'p') togglePlay();
  else if (k === 's') toggle('strobe');
  else if (k === 'm') toggle('mirror');
  else if (k === 'b') toggle('blackout');
  else if (k === 't') audio.tap(performance.now());
  else if (k === 'h') document.body.classList.toggle('ui-hidden');
  else if (k === 'f') toggleFullscreen();
  else return;
  e.preventDefault?.();
}
window.addEventListener('keydown', handleKey);

function toggleFullscreen() {
  if (document.fullscreenElement) document.exitFullscreen();
  else $('stage').requestFullscreen().catch(() => {});
}
$('fullBtn').addEventListener('click', toggleFullscreen);

// ---------- Ventana del proyector ----------
let outWin = null;
let outStream = null;
$('outputBtn').addEventListener('click', async () => {
  if (outWin && !outWin.closed) { outWin.focus(); return; }
  let features = 'width=1280,height=720';
  // Si el navegador permite conocer las pantallas, abrimos directamente en la secundaria.
  try {
    if ('getScreenDetails' in window) {
      const sd = await window.getScreenDetails();
      const ext = sd.screens.find((s) => s !== sd.currentScreen) || null;
      if (ext) features = `left=${ext.availLeft},top=${ext.availTop},width=${ext.availWidth},height=${ext.availHeight}`;
    }
  } catch (e) { /* permiso denegado: ventana normal */ }

  outWin = window.open('', 'proyector', features);
  if (!outWin) { setStatus('El navegador bloqueó la ventana emergente. Permite pop-ups para esta página.', true); return; }
  outStream = outStream || canvas.captureStream(60);
  const d = outWin.document;
  d.open();
  d.write(`<!doctype html><html><head><meta charset="utf-8"><title>Proyector</title>
    <style>html,body{margin:0;height:100%;background:#000;overflow:hidden;cursor:none}
    video{width:100%;height:100%;object-fit:contain;display:block}
    #m{position:fixed;top:12px;left:0;right:0;text-align:center;color:#888;font:14px system-ui;transition:opacity 1s}</style>
    </head><body><video autoplay muted playsinline></video>
    <div id="m">Doble clic = pantalla completa · tecla F también</div></body></html>`);
  d.close();
  const v = d.querySelector('video');
  v.srcObject = outStream;
  v.play().catch(() => {});
  const fs = () => (d.fullscreenElement ? d.exitFullscreen() : d.documentElement.requestFullscreen().catch(() => {}));
  d.addEventListener('dblclick', fs);
  d.addEventListener('keydown', (e) => {
    if (e.key.toLowerCase() === 'f') fs();
    else handleKey({ key: e.key, code: e.code, shiftKey: e.shiftKey, target: null }); // atajos también desde el proyector
  });
  setTimeout(() => { const m = d.getElementById('m'); if (m) m.style.opacity = 0; }, 4000);
});

// ---------- Bucle principal ----------
const meters = { bass: $('mBass'), mid: $('mMid'), high: $('mHigh') };
const beatDot = $('beatDot');
let last = performance.now();
let frames = 0, fpsTime = last;
const t0 = last;

function frame(now) {
  const dt = Math.min(0.1, (now - last) / 1000);
  last = now;
  const time = (now - t0) / 1000;

  audio.update(now, dt);

  // Fuentes: cámaras reales si hay alguna lista; si no, 3 cámaras simuladas.
  const { order, sim } = activeOrder();
  for (const i of order) {
    if (sim) {
      drawTestPattern(testCanvases[i], time, i);
      renderer.uploadSource(i, testCanvases[i], 640, 360);
    } else {
      const v = slots[i].video;
      renderer.uploadSource(i, v, v.videoWidth, v.videoHeight);
    }
  }
  if (!order.includes(state.sel)) { state.sel = state.prevSel = order[0]; state.fade = 1; }
  if (!order.includes(state.prevSel)) state.prevSel = state.sel;
  state.fade = Math.min(1, state.fade + dt / 0.35);

  // Fundido suave a negro / desde negro.
  const target = state.blackout ? 0 : 1;
  state.master += (target - state.master) * Math.min(1, dt * 6);

  const b = audio.bands;
  renderer.render(state.effect, {
    time,
    bass: b.bass, mid: b.mid, high: b.high, level: b.level,
    beat: audio.beat,
    intensity: state.intensity,
    hue: state.hue,
    master: state.master,
    flash: state.strobe ? Math.pow(audio.beat, 3) * 0.9 : 0,
  }, {
    order, sel: state.sel, prevSel: state.prevSel, fade: state.fade, mode: state.layout, mirror: state.mirror,
  });

  meters.bass.style.width = `${Math.min(100, b.bass * 100)}%`;
  meters.mid.style.width = `${Math.min(100, b.mid * 100)}%`;
  meters.high.style.width = `${Math.min(100, b.high * 100)}%`;
  beatDot.style.opacity = 0.1 + audio.beat * 0.9;

  frames++;
  if (now - fpsTime > 1000) {
    $('fps').textContent = frames;
    $('bpm').textContent = audio.bpm || '—';
    frames = 0; fpsTime = now;
    // Indicadores de cámaras (1 vez por segundo basta)
    for (let i = 0; i < 3; i++) {
      const s = slots[i];
      const led = $(`led${i}`);
      led.className = 'led ' + (s.error ? 'err' : slotReady(s) ? 'live' : sim ? 'sim' : '');
      led.title = s.error || s.info || (sim ? 'simulada' : 'sin cámara');
      $(`sel${i}`).disabled = !order.includes(i);
    }
  }
  for (let i = 0; i < 3; i++) $(`sel${i}`).classList.toggle('onair', i === state.sel);
  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);

if (!navigator.mediaDevices?.enumerateDevices) {
  setStatus('Este navegador no permite acceder a cámara/micrófono. Abre la página con Chrome o Edge.', true);
}
