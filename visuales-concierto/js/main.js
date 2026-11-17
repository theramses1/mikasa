// Interfaz del operador: fuentes, efectos, controles y ventana del proyector.

const $ = (id) => document.getElementById(id);
const canvas = $('out');
const video = $('camVideo');

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
  camStream: null,
};

// ---------- Patrón de prueba (cuando no hay cámara) ----------
const testCanvas = document.createElement('canvas');
testCanvas.width = 640; testCanvas.height = 360;
const tctx = testCanvas.getContext('2d');
function drawTestPattern(t) {
  const w = testCanvas.width, h = testCanvas.height;
  const g = tctx.createLinearGradient(0, 0, w, h);
  g.addColorStop(0, `hsl(${(t * 20) % 360},70%,25%)`);
  g.addColorStop(1, `hsl(${(t * 20 + 160) % 360},70%,15%)`);
  tctx.fillStyle = g;
  tctx.fillRect(0, 0, w, h);
  // "Persona" estilizada que se mueve, para ver bordes y estelas.
  const x = w / 2 + Math.sin(t * 1.3) * w * 0.25;
  tctx.fillStyle = '#f4d7c0';
  tctx.beginPath(); tctx.arc(x, h * 0.35, 32, 0, Math.PI * 2); tctx.fill();
  tctx.fillStyle = '#222';
  tctx.fillRect(x - 40, h * 0.35 + 36, 80, 120);
  tctx.save(); tctx.translate(x + 40, h * 0.35 + 50); tctx.rotate(Math.sin(t * 4) * 0.8);
  tctx.fillStyle = '#c33'; tctx.fillRect(0, -6, 90, 12); tctx.restore();
  for (let i = 0; i < 6; i++) {
    tctx.fillStyle = `hsla(${i * 60},90%,60%,.8)`;
    tctx.fillRect((i * 110 + t * 60) % (w + 100) - 100, h - 40, 60, 40);
  }
}

// ---------- Dispositivos ----------
async function listDevices() {
  const devices = await navigator.mediaDevices.enumerateDevices();
  const fill = (sel, kind, emptyLabel) => {
    const prev = sel.value;
    sel.innerHTML = `<option value="">${emptyLabel}</option>`;
    devices.filter((d) => d.kind === kind).forEach((d, i) => {
      const o = document.createElement('option');
      o.value = d.deviceId;
      o.textContent = d.label || `${kind === 'videoinput' ? 'Cámara' : 'Audio'} ${i + 1}`;
      sel.appendChild(o);
    });
    if ([...sel.options].some((o) => o.value === prev)) sel.value = prev;
  };
  fill($('camSelect'), 'videoinput', '— patrón de prueba —');
  fill($('micSelect'), 'audioinput', '— sin audio —');
}

async function startCamera(deviceId) {
  if (state.camStream) state.camStream.getTracks().forEach((t) => t.stop());
  state.camStream = null;
  video.srcObject = null;
  if (!deviceId) return 'patrón de prueba';
  const [w, h] = $('camRes').value.split('x').map(Number);
  const stream = await navigator.mediaDevices.getUserMedia({
    video: { deviceId: { exact: deviceId }, width: { ideal: w }, height: { ideal: h }, frameRate: { ideal: 60 } },
    audio: false,
  });
  state.camStream = stream;
  video.srcObject = stream;
  await video.play();
  const s = stream.getVideoTracks()[0].getSettings();
  return `${s.width}×${s.height} @ ${Math.round(s.frameRate || 0)} fps`;
}

function setStatus(msg, error = false) {
  $('status').textContent = msg;
  $('status').classList.toggle('error', error);
}

$('startBtn').addEventListener('click', async () => {
  try {
    // Pedir permiso una vez para que el navegador muestre los nombres de los dispositivos.
    if (!state.started) {
      state.started = true;
      const tmp = await navigator.mediaDevices.getUserMedia({ video: true, audio: true }).catch(() =>
        navigator.mediaDevices.getUserMedia({ video: true }).catch(() => navigator.mediaDevices.getUserMedia({ audio: true })));
      tmp.getTracks().forEach((t) => t.stop());
      await listDevices();
      // Preseleccionar la primera cámara y entrada de audio si no se eligió nada.
      if (!$('camSelect').value && $('camSelect').options[1]) $('camSelect').selectedIndex = 1;
      if (!$('micSelect').value && $('micSelect').options[1]) $('micSelect').selectedIndex = 1;
    }
    const camInfo = await startCamera($('camSelect').value);
    if ($('micSelect').value) await audio.useDevice($('micSelect').value);
    setStatus(`Cámara: ${camInfo} · Audio: ${audio.label}`);
    $('startBtn').textContent = 'Aplicar cambios';
    $('hint').style.display = 'none';
  } catch (err) {
    console.error(err);
    setStatus(`No se pudo iniciar: ${err.message}`, true);
  }
});

