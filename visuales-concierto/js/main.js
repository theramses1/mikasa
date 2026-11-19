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
// La elección del usuario se recuerda por id y por nombre: en Chrome los ids pueden cambiar
// (sobre todo abriendo index.html como archivo) y la lista puede llegar sin nombres.
const store = {
  load() { try { return JSON.parse(localStorage.getItem('visuales.devices') || '{}'); } catch (e) { return {}; } },
  save() {
    try {
      const lab = (sel) => (sel.value ? sel.selectedOptions[0]?.dataset.label || '' : '');
      localStorage.setItem('visuales.devices', JSON.stringify({
        cams: [0, 1, 2].map((i) => lab($(`cam${i}`))),
        mic: lab($('micSelect')),
      }));
    } catch (e) { /* almacenamiento no disponible */ }
  },
};

function choiceOf(sel) {
  return { id: sel.value, label: sel.value ? sel.selectedOptions[0]?.dataset.label || '' : '' };
}
function restoreChoice(sel, choice) {
  const opts = [...sel.options].filter((o) => o.value);
  const hit = opts.find((o) => o.value === choice.id) || (choice.label && opts.find((o) => o.dataset.label === choice.label));
  sel.value = hit ? hit.value : '';
}
function fillSelect(sel, devices, emptyText, nameOf) {
  const choice = choiceOf(sel);
  sel.innerHTML = `<option value="">${emptyText}</option>`;
  devices.forEach((d, k) => {
    const o = new Option(nameOf(d, k), d.deviceId);
    o.dataset.label = d.label;
    sel.add(o);
  });
  restoreChoice(sel, choice);
}

let savedOnce = false;
// Últimos dispositivos conocidos de cada tipo. Chrome concede cámara y micrófono por separado:
// sin permiso para un tipo, lo devuelve como una entrada vacía (sin id ni nombre). En ese caso
// se conserva la lista anterior de ese tipo en vez de borrarla.
const known = { videoinput: [], audioinput: [] };

async function listDevices() {
  const devices = await navigator.mediaDevices.enumerateDevices();
  const blindKinds = [];
  for (const kind of ['videoinput', 'audioinput']) {
    const all = devices.filter((d) => d.kind === kind);
    const usable = all.filter((d) => d.deviceId);
    if (usable.length) known[kind] = usable.map((d) => ({ deviceId: d.deviceId, label: d.label, groupId: d.groupId }));
    else if (all.length) blindKinds.push(kind);   // hay dispositivos pero sin permiso: no tocar
    else known[kind] = [];                       // realmente no hay ninguno
  }
  const cams = known.videoinput, mics = known.audioinput;
  const camGroups = new Set(cams.map((d) => d.groupId).filter(Boolean));

  for (let i = 0; i < 3; i++) fillSelect($(`cam${i}`), cams, '— ninguna —', (d, k) => d.label || `Cámara ${k + 1}`);
  // El micrófono de una cámara USB comparte groupId con su video.
  fillSelect($('micSelect'), mics, '— sin audio —',
    (d, k) => `${camGroups.has(d.groupId) ? '🎥' : '💻'} ${d.label || `Micrófono ${k + 1}`}`);

  // La primera vez, recuperar lo que se eligió en la sesión anterior.
  if (!savedOnce && cams.some((d) => d.label)) {
    savedOnce = true;
    const saved = store.load();
    (saved.cams || []).forEach((label, i) => {
      if (label && !$(`cam${i}`).value) { restoreChoice($(`cam${i}`), { id: '', label }); slots[i].manual = true; }
    });
    if (saved.mic && !$('micSelect').value) restoreChoice($('micSelect'), { id: '', label: saved.mic });
  }
  return { cams, mics, blind: blindKinds.length === 2, blindKinds };
}

// Pide permiso de cámara y micrófono (por separado si hace falta) y deja el permiso activo
// mientras se listan los dispositivos. Devuelve los streams temporales para cerrarlos después.
async function requestAccess() {
  const md = navigator.mediaDevices;
  const streams = [];
  try { streams.push(await md.getUserMedia({ video: true, audio: true })); } catch (e) {
    try { streams.push(await md.getUserMedia({ video: true })); } catch (e2) { /* sin cámara */ }
    try { streams.push(await md.getUserMedia({ audio: true })); } catch (e3) { /* sin micrófono */ }
  }
  return streams;
}
const stopAll = (streams) => streams.forEach((st) => st.getTracks().forEach((t) => t.stop()));