$('camSelect').addEventListener('change', () => startCamera($('camSelect').value)
  .then((i) => setStatus(`Cámara: ${i} · Audio: ${audio.label}`)).catch((e) => setStatus(e.message, true)));
$('camRes').addEventListener('change', () => $('camSelect').value && $('camSelect').dispatchEvent(new Event('change')));
$('micSelect').addEventListener('change', () => {
  if ($('micSelect').value) audio.useDevice($('micSelect').value).then(() => setStatus(`Audio: ${audio.label}`)).catch((e) => setStatus(e.message, true));
});
$('audioFile').addEventListener('change', (e) => {
  const f = e.target.files[0];
  if (f) { audio.useFile(f); setStatus(`Audio: ${f.name} (en bucle)`); }
});
navigator.mediaDevices?.addEventListener?.('devicechange', listDevices);

// ---------- Efectos ----------
const effectBtns = EFFECTS.map((e, i) => {
  const b = document.createElement('button');
  b.innerHTML = `${e.name} <kbd>${i + 1}</kbd>`;
  b.addEventListener('click', () => selectEffect(i));
  $('effects').appendChild(b);
  return b;
});
function selectEffect(i) {
  state.effect = (i + EFFECTS.length) % EFFECTS.length;
  state.beatsSinceSwitch = 0;
  effectBtns.forEach((b, j) => b.classList.toggle('active', j === state.effect));
  $('effectDesc').textContent = EFFECTS[state.effect].desc;
}
selectEffect(0);

audio.onBeat = () => {
  if (!state.autoCycle) return;
  if (++state.beatsSinceSwitch >= state.autoEvery) {
    let next;
    do { next = Math.floor(Math.random() * EFFECTS.length); } while (next === state.effect);
    selectEffect(next);
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

window.addEventListener('keydown', (e) => {
  if (e.target.tagName === 'SELECT' || e.target.tagName === 'INPUT' && e.target.type !== 'range' && e.target.type !== 'checkbox') return;
  const k = e.key.toLowerCase();
  if (k >= '1' && k <= String(EFFECTS.length)) selectEffect(Number(k) - 1);
  else if (k === 'arrowright') selectEffect(state.effect + 1);
  else if (k === 'arrowleft') selectEffect(state.effect - 1);
  else if (k === 'arrowup') nudge('intensity', 0.05);
  else if (k === 'arrowdown') nudge('intensity', -0.05);
  else if (k === 's') toggle('strobe');
  else if (k === 'm') toggle('mirror');
  else if (k === 'b') toggle('blackout');
  else if (k === 't') audio.tap(performance.now());
  else if (k === 'a') { $('autoCycle').checked = !$('autoCycle').checked; $('autoCycle').dispatchEvent(new Event('change')); }
  else if (k === 'h') document.body.classList.toggle('ui-hidden');
  else if (k === 'f') toggleFullscreen();
  else return;
  e.preventDefault();
});

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
    else window.dispatchEvent(new KeyboardEvent('keydown', { key: e.key })); // atajos también desde el proyector
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

  // Fuente: cámara si hay imagen, si no el patrón de prueba.
  if (state.camStream && video.readyState >= 2 && video.videoWidth) {
    renderer.uploadSource(video, video.videoWidth, video.videoHeight);
  } else {
    drawTestPattern(time);
    renderer.uploadSource(testCanvas, testCanvas.width, testCanvas.height);
  }

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
    mirror: state.mirror,
    master: state.master,
    flash: state.strobe ? Math.pow(audio.beat, 3) * 0.9 : 0,
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
  }
  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);

if (navigator.mediaDevices?.enumerateDevices) listDevices();
else setStatus('Este navegador no permite acceder a cámara/micrófono. Abre la página con Chrome o Edge.', true);