async function startCamera(slot, deviceId, retried = false) {
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
    s.deviceId = deviceId;
    s.video.srcObject = stream;
    // Si el cable se suelta o el teléfono sale del modo webcam, marcarlo en rojo.
    stream.getVideoTracks()[0].addEventListener('ended', () => {
      if (s.stream === stream) { s.stream = null; s.error = 'se desconectó'; reportStatus(); }
    });
    await s.video.play();
    const st = stream.getVideoTracks()[0].getSettings();
    s.info = `${st.width}×${st.height}@${Math.round(st.frameRate || 0)}`;
  } catch (err) {
    console.error(err);
    // El id de la cámara cambió (Chrome lo renueva a veces): buscarla por su nombre y reintentar.
    if (!retried && (err.name === 'OverconstrainedError' || err.name === 'NotFoundError')) {
      const sel = $(`cam${slot}`);
      const choice = choiceOf(sel);
      await listDevices();
      restoreChoice(sel, choice);
      if (sel.value) return startCamera(slot, sel.value, true);
    }
    s.error = err.name === 'NotReadableError'
      ? 'no se pudo abrir (¿la usa otra app, o falta ancho de banda USB? prueba 720p u otro puerto)'
      : err.message;
  }
}

let camCount = -1;

// Pone las cámaras detectadas que no se usan en las ranuras vacías. Devuelve las ranuras asignadas.
function autoAssign(cams) {
  const used = new Set([0, 1, 2].map((i) => $(`cam${i}`).value).filter(Boolean));
  const free = cams.filter((c) => !used.has(c.deviceId));
  const assigned = [];
  for (let i = 0; i < 3; i++) {
    if (!slots[i].manual && !$(`cam${i}`).value && free.length) { $(`cam${i}`).value = free.shift().deviceId; assigned.push(i); }
  }
  return assigned;
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
  if (!parts.length) {
    parts.push(camCount === 0
      ? 'El navegador no detecta ninguna cámara (revisa cable, modo Webcam en el teléfono y permisos)'
      : 'Sin cámaras: modo simulador');
  }
  parts.push(`Audio: ${audio.label}`);
  setStatus(parts.join(' · '), anyErr);
}

$('startBtn').addEventListener('click', async () => {
  let tmp = [];
  try {
    state.started = true;
    setStatus('Pidiendo permiso de cámara y micrófono…');
    // El permiso se mantiene abierto hasta conectar todo: así Chrome no lo retira a mitad.
    tmp = await requestAccess();
    if (!tmp.length) throw new Error('Chrome no dio permiso de cámara ni de micrófono. Revisa el icono de la izquierda de la barra de direcciones.');
    const { cams } = await listDevices();
    camCount = cams.length;
    autoAssign(cams);
    if (!$('micSelect').value && !state.micManual && $('micSelect').options[1]) $('micSelect').selectedIndex = 1;
    setStatus('Conectando cámaras…');
    for (let i = 0; i < 3; i++) await startCamera(i, $(`cam${i}`).value);
    if ($('micSelect').value) await startMic();
    stopAll(tmp);
    tmp = [];
    await listDevices();   // con las cámaras ya abiertas los nombres son definitivos
    store.save();
    reportStatus();
    $('startBtn').textContent = 'Reconectar / aplicar cambios';
    $('hint').style.display = 'none';
  } catch (err) {
    console.error(err);
    setStatus(`No se pudo iniciar: ${err.message}`, true);
  } finally {
    stopAll(tmp);
  }
});

$('rescanBtn').addEventListener('click', async () => {
  const tmp = await requestAccess();
  const { cams, mics } = await listDevices();
  stopAll(tmp);
  camCount = cams.length;
  setStatus(`Encontrados: ${cams.length} cámara(s) y ${mics.length} micrófono(s)` +
    (cams.length ? '' : ' · Si falta el teléfono, revisa el cable y el modo Webcam'), !cams.length);
});

for (let i = 0; i < 3; i++) {
  $(`cam${i}`).addEventListener('change', async () => {
    slots[i].manual = true;   // respetar la elección (también "ninguna")
    store.save();
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
  state.micManual = true;
  store.save();
  if (!$('micSelect').value || !state.started) return;
  startMic().then(reportStatus);
});

// Abre el micrófono elegido; si su id cambió, lo busca por nombre.
async function startMic() {
  const sel = $('micSelect');
  try {
    await audio.useDevice(sel.value);
  } catch (err) {
    if (err.name !== 'OverconstrainedError' && err.name !== 'NotFoundError') { setStatus(`Micrófono: ${err.message}`, true); return; }
    const choice = choiceOf(sel);
    await listDevices();
    restoreChoice(sel, choice);
    if (sel.value) await audio.useDevice(sel.value).catch((e) => setStatus(`Micrófono: ${e.message}`, true));
  }
  updatePlayer();
}
// Cámaras conectadas o desconectadas con la página abierta (p. ej. el teléfono pasa a modo webcam).
navigator.mediaDevices?.addEventListener?.('devicechange', async () => {
  if (!state.started) return;
  const { cams, blind } = await listDevices();
  if (blind) return;
  camCount = cams.length;
  // Las desconexiones las detecta el evento 'ended' de cada cámara; aquí solo se suman las nuevas.
  for (const i of autoAssign(cams)) await startCamera(i, $(`cam${i}`).value);
  reportStatus();
});

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
  lyrics.resize(w / 2, h / 2);
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
  // No robar teclas mientras se escribe (búsqueda de letras, clave) o se usa un desplegable.
  if (tag === 'SELECT' || tag === 'TEXTAREA' || (tag === 'INPUT' && ['file', 'text', 'password', 'search'].includes(e.target.type))) return;
  if ($('songDialog').open) return;   // editando una canción
  const code = e.code || '';
  const k = (e.key || '').toLowerCase();
  // Banda en vivo: Espacio / Enter / → / AvPág (clickers) avanzan la letra; ← / RePág / Retroceso retroceden.
  if (state.lyrMode === 'live' && !e.ctrlKey && !e.altKey && !e.metaKey) {
    let dir = 0;
    if (k === 'pagedown' && e.shiftKey) { stepSong(1); dir = 2; }
    else if (k === 'pageup' && e.shiftKey) { stepSong(-1); dir = 2; }
    else if ([' ', 'enter', 'arrowright', 'pagedown'].includes(k)) dir = 1;
    else if (['arrowleft', 'pageup', 'backspace'].includes(k)) dir = -1;
    if (dir) {
      if (dir !== 2) liveStep(dir);
      e.preventDefault?.();
      if (document.activeElement && document.activeElement !== document.body) document.activeElement.blur();
      return;
    }
  }
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
  else if (k === 'l') flipCheckbox('lyrVisible');
  else if (k === 'y') cycleSelect('lyrDisplay');
  else if (k === 'u') cycleSelect('lyrFx');
  else if (k === 'i') identifySong(false);
  else if (k === '[') stepSong(-1);
  else if (k === ']') stepSong(1);
  else if (k === 'j') lyrics.step(-1, performance.now());
  else if (k === 'k') lyrics.step(1, performance.now());
  else if (k === ',') lyrics.nudge(-0.25);
  else if (k === '.') lyrics.nudge(0.25);
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
  // Capa de letras: solo se sube a la GPU si hay algo que mostrar.
  const lyrOn = lyrics.draw(now, audio.beat);
  if (lyrOn) renderer.uploadLyrics(lyrics.canvas);
  if (frames % 6 === 0) $('lyrOffset').textContent = `desfase ${lyrics.offset >= 0 ? '+' : ''}${lyrics.offset.toFixed(2).replace('.', ',')} s`;

  renderer.render(state.effect, {
    time,
    bass: b.bass, mid: b.mid, high: b.high, level: b.level,
    beat: audio.beat,
    intensity: state.intensity,
    hue: state.hue,
    master: state.master,
    flash: state.strobe ? Math.pow(audio.beat, 3) * 0.9 : 0,
    lyrOn,
    lyrFx: lyrics.effect,
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
if (location.protocol === 'file:') $('fileTip').hidden = false;

// Si Chrome ya tiene permiso, mostrar los dispositivos sin esperar a "Iniciar".
(async () => {
  try {
    const q = await navigator.permissions.query({ name: 'camera' });
    if (q.state === 'granted') await listDevices();
  } catch (e) { /* el navegador no permite consultarlo */ }
})();

// ---------- Letras ----------
const lyrics = new LyricsEngine(audio, canvas.width / 2, canvas.height / 2);

function cycleSelect(id) {
  const sel = $(id);
  sel.selectedIndex = (sel.selectedIndex + 1) % sel.options.length;
  sel.dispatchEvent(new Event('change'));
}
LYRIC_DISPLAYS.forEach((name, i) => $('lyrDisplay').add(new Option(`${i + 1}. ${name}`, i)));
LYRIC_EFFECTS.forEach((name, i) => $('lyrFx').add(new Option(`${i + 1}. ${name}`, i)));
$('lyrDisplay').addEventListener('change', (e) => (lyrics.display = Number(e.target.value)));
$('lyrFx').addEventListener('change', (e) => (lyrics.effect = Number(e.target.value)));
$('lyrVisible').addEventListener('change', (e) => (lyrics.visible = e.target.checked));
bindRange('lyrSize', 'lyrSizeVal', (v) => (lyrics.size = v));

function lyrStatus(msg, error = false) {
  $('lyrStatus').textContent = msg;
  $('lyrStatus').classList.toggle('error', error);
}
function showSong() {
  $('lyrSong').textContent = lyrics.title ? `🎵 ${lyrics.title}` : 'Sin canción';
}

// ---------- Repertorio y reconocimiento local ----------
const library = new SongLibrary(lyrics);
let currentSongId = null;     // canción del repertorio cuya letra se muestra
let pending = null;           // coincidencia dudosa esperando confirmación

const songName = (s) => [s.artist, s.title].filter(Boolean).join(' – ') || s.file || 'Sin nombre';
function lyricsBadge(s) {
  const t = s.track;
  if (!t) return 'sin letra';
  if (t.instrumental) return 'instrumental';
  return t.syncedLyrics ? '✓ sincronizada' : t.plainLyrics ? 'solo texto' : 'sin letra';
}
function renderLibrary() {
  const box = $('libList');
  box.innerHTML = '';
  $('libCount').textContent = library.songs.length ? `${library.songs.length} canción(es)` : '';
  if (!library.songs.length) {
    box.innerHTML = state.lyrMode === 'live'
      ? '<p class="empty">Todavía no hay canciones. Pulsa ➕ Añadir canción.</p>'
      : '<p class="empty">Todavía no hay canciones. Añade los audios del repertorio con 🎵.</p>';
    return;
  }
  library.songs.forEach((s, i) => {
    const row = document.createElement('div');
    row.className = 'song-row' + (s.id === currentSongId ? ' playing' : '');
    row.innerHTML = `<button title="Mostrar su letra ahora">▶</button><span class="name"></span>
      <span class="badge"></span><button class="mv" title="Subir en el setlist">↑</button><button class="mv" title="Bajar en el setlist">↓</button>
      <button title="Editar nombre y letra">✎</button><button title="Quitar del setlist">🗑</button>`;
    row.querySelector('.name').textContent = `${i + 1}. ${songName(s)}`;
    row.querySelector('.name').title = songName(s);
    row.querySelector('.badge').textContent = lyricsBadge(s) + (s.h && s.h.length ? ' · 🎵' : '');
    const [play, up, down, edit, del] = row.querySelectorAll('button');
    play.addEventListener('click', () => showSongLyrics(s));
    up.addEventListener('click', async () => { await library.move(s.id, -1); renderLibrary(); });
    down.addEventListener('click', async () => { await library.move(s.id, 1); renderLibrary(); });
    edit.addEventListener('click', () => editSong(s));
    del.addEventListener('click', async () => {
      if (!confirm(`¿Quitar «${songName(s)}» del repertorio?`)) return;
      await library.remove(s.id);
      renderLibrary();
    });
    box.appendChild(row);
  });
}

// Mostrar la letra de una canción del repertorio a mano (la canción empieza ahora).
function showSongLyrics(s) {
  currentSongId = s.id;
  pending = null;
  lyrics.load(library.trackFor(s));
  showSong();
  renderLibrary();
  if (state.lyrMode === 'live') {
    lyrStatus(lyrics.lines.length ? '✓ Lista. Pulsa Espacio (o el clicker) cuando empiece a cantar.' : 'Esta canción no tiene letra: añádela con ✎', !lyrics.lines.length);
    return;
  }
  lyrStatus(lyrics.fromFile ? '✓ Letra sincronizada con la canción de prueba'
    : lyrics.synced ? 'Letra cargada desde 0:00. Pulsa 🎤 o ⏱ "Empieza ya" para sincronizar' : 'Letra sin tiempos: avanza con K / J');
}

// Repertorio anterior / siguiente (teclas [ y ])
function stepSong(dir) {
  if (!library.songs.length) return;
  const i = library.songs.findIndex((s) => s.id === currentSongId);
  const next = library.songs[(i + dir + library.songs.length) % library.songs.length];
  showSongLyrics(next);
}

$('libFiles').addEventListener('change', async (e) => {
  const files = [...e.target.files];
  e.target.value = '';
  for (const [k, f] of files.entries()) {
    try {
      await library.addFile(f, (m) => lyrStatus(`(${k + 1}/${files.length}) ${m}`));
      renderLibrary();
    } catch (err) {
      console.error(err);
      lyrStatus(`No se pudo analizar ${f.name}: ${err.message}`, true);
    }
  }
  const noLyr = library.songs.filter((s) => !s.track).length;
  lyrStatus(`✓ Repertorio listo${noLyr ? ` · ${noLyr} sin letra: añádela con ✎` : ''}`);
});

// Editor de canción: nombre y letra (buscar en LRCLIB o pegar a mano)
let editing = null;
let creating = false;
function editSong(s) {
  editing = s;
  creating = false;
  $('dlgHeading').textContent = 'Editar canción';
  $('dlgArtist').value = s.artist || '';
  $('dlgTitle').value = s.title || '';
  const t = s.track || {};
  $('dlgLyrics').value = t.syncedLyrics || t.plainLyrics || '';
  $('dlgStatus').textContent = '';
  $('songDialog').showModal();
}
$('dlgSearch').addEventListener('click', async () => {
  const artist = $('dlgArtist').value.trim(), title = $('dlgTitle').value.trim();
  $('dlgStatus').textContent = '🔎 Buscando…';
  try {
    const t = artist ? await lyrics.findLyrics(artist, title) : (await lyrics.search(title))[0];
    if (!t) { $('dlgStatus').textContent = 'No se encontró letra. Puedes pegarla a mano.'; return; }
    $('dlgLyrics').value = t.syncedLyrics || t.plainLyrics || '';
    $('dlgStatus').textContent = t.syncedLyrics ? '✓ Encontrada con tiempos' : '✓ Encontrada (sin tiempos)';
  } catch (err) {
    $('dlgStatus').textContent = err.message;
  }
});
// Enter en artista / título busca la letra (en vez de cerrar el editor).
['dlgArtist', 'dlgTitle'].forEach((id) => $(id).addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { e.preventDefault(); $('dlgSearch').click(); }
}));
$('addSongBtn').addEventListener('click', () => {
  editing = null;
  creating = true;
  $('dlgHeading').textContent = 'Nueva canción del setlist';
  $('dlgArtist').value = '';
  $('dlgTitle').value = '';
  $('dlgLyrics').value = '';
  $('dlgStatus').textContent = 'Escribe artista y título y pulsa 🔎, o pega la letra.';
  $('songDialog').showModal();
  $('dlgArtist').focus();
});
$('songDialog').addEventListener('close', async () => {
  if ($('songDialog').returnValue !== 'save' || (!editing && !creating)) return;
  const artist = $('dlgArtist').value.trim(), title = $('dlgTitle').value.trim();
  const text = $('dlgLyrics').value.replace(/\s+$/, '');
  const synced = parseLRC(text).length > 0;
  const track = text.trim() ? { artistName: artist, trackName: title, syncedLyrics: synced ? text : null, plainLyrics: synced ? null : text } : null;
  if (creating) {
    if (!artist && !title && !track) return;
    const ns = await library.addManual(artist, title, track);
    creating = false;
    renderLibrary();
    if (!currentSongId) showSongLyrics(ns);
    return;
  }
  const s = editing;
  s.artist = artist;
  s.title = title;
  s.track = track;
  await library.update(s);
  renderLibrary();
  if (s.id === currentSongId) showSongLyrics(s);
});

// Reconoce lo que suena usando los últimos 8 s de audio.
async function identifySong(silentIfNone = false) {
  if (!library.songs.length) { if (!silentIfNone) lyrStatus('Primero añade las canciones del repertorio.', true); return; }
  const rec = audio.recent(8);
  if (!rec) { if (!silentIfNone) lyrStatus('Todavía no hay suficiente audio: elige un micrófono (o la canción de prueba) y espera unos segundos.', true); return; }
  const buf = new AudioBuffer({ length: rec.samples.length, sampleRate: rec.sampleRate, numberOfChannels: 1 });
  buf.copyToChannel(rec.samples, 0);
  const mono = await SongLibrary.toMono(buf);
  const m = library.identify(mono);
  if (!m) {
    pending = null;
    if (!silentIfNone) lyrStatus('No se reconoció la canción. ¿Está en el repertorio? También puedes elegirla con ▶.', true);
    return;
  }
  const posNow = m.pos + (performance.now() - rec.endTime) / 1000;
  // Coincidencia dudosa: se confirma con una segunda escucha coherente (misma canción, posición que avanza).
  if (!m.sure) {
    const ok = pending && pending.id === m.song.id && Math.abs((posNow - pending.pos) - (performance.now() - pending.at) / 1000) < 1;
    if (!ok) {
      pending = { id: m.song.id, pos: posNow, at: performance.now() };
      if (!silentIfNone) lyrStatus(`Parece «${songName(m.song)}»… comprobando (pulsa 🎤 otra vez en unos segundos).`);
      return;
    }
  }
  pending = null;
  if (m.song.id === currentSongId && lyrics.lines.length) {
    const drift = posNow - lyrics.position(performance.now());
    // En reconocimiento continuo no se pisan las correcciones manuales pequeñas.
    if (silentIfNone && Math.abs(drift) < 1.5) return;
    lyrics.resync(m.pos, rec.endTime);
    lyrStatus(`✓ ${songName(m.song)} · resincronizada (${drift >= 0 ? '+' : ''}${drift.toFixed(1).replace('.', ',')} s)`);
    return;
  }
  currentSongId = m.song.id;
  lyrics.applyMatch(library.trackFor(m.song), m.pos, rec.endTime);
  showSong();
  renderLibrary();
  const mmss = `${Math.floor(posNow / 60)}:${String(Math.floor(posNow % 60)).padStart(2, '0')}`;
  lyrStatus(m.song.track ? `✓ ${songName(m.song)} · minuto ${mmss}` : `✓ ${songName(m.song)} · sin letra: añádela con ✎`);
}
$('identifyBtn').addEventListener('click', () => identifySong(false));

let autoIdTimer = null;
$('autoIdentify').addEventListener('change', (e) => {
  clearInterval(autoIdTimer);
  autoIdTimer = e.target.checked ? setInterval(() => identifySong(true), 5000) : null;
  lyrStatus(e.target.checked ? '👂 Escuchando continuamente…' : '');
});

library.open().then(() => {
  renderLibrary();
  if (!library.persistent) lyrStatus('Aviso: este navegador no deja guardar el repertorio; se perderá al cerrar. Abre la app con iniciar.bat.', true);
});

// Búsqueda manual
let lyrResults = [];
async function searchLyrics() {
  const q = $('lyrQuery').value.trim();
  if (!q) return;
  lyrStatus('🔎 Buscando letra…');
  try {
    lyrResults = (await lyrics.search(q)).slice(0, 15);
    const sel = $('lyrResults');
    sel.innerHTML = '';
    lyrResults.forEach((t, i) => sel.add(new Option(
      `${t.artistName} — ${t.trackName}${t.syncedLyrics ? ' ✓ sincronizada' : t.plainLyrics ? ' (solo texto)' : ' (sin letra)'}`, i)));
    sel.hidden = !lyrResults.length;
    if (!lyrResults.length) { lyrStatus('No se encontró ninguna letra con esa búsqueda.', true); return; }
    loadResult(0);
  } catch (err) {
    lyrStatus(err.message, true);
  }
}
function loadResult(i) {
  const t = lyrResults[i];
  if (!t) return;
  currentSongId = null;
  pending = null;
  lyrics.load(t);
  showSong();
  if (lyrics.fromFile) lyrStatus('✓ Letra cargada y sincronizada con la canción de prueba');
  else if (lyrics.synced) lyrStatus('✓ Letra cargada. Pulsa ⏱ "Empieza ya" cuando arranque la canción, y ajusta con J / K');
  else lyrStatus('✓ Letra sin tiempos: avanza las líneas con K / J');
}
$('lyrSearchBtn').addEventListener('click', searchLyrics);
$('lyrQuery').addEventListener('keydown', (e) => { if (e.key === 'Enter') searchLyrics(); });
$('lyrResults').addEventListener('change', (e) => loadResult(Number(e.target.value)));

// Sincronización manual
$('lyrNow').addEventListener('click', () => { lyrics.startNow(performance.now()); lyrStatus('⏱ Letra sincronizada desde el inicio'); });
$('lyrPrev').addEventListener('click', () => lyrics.step(-1, performance.now()));
$('lyrNext').addEventListener('click', () => lyrics.step(1, performance.now()));
$('lyrMinus').addEventListener('click', () => lyrics.nudge(-0.25));
$('lyrPlus').addEventListener('click', () => lyrics.nudge(0.25));

// ---------- Modo banda en vivo / música grabada ----------
function liveStep(dir) {
  if (!lyrics.lines.length) {
    if (dir > 0 && library.songs.length) stepSong(1);
    return;
  }
  lyrics.step(dir, performance.now());
}
$('liveFwd').addEventListener('click', () => liveStep(1));
$('liveBack').addEventListener('click', () => liveStep(-1));
$('liveRestart').addEventListener('click', () => { lyrics.startNow(performance.now()); lyrics.titleShownAt = performance.now(); });

function applyLyrMode(mode) {
  state.lyrMode = mode;
  $('lyrMode').value = mode;
  document.body.classList.toggle('mode-live', mode === 'live');
  document.body.classList.toggle('mode-rec', mode === 'rec');
  lyrics.manual = mode === 'live';
  if (mode === 'live' && $('autoIdentify').checked) { $('autoIdentify').checked = false; $('autoIdentify').dispatchEvent(new Event('change')); }
  if (mode === 'live') lyrics.manualIdx = -1;
  try { localStorage.setItem('visuales.lyrMode', mode); } catch (e) { /* sin almacenamiento */ }
  renderLibrary();
}
$('lyrMode').addEventListener('change', (e) => applyLyrMode(e.target.value));
let savedMode = 'live';
try { savedMode = localStorage.getItem('visuales.lyrMode') || 'live'; } catch (e) { /* sin almacenamiento */ }
applyLyrMode(savedMode);

// Teleprompter del operador: se actualiza solo cuando cambia la línea.
let prompterKey = '';
function updatePrompter() {
  if (state.lyrMode !== 'live') return;
  const L = lyrics.lines;
  const idx = lyrics.manualIdx;
  const key = `${lyrics.title}|${idx}|${L.length}`;
  if (key === prompterKey) return;
  prompterKey = key;
  const show = (i) => (i < 0 ? '' : i >= L.length ? '' : L[i].text || '· · · (pausa) · · ·');
  const nextSong = () => {
    const i = library.songs.findIndex((x) => x.id === currentSongId);
    const n = library.songs[i + 1];
    return n ? `Siguiente canción: ${songName(n)}` : 'Fin del setlist';
  };
  let cur;
  if (!L.length) cur = library.songs.length ? 'Elige una canción del setlist (▶) o pulsa ]' : 'Añade canciones al setlist';
  else if (idx < 0) cur = '▶ Pulsa Espacio cuando empiece a cantar';
  else if (idx >= L.length) cur = '✓ Fin de la letra';
  else cur = show(idx);
  $('prompter').querySelector('.pr-prev').textContent = show(idx - 1);
  $('prompter').querySelector('.pr-cur').textContent = cur;
  $('prompter').querySelector('.pr-next').textContent = idx >= L.length - 1 && L.length ? nextSong() : show(idx + 1);
  $('prompter').querySelector('.pr-next2').textContent = idx >= L.length - 1 ? '' : show(idx + 2);
}
setInterval(updatePrompter, 100);
