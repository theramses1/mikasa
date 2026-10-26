import * as C from './calc.js';
import { load, save, uid, demoState, exportJson, importJson, defaultState } from './store.js';
import { lineChart, sparkline, ring, bars } from './charts.js';
import { MUSCLES } from './data.js';

let S = load();
const today = () => C.ymd();
const ui = {
  tab: 'inicio',
  dietDate: today(),
  journalDate: today(),
  trainView: 'entrenar',
  exerciseId: null,
  workoutId: null,
  weightRange: 90,
};

// ---------- Utilidades ----------

const $ = (sel, root = document) => root.querySelector(sel);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const fmt = (n, d = 0) =>
  n == null || Number.isNaN(n) ? '—' : Number(n).toLocaleString('es-MX', { maximumFractionDigits: d, minimumFractionDigits: d });
const signed = (n, d = 1) => (n == null ? '—' : (n > 0 ? '+' : n < 0 ? '−' : '±') + fmt(Math.abs(n), d));
const num = (v) => {
  const n = parseFloat(String(v ?? '').replace(',', '.'));
  return Number.isFinite(n) ? n : null;
};

function fmtDate(s, long = false) {
  if (s === today()) return 'Hoy';
  if (s === C.addDays(today(), -1)) return 'Ayer';
  if (s === C.addDays(today(), 1)) return 'Mañana';
  return C.parseYmd(s).toLocaleDateString('es-MX', long ? { weekday: 'long', day: 'numeric', month: 'long' } : { weekday: 'short', day: 'numeric', month: 'short' });
}

function commit() {
  save(S);
  render();
}

let toastTimer;
function toast(msg) {
  const el = $('#toast');
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 2600);
}

function formData(form) {
  return Object.fromEntries(new FormData(form).entries());
}

// ---------- Derivados ----------

function derived() {
  const t = today();
  const trend = C.trendSeries(S.weights, t);
  const exp = C.expenditureSeries(S, t);
  const trendNow = trend.length ? trend[trend.length - 1].trend : null;
  const tdee = exp.length ? exp[exp.length - 1].tdee : S.profile && trendNow ? C.initialTdee(S.profile, trendNow) : null;
  return { trend, exp, trendNow, tdee };
}

function targets() {
  if (S.targets) return S.targets;
  return { kcal: 2000, p: 150, c: 200, f: 65, date: today() };
}

function newTargets(d) {
  return C.computeTargets({ tdee: d.tdee, weightKg: d.trendNow, goal: S.goal, sex: S.profile.sex });
}

function checkinDue() {
  const t = S.targets;
  return !t || C.daysBetween(t.date, today()) >= 7;
}

function doCheckin() {
  const d = derived();
  if (d.tdee == null || d.trendNow == null) return toast('Registra tu peso para hacer el check-in.');
  const next = newTargets(d);
  const prev = S.targets;
  S.checkins.push({ date: today(), tdee: Math.round(d.tdee), weight: d.trendNow, ...next });
  S.targets = prev?.manual ? { ...prev, date: today() } : { date: today(), tdee: Math.round(d.tdee), manual: false, ...next };
  commit();
  if (prev?.manual) toast('Check-in guardado (tus objetivos manuales se mantienen).');
  else if (prev) toast(`Nuevo objetivo: ${fmt(next.kcal)} kcal (${signed(next.kcal - prev.kcal, 0)})`);
  else toast(`Objetivo: ${fmt(next.kcal)} kcal`);
}

function goalLabel(rate) {
  if (!rate) return 'Mantener peso';
  return `${rate < 0 ? 'Perder' : 'Ganar'} ${fmt(Math.abs(rate), 2)} % por semana`;
}

// ---------- Render principal ----------

const TITLES = { inicio: 'MoiehFih', dieta: 'Dieta', entreno: 'Entreno', peso: 'Peso corporal', bitacora: 'Bitácora', ajustes: 'Ajustes' };

function render() {
  const view = $('#view');
  const tabbar = $('#tabbar');
  if (!S.profile) {
    $('#title').textContent = 'Bienvenido';
    tabbar.hidden = true;
    view.innerHTML = renderOnboarding();
    return;
  }
  tabbar.hidden = false;
  $('#title').textContent = TITLES[ui.tab];
  for (const b of tabbar.querySelectorAll('button')) b.classList.toggle('active', b.dataset.tab === ui.tab);
  const views = { inicio: renderInicio, dieta: renderDieta, entreno: renderEntreno, peso: renderPeso, bitacora: renderBitacora, ajustes: renderAjustes };
  view.innerHTML = views[ui.tab]();
}

// ---------- Onboarding / perfil ----------

const RATES = [-1, -0.75, -0.5, -0.25, 0, 0.1, 0.25, 0.5];

function profileFields(p = {}, g = S.goal, withWeight = false) {
  const opt = (v, label, cur) => `<option value="${v}" ${String(cur) === String(v) ? 'selected' : ''}>${label}</option>`;
  return `
    <label>Nombre (opcional)<input name="name" value="${esc(p.name)}" autocomplete="given-name"></label>
    <div class="grid2">
      <label>Sexo<select name="sex">${opt('m', 'Hombre', p.sex)}${opt('f', 'Mujer', p.sex)}</select></label>
      <label>Edad<input name="age" inputmode="numeric" required value="${esc(p.age)}"></label>
      <label>Estatura (cm)<input name="heightCm" inputmode="decimal" required value="${esc(p.heightCm)}"></label>
      ${withWeight ? `<label>Peso actual (kg)<input name="weight" inputmode="decimal" required></label>` : ''}
    </div>
    <label>Actividad<select name="activity">${Object.entries(C.ACTIVITY)
      .map(([k, a]) => opt(k, a.label, p.activity || 'moderado'))
      .join('')}</select></label>
    <label>Objetivo<select name="ratePct">${RATES.map((r) => opt(r, goalLabel(r), g.ratePct)).join('')}</select></label>
    <div class="grid2">
      <label>Proteína (g por kg)<input name="proteinGPerKg" inputmode="decimal" value="${esc(g.proteinGPerKg)}"></label>
      <label>Grasa (% de kcal)<input name="fatPct" inputmode="decimal" value="${esc(Math.round(g.fatPct * 100))}"></label>
    </div>`;
}

function readProfile(fd) {
  const profile = {
    ...(S.profile || {}),
    name: fd.name?.trim() || '',
    sex: fd.sex,
    age: num(fd.age),
    heightCm: num(fd.heightCm),
    activity: fd.activity,
  };
  const ratePct = num(fd.ratePct) ?? 0;
  const goal = {
    type: ratePct < 0 ? 'lose' : ratePct > 0 ? 'gain' : 'maintain',
    ratePct,
    proteinGPerKg: num(fd.proteinGPerKg) ?? 2,
    fatPct: (num(fd.fatPct) ?? 27) / 100,
  };
  if (!profile.age || !profile.heightCm) throw new Error('Completa edad y estatura.');
  return { profile, goal };
}

function renderOnboarding() {
  return `<div class="welcome">
    <div class="card">
      <h2>Tu coach de nutrición y entreno</h2>
      <p>Registra lo que comes, tu peso y tus entrenos. La app estima tu <b>gasto energético real</b> a partir de tus datos
      y ajusta tus calorías y macros cada semana, como un coach. También te sugiere cuánto peso y cuántas repeticiones hacer.</p>
      <form class="form" data-form="onboarding">${profileFields({}, S.goal, true)}
        <button class="btn primary block">Empezar</button>
      </form>
    </div>
    <button class="btn block" data-action="demo">Explorar con datos de demostración</button>
    <p class="tiny muted">Tus datos se guardan solo en este dispositivo. Puedes exportarlos desde Ajustes.</p>
  </div>`;
}

// ---------- Inicio ----------

function macroBar(label, cls, value, target, unit = 'g') {
  const pct = target ? (value / target) * 100 : 0;
  return `<div class="macro"><div class="macro-top"><span><i class="dot${cls}"></i>${label}</span><span><b>${fmt(value)}</b> / ${fmt(target)} ${unit}</span></div>
    <div class="track"><span class="fill ${cls} ${pct > 110 ? 'over' : ''}" style="width:${Math.min(100, pct).toFixed(0)}%"></span></div></div>`;
}

function dayCard(date, title) {
  const tg = targets();
  const tot = C.sumEntries(S.foodLog[date]);
  const left = tg.kcal - tot.kcal;
  return `<div class="card">
    <div class="card-head"><h2>${title}</h2><span class="pill">${fmt(tg.kcal)} kcal objetivo</span></div>
    <div class="row">
      ${ring(tot.kcal, tg.kcal, fmt(tot.kcal), 'kcal consumidas')}
      <div class="grow">
        <div class="stat"><strong class="${left < 0 ? 'bad' : ''}">${fmt(Math.abs(left))}</strong><span>${left < 0 ? 'kcal por encima' : 'kcal restantes'}</span></div>
        ${macroBar('Proteína', 'p', tot.p, tg.p)}
        ${macroBar('Carbos', 'c', tot.c, tg.c)}
        ${macroBar('Grasa', 'f', tot.f, tg.f)}
      </div>
    </div>
  </div>`;
}

function renderInicio() {
  const d = derived();
  const t = today();
  const tg = targets();
  let html = '';
  if (checkinDue()) {
    const preview = d.tdee && d.trendNow ? newTargets(d) : null;
    html += `<div class="banner"><div class="row between"><div><h3>Check-in semanal disponible</h3>
      <div class="small muted">${preview ? `Nuevo objetivo estimado: <b>${fmt(preview.kcal)} kcal</b> · P ${preview.p} · C ${preview.c} · G ${preview.f}` : 'Registra tu peso para calcularlo.'}</div></div>
      <button class="btn primary sm" data-action="checkin">Hacer check-in</button></div></div>`;
  }
  html += dayCard(t, 'Hoy');

  const exp28 = d.exp.slice(-28).map((x) => x.tdee);
  const expWeekAgo = d.exp.length > 7 ? d.exp[d.exp.length - 8].tdee : null;
  const trendWeekAgo = C.trendOn(d.trend, C.addDays(t, -7));
  const wk = d.trendNow != null && trendWeekAgo != null ? d.trendNow - trendWeekAgo : null;
  html += `<div class="grid2">
    <div class="card tap" data-action="go" data-tab="peso">
      <div class="small muted">Gasto energético</div>
      <div class="big">${fmt(d.tdee)}</div>
      <div class="tiny muted">kcal/día · ${expWeekAgo ? signed(d.tdee - expWeekAgo, 0) + ' en 7 días' : 'estimación inicial'}</div>
      ${sparkline(exp28, 'tdee')}
    </div>
    <div class="card tap" data-action="go" data-tab="peso">
      <div class="small muted">Peso de tendencia</div>
      <div class="big">${fmt(d.trendNow, 1)}</div>
      <div class="tiny muted">kg · ${wk != null ? signed(wk, 2) + ' kg/sem' : 'sin datos'}</div>
      ${sparkline(d.trend.slice(-28).map((x) => x.trend), 'trend')}
    </div>
  </div>`;

  const next = S.targets ? Math.max(0, 7 - C.daysBetween(S.targets.date, t)) : 0;
  html += `<div class="card">
    <div class="card-head"><h2>Plan</h2><span class="pill ${S.targets?.manual ? 'warn' : 'good'}">${S.targets?.manual ? 'Manual' : 'Coach'}</span></div>
    <div class="small">${goalLabel(S.goal.ratePct)}${d.trendNow ? ` · ≈ ${fmt(Math.abs((S.goal.ratePct / 100) * d.trendNow), 2)} kg/sem` : ''}</div>
    <div class="grid3" style="margin-top:10px">
      <div class="stat"><strong>${fmt(tg.p)}</strong><span>Proteína (g)</span></div>
      <div class="stat"><strong>${fmt(tg.c)}</strong><span>Carbos (g)</span></div>
      <div class="stat"><strong>${fmt(tg.f)}</strong><span>Grasa (g)</span></div>
    </div>
    <div class="tiny muted" style="margin-top:8px">${checkinDue() ? 'Check-in pendiente' : `Próximo check-in en ${next} día${next === 1 ? '' : 's'}`}</div>
  </div>`;

  const weekStart = C.addDays(t, -6);
  const thisWeek = S.workouts.filter((w) => w.date >= weekStart);
  const last = [...S.workouts].sort((a, b) => b.start - a.start)[0];
  html += `<div class="card"><div class="card-head"><h2>Entreno</h2><span class="pill">${thisWeek.length} sesiones / 7 días</span></div>
    ${
      S.activeWorkout
        ? `<button class="btn primary block" data-action="go" data-tab="entreno">Continuar: ${esc(S.activeWorkout.name)}</button>`
        : last
          ? `<div class="small">Último: <b>${esc(last.name)}</b> · ${fmtDate(last.date)} · ${fmt(workoutVolume(last))} kg de volumen</div>
             <button class="btn block" style="margin-top:10px" data-action="go" data-tab="entreno">Empezar entreno</button>`
          : `<button class="btn block" data-action="go" data-tab="entreno">Empezar tu primer entreno</button>`
    }</div>`;

  const j = S.journal[t];
  html += `<div class="card tap" data-action="go" data-tab="bitacora"><div class="card-head"><h2>Bitácora de hoy</h2><span class="btn ghost">${j ? 'Editar' : 'Registrar'}</span></div>
    <div class="small muted">${j ? journalSummary(j) : 'Anota sueño, energía, hambre y notas del día.'}</div></div>`;
  return html;
}

function journalSummary(j) {
  const parts = [];
  if (j.sleepH) parts.push(`😴 ${fmt(j.sleepH, 1)} h`);
  if (j.energy) parts.push(`⚡ ${j.energy}/5`);
  if (j.mood) parts.push(`🙂 ${j.mood}/5`);
  if (j.hunger) parts.push(`🍽 hambre ${j.hunger}/5`);
  if (j.steps) parts.push(`👣 ${fmt(j.steps)}`);
  return parts.join(' · ') + (j.notes ? `<div style="margin-top:4px">${esc(j.notes)}</div>` : '');
}

// ---------- Dieta ----------

function dateNav(key) {
  const d = ui[key];
  return `<div class="datenav"><button class="btn sm" data-action="shift-date" data-key="${key}" data-n="-1">‹</button>
    <strong>${fmtDate(d, true)}</strong>
    <button class="btn sm" data-action="shift-date" data-key="${key}" data-n="1" ${d >= today() ? 'disabled' : ''}>›</button></div>`;
}

function entryMeta(e) {
  const qty = e.grams != null ? `${fmt(e.grams)} g · ` : '';
  return `${qty}P ${fmt(e.p)} · C ${fmt(e.c)} · G ${fmt(e.f)}`;
}

function renderDieta() {
  const date = ui.dietDate;
  const entries = S.foodLog[date] || [];
  let html = dateNav('dietDate') + dayCard(date, 'Resumen del día');
  for (const meal of C.MEALS) {
    const items = entries.filter((e) => e.meal === meal);
    const tot = C.sumEntries(items);
    html += `<div class="card"><div class="card-head"><h3>${meal}</h3><span class="small muted">${fmt(tot.kcal)} kcal</span></div>
      <ul class="list">${items
        .map(
          (e) => `<li class="tap" data-action="edit-entry" data-id="${e.id}"><div class="main"><div class="name">${esc(e.name)}</div><div class="meta">${entryMeta(e)}</div></div>
          <span class="kcal">${fmt(e.kcal)}</span><button class="x-btn" data-action="del-entry" data-id="${e.id}" aria-label="Eliminar">×</button></li>`,
        )
        .join('')}</ul>
      <button class="btn ghost" data-action="add-food" data-meal="${meal}">+ Añadir alimento</button></div>`;
  }
  html += `<div class="row wrap" style="margin-bottom:12px">
    <button class="btn" data-action="copy-day">Copiar día anterior</button>
    ${entries.length ? `<button class="btn danger" data-action="clear-day">Vaciar día</button>` : ''}</div>`;

  // Promedios semanales
  const week = [];
  for (let i = 0; i < 7; i++) {
    const d = C.addDays(date, -i);
    if (S.foodLog[d]?.length) week.push(C.sumEntries(S.foodLog[d]));
  }
  if (week.length) {
    const avg = (k) => week.reduce((s, x) => s + x[k], 0) / week.length;
    const tg = targets();
    html += `<div class="card"><div class="card-head"><h3>Promedio últimos 7 días</h3><span class="small muted">${week.length} días registrados</span></div>
      <div class="preview"><div><strong>${fmt(avg('kcal'))}</strong><span>kcal (${signed(avg('kcal') - tg.kcal, 0)})</span></div>
      <div><strong>${fmt(avg('p'))}</strong><span>Proteína</span></div><div><strong>${fmt(avg('c'))}</strong><span>Carbos</span></div><div><strong>${fmt(avg('f'))}</strong><span>Grasa</span></div></div></div>`;
  }
  return html;
}

function recentFoodIds() {
  const seen = new Map();
  const dates = Object.keys(S.foodLog).sort().reverse().slice(0, 30);
  for (const d of dates) for (const e of S.foodLog[d]) if (e.foodId) seen.set(e.foodId, (seen.get(e.foodId) || 0) + 1);
  return seen;
}

function foodList(q) {
  const recent = recentFoodIds();
  const qq = q
    .trim()
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '');
  const norm = (s) =>
    s
      .toLowerCase()
      .normalize('NFD')
      .replace(/[̀-ͯ]/g, '');
  const list = S.foods
    .filter((f) => !qq || norm(f.name).includes(qq))
    .sort((a, b) => (recent.get(b.id) || 0) - (recent.get(a.id) || 0) || a.name.localeCompare(b.name, 'es'));
  if (!list.length) return `<p class="small muted">Sin resultados. Créalo en la pestaña «Nuevo».</p>`;
  return `<ul class="list">${list
    .slice(0, 60)
    .map(
      (f) => `<li class="tap" data-action="pick-food" data-id="${f.id}"><div class="main"><div class="name">${esc(f.name)}${recent.has(f.id) ? ' <span class="pill tiny">reciente</span>' : ''}</div>
      <div class="meta">100 g: ${fmt(f.kcal)} kcal · P ${fmt(f.p, 1)} · C ${fmt(f.c, 1)} · G ${fmt(f.f, 1)}</div></div>
      ${f.builtin ? '' : `<button class="x-btn" data-action="del-food" data-id="${f.id}" aria-label="Eliminar alimento">×</button>`}</li>`,
    )
    .join('')}</ul>`;
}

const modalCtx = {};

function openModal(title, body) {
  const dlg = $('#modal');
  dlg.innerHTML = `<div class="modal-head"><h2>${title}</h2><button class="x-btn" data-action="close-modal" aria-label="Cerrar">×</button></div><div class="modal-body">${body}</div>`;
  if (!dlg.open) dlg.showModal();
  const first = dlg.querySelector('[autofocus]');
  if (first) first.focus();
}

function closeModal() {
  const dlg = $('#modal');
  if (dlg.open) dlg.close();
}

function openFoodPicker(meal, tab = 'buscar') {
  modalCtx.meal = meal;
  modalCtx.entryId = null;
  const seg = ['buscar', 'nuevo', 'rapido']
    .map((t) => `<button class="${t === tab ? 'active' : ''}" data-action="food-tab" data-t="${t}">${{ buscar: 'Buscar', nuevo: 'Nuevo', rapido: 'Rápido' }[t]}</button>`)
    .join('');
  let body = `<div class="seg">${seg}</div>`;
  if (tab === 'buscar') {
    body += `<input id="food-q" placeholder="Buscar alimento…" autocomplete="off" autofocus><div id="food-results" style="margin-top:8px">${foodList('')}</div>`;
  } else if (tab === 'nuevo') {
    body += `<form class="form" data-form="new-food">
      <label>Nombre<input name="name" required autofocus></label>
      <div class="grid2"><label>Porción (nombre)<input name="servingName" placeholder="pieza, taza…"></label>
      <label>Porción (g)<input name="servingG" inputmode="decimal" placeholder="100"></label></div>
      <label>Los valores son por<select name="basis"><option value="100">100 g</option><option value="serving">porción</option></select></label>
      <div class="grid2"><label>Proteína (g)<input name="p" inputmode="decimal" required></label><label>Carbos (g)<input name="c" inputmode="decimal" required></label>
      <label>Grasa (g)<input name="f" inputmode="decimal" required></label><label>Calorías<input name="kcal" inputmode="decimal" placeholder="auto"></label></div>
      <button class="btn primary block">Guardar y añadir</button></form>`;
  } else {
    body += `<form class="form" data-form="quick-add">
      <label>Descripción<input name="name" placeholder="Comida rápida" autofocus></label>
      <div class="grid2"><label>Proteína (g)<input name="p" inputmode="decimal"></label><label>Carbos (g)<input name="c" inputmode="decimal"></label>
      <label>Grasa (g)<input name="f" inputmode="decimal"></label><label>Calorías<input name="kcal" inputmode="decimal" placeholder="auto"></label></div>
      <button class="btn primary block">Añadir</button></form>`;
  }
  openModal(`Añadir a ${meal}`, body);
}

function previewHtml(m) {
  return `<div class="preview"><div><strong>${fmt(m.kcal)}</strong><span>kcal</span></div><div><strong>${fmt(m.p, 1)}</strong><span>Proteína</span></div>
    <div><strong>${fmt(m.c, 1)}</strong><span>Carbos</span></div><div><strong>${fmt(m.f, 1)}</strong><span>Grasa</span></div></div>`;
}

function openQuantity(food, entry) {
  modalCtx.food = food;
  modalCtx.entryId = entry?.id || null;
  const grams = entry?.grams ?? food.servingG ?? 100;
  const hasServing = food.servingG && food.servingG !== 100;
  const unit = !entry && hasServing ? 'serving' : 'g';
  const amount = unit === 'serving' ? 1 : grams;
  const meal = entry?.meal || modalCtx.meal;
  openModal(
    esc(food.name),
    `<form class="form" data-form="quantity">
      <div class="grid2"><label>Cantidad<input name="amount" id="qty-amount" inputmode="decimal" value="${amount}" autofocus></label>
      <label>Unidad<select name="unit" id="qty-unit"><option value="g" ${unit === 'g' ? 'selected' : ''}>gramos</option>
      ${hasServing ? `<option value="serving" ${unit === 'serving' ? 'selected' : ''}>${esc(food.servingName || 'porción')} (${fmt(food.servingG)} g)</option>` : ''}</select></label></div>
      <label>Comida<select name="meal">${C.MEALS.map((m) => `<option ${m === meal ? 'selected' : ''}>${m}</option>`).join('')}</select></label>
      <div id="qty-preview">${previewHtml(C.scaleFood(food, grams))}</div>
      <button class="btn primary block">${entry ? 'Guardar cambios' : 'Añadir'}</button></form>`,
  );
}

function qtyGrams() {
  const amount = num($('#qty-amount')?.value) ?? 0;
  const unit = $('#qty-unit')?.value;
  return unit === 'serving' ? amount * modalCtx.food.servingG : amount;
}

function openQuickEdit(entry) {
  modalCtx.entryId = entry.id;
  openModal(
    'Editar entrada',
    `<form class="form" data-form="quick-add">
      <label>Descripción<input name="name" value="${esc(entry.name)}"></label>
      <div class="grid2"><label>Proteína (g)<input name="p" inputmode="decimal" value="${fmt(entry.p, 1)}"></label><label>Carbos (g)<input name="c" inputmode="decimal" value="${fmt(entry.c, 1)}"></label>
      <label>Grasa (g)<input name="f" inputmode="decimal" value="${fmt(entry.f, 1)}"></label><label>Calorías<input name="kcal" inputmode="decimal" value="${Math.round(entry.kcal)}"></label></div>
      <label>Comida<select name="meal">${C.MEALS.map((m) => `<option ${m === entry.meal ? 'selected' : ''}>${m}</option>`).join('')}</select></label>
      <button class="btn primary block">Guardar cambios</button></form>`,
  );
}

function upsertEntry(date, entry) {
  const list = (S.foodLog[date] ||= []);
  const idx = list.findIndex((e) => e.id === entry.id);
  if (idx >= 0) list[idx] = entry;
  else list.push(entry);
}

// ---------- Entreno ----------

const exById = (id) => S.exercises.find((e) => e.id === id);
const workoutVolume = (w) => w.exercises.reduce((v, ex) => v + C.setVolume(ex.sets.filter((s) => s.done !== false)), 0);
const workoutSets = (w) => w.exercises.reduce((n, ex) => n + ex.sets.filter((s) => s.done !== false && s.reps > 0).length, 0);

function seg(key, options) {
  return `<div class="seg">${options.map(([v, l]) => `<button class="${ui[key] === v ? 'active' : ''}" data-action="seg" data-key="${key}" data-v="${v}">${l}</button>`).join('')}</div>`;
}

function renderEntreno() {
  if (ui.exerciseId) return renderExerciseDetail(exById(ui.exerciseId));
  if (ui.workoutId) return renderWorkoutDetail(S.workouts.find((w) => w.id === ui.workoutId));
  let html = seg('trainView', [
    ['entrenar', 'Entrenar'],
    ['historial', 'Historial'],
    ['ejercicios', 'Ejercicios'],
  ]);
  if (ui.trainView === 'entrenar') html += S.activeWorkout ? renderActiveWorkout() : renderTrainHome();
  else if (ui.trainView === 'historial') html += renderHistory();
  else html += renderExercises();
  return html;
}

function renderTrainHome() {
  const sets = C.weeklySetsByMuscle(S.workouts, S.exercises, today());
  const items = MUSCLES.filter((m) => sets[m]).map((m) => ({ label: m, value: sets[m] }));
  return `<button class="btn primary block" data-action="start-workout" style="margin-bottom:12px">Empezar entreno vacío</button>
    <div class="section-title">Rutinas</div>
    ${S.routines
      .map(
        (r) => `<div class="card"><div class="card-head"><h3>${esc(r.name)}</h3><div class="row">
          <button class="btn ghost sm" data-action="edit-routine" data-id="${r.id}">Editar</button>
          <button class="btn primary sm" data-action="start-workout" data-id="${r.id}">Empezar</button></div></div>
          <div class="small muted">${r.exerciseIds.map((id) => esc(exById(id)?.name || '?')).join(' · ')}</div></div>`,
      )
      .join('')}
    <button class="btn block" data-action="edit-routine">+ Nueva rutina</button>
    <div class="section-title">Series por músculo (7 días)</div>
    <div class="card">${items.length ? bars(items) : '<div class="small muted">Aún no hay series esta semana.</div>'}
      <div class="tiny muted" style="margin-top:8px">Referencia general: 10-20 series semanales por grupo muscular.</div></div>`;
}

function buildExerciseEntry(exerciseId) {
  const ex = exById(exerciseId);
  const sug = C.suggestNext(C.exerciseHistory(S.workouts, exerciseId), ex);
  const sets = Array.from({ length: sug.sets }, () => ({ weight: sug.weight ?? '', reps: sug.reps, rpe: '', done: false }));
  return { exerciseId, sets, suggestion: sug };
}

function startWorkout(routineId) {
  const r = S.routines.find((x) => x.id === routineId);
  S.activeWorkout = {
    id: uid(),
    date: today(),
    name: r ? r.name : 'Entreno',
    start: Date.now(),
    notes: '',
    exercises: r ? r.exerciseIds.filter(exById).map(buildExerciseEntry) : [],
  };
  ui.trainView = 'entrenar';
  commit();
}

function elapsed(start) {
  const m = Math.floor((Date.now() - start) / 60000);
  return m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${m % 60} min`;
}

function renderActiveWorkout() {
  const w = S.activeWorkout;
  const kindCls = { up: 'good', deload: 'bad', hold: 'warn' };
  return `<div class="card"><input class="wk-name" data-wk-field="name" value="${esc(w.name)}" aria-label="Nombre del entreno">
      <div class="row between small muted" style="margin-top:8px"><span>Iniciado ${new Date(w.start).toLocaleTimeString('es-MX', { hour: '2-digit', minute: '2-digit' })}</span><span id="wk-timer">${elapsed(w.start)}</span></div></div>
    ${w.exercises
      .map((ex, i) => {
        const e = exById(ex.exerciseId);
        const s = ex.suggestion;
        return `<div class="card"><div class="card-head"><div><h3>${esc(e?.name || '?')}</h3><div class="tiny muted">${esc(e?.muscle || '')} · rango ${e?.repMin}-${e?.repMax} reps</div></div>
          <button class="x-btn" data-action="wk-del-ex" data-i="${i}" aria-label="Quitar ejercicio">×</button></div>
          ${s ? `<div class="suggest"><span class="pill ${kindCls[s.kind] || ''}">Sugerencia</span> <b>${s.weight != null && s.weight !== '' ? fmt(s.weight, 1) + ' kg' : 'Elige peso'} × ${s.reps}</b> — ${esc(s.reason)}</div>` : ''}
          <table class="sets"><thead><tr><th>#</th><th>kg</th><th>Reps</th><th>RPE</th><th>✓</th></tr></thead><tbody>
          ${ex.sets
            .map(
              (st, j) => `<tr class="${st.done ? 'done' : ''}"><td class="muted">${j + 1}</td>
              <td><input inputmode="decimal" data-set="${i}:${j}:weight" value="${esc(st.weight)}"></td>
              <td><input inputmode="numeric" data-set="${i}:${j}:reps" value="${esc(st.reps)}"></td>
              <td><input inputmode="decimal" data-set="${i}:${j}:rpe" value="${esc(st.rpe)}" placeholder="–"></td>
              <td><button class="check ${st.done ? 'on' : ''}" data-action="wk-toggle" data-i="${i}" data-j="${j}" aria-label="Serie hecha">✓</button></td></tr>`,
            )
            .join('')}</tbody></table>
          <div class="row" style="margin-top:6px"><button class="btn ghost sm" data-action="wk-add-set" data-i="${i}">+ Serie</button>
          ${ex.sets.length ? `<button class="btn ghost sm" data-action="wk-del-set" data-i="${i}">− Serie</button>` : ''}</div></div>`;
      })
      .join('')}
    <button class="btn block" data-action="wk-add-ex" style="margin-bottom:12px">+ Añadir ejercicio</button>
    <label>Notas<textarea data-wk-field="notes" placeholder="Sensaciones, técnica, molestias…">${esc(w.notes)}</textarea></label>
    <div class="grid2" style="margin-top:12px"><button class="btn danger" data-action="wk-discard">Descartar</button><button class="btn primary" data-action="wk-finish">Terminar entreno</button></div>`;
}

function finishWorkout() {
  const w = S.activeWorkout;
  const anyDone = w.exercises.some((ex) => ex.sets.some((s) => s.done));
  const clean = (st) => ({ weight: num(st.weight) ?? 0, reps: num(st.reps) ?? 0, rpe: num(st.rpe), done: true });
  const exercises = w.exercises
    .map((ex) => ({ exerciseId: ex.exerciseId, sets: ex.sets.filter((s) => (anyDone ? s.done : true)).map(clean).filter((s) => s.reps > 0) }))
    .filter((ex) => ex.sets.length);
  if (!exercises.length) return toast('Registra al menos una serie con repeticiones.');
  const prs = [];
  for (const ex of exercises) {
    const hist = C.exerciseHistory(S.workouts, ex.exerciseId);
    const prev = hist.length ? Math.max(...hist.map((h) => h.best1rm)) : 0;
    const now = Math.max(...ex.sets.map((s) => C.e1rm(s.weight, s.reps)));
    if (hist.length && now > prev) prs.push(exById(ex.exerciseId)?.name);
  }
  S.workouts.push({ id: w.id, date: w.date, name: w.name.trim() || 'Entreno', start: w.start, end: Date.now(), notes: w.notes, exercises });
  S.activeWorkout = null;
  ui.trainView = 'historial';
  commit();
  toast(prs.length ? `🏆 Nuevo récord: ${prs.join(', ')}` : 'Entreno guardado 💪');
}

function renderHistory() {
  const list = [...S.workouts].sort((a, b) => (a.date === b.date ? b.start - a.start : a.date < b.date ? 1 : -1));
  if (!list.length) return `<div class="card small muted">Aún no hay entrenos guardados.</div>`;
  return `<div class="card"><ul class="list">${list
    .map(
      (w) => `<li class="tap" data-action="open-workout" data-id="${w.id}"><div class="main"><div class="name">${esc(w.name)}</div>
      <div class="meta">${fmtDate(w.date)} · ${workoutSets(w)} series · ${w.end ? Math.round((w.end - w.start) / 60000) + ' min' : ''}</div></div>
      <span class="kcal">${fmt(workoutVolume(w))} kg</span></li>`,
    )
    .join('')}</ul></div>`;
}

function renderWorkoutDetail(w) {
  if (!w) {
    ui.workoutId = null;
    return renderEntreno();
  }
  return `<button class="btn ghost" data-action="back-train">‹ Historial</button>
    <div class="card"><h2>${esc(w.name)}</h2><div class="small muted">${fmtDate(w.date, true)} · ${workoutSets(w)} series · ${fmt(workoutVolume(w))} kg de volumen</div>
    ${w.notes ? `<p class="small">${esc(w.notes)}</p>` : ''}</div>
    ${w.exercises
      .map((ex) => {
        const e = exById(ex.exerciseId);
        return `<div class="card tap" data-action="open-exercise" data-id="${ex.exerciseId}"><h3>${esc(e?.name || '?')}</h3>
        <ul class="list">${ex.sets.map((s, j) => `<li><span class="muted">${j + 1}</span><div class="main">${fmt(s.weight, 1)} kg × ${s.reps}${s.rpe ? ` @ RPE ${fmt(s.rpe, 1)}` : ''}</div><span class="meta">1RM est. ${fmt(C.e1rm(s.weight, s.reps), 1)}</span></li>`).join('')}</ul></div>`;
      })
      .join('')}
    <div class="grid2"><button class="btn danger" data-action="del-workout" data-id="${w.id}">Eliminar</button><button class="btn primary" data-action="repeat-workout" data-id="${w.id}">Repetir</button></div>`;
}

function renderExercises() {
  const groups = {};
  for (const e of S.exercises) (groups[e.muscle] ||= []).push(e);
  let html = `<button class="btn block" data-action="new-exercise" style="margin-bottom:12px">+ Nuevo ejercicio</button>`;
  for (const m of MUSCLES) {
    if (!groups[m]) continue;
    html += `<div class="section-title">${m}</div><div class="card"><ul class="list">${groups[m]
      .map((e) => {
        const h = C.exerciseHistory(S.workouts, e.id);
        const best = h.length ? Math.max(...h.map((x) => x.best1rm)) : null;
        return `<li class="tap" data-action="open-exercise" data-id="${e.id}"><div class="main"><div class="name">${esc(e.name)}</div>
          <div class="meta">${h.length ? `${h.length} sesiones · último ${fmtDate(h[h.length - 1].date)}` : 'Sin registros'}</div></div>
          ${best ? `<span class="kcal">${fmt(best, 1)} kg</span>` : ''}</li>`;
      })
      .join('')}</ul></div>`;
  }
  return html;
}

function renderExerciseDetail(e) {
  if (!e) {
    ui.exerciseId = null;
    return renderEntreno();
  }
  const h = C.exerciseHistory(S.workouts, e.id);
  const sug = C.suggestNext(h, e);
  const best = h.length ? h.reduce((a, b) => (b.best1rm > a.best1rm ? b : a)) : null;
  const heaviest = h.length ? Math.max(...h.map((x) => x.topWeight)) : null;
  const maxVol = h.length ? Math.max(...h.map((x) => x.volume)) : null;
  return `<button class="btn ghost" data-action="back-train">‹ Volver</button>
    <div class="card"><h2>${esc(e.name)}</h2><div class="small muted">${esc(e.muscle)} · rango ${e.repMin}-${e.repMax} reps · +${fmt(e.increment, 1)} kg por subida</div>
      <div class="suggest"><b>Próxima sesión: ${sug.weight != null ? fmt(sug.weight, 1) + ' kg' : 'elige peso'} × ${sug.reps} (${sug.sets} series)</b><br>${esc(sug.reason)}</div></div>
    <div class="grid3"><div class="card stat"><strong>${fmt(best?.best1rm, 1)}</strong><span>Mejor 1RM est.</span></div>
      <div class="card stat"><strong>${fmt(heaviest, 1)}</strong><span>Peso máximo</span></div><div class="card stat"><strong>${fmt(maxVol)}</strong><span>Volumen máx.</span></div></div>
    <div class="card"><h3>1RM estimado por sesión</h3>${lineChart([{ points: h.map((x) => ({ x: x.date, y: x.best1rm })), cls: 'trend', dots: true }], { unit: '' })}</div>
    <div class="card"><h3>Historial</h3><ul class="list">${[...h]
      .reverse()
      .slice(0, 20)
      .map((x) => `<li><div class="main"><div class="name">${fmtDate(x.date)}</div><div class="meta">${x.sets.map((s) => `${fmt(s.weight, 1)}×${s.reps}`).join(' · ')}</div></div><span class="meta">${fmt(x.best1rm, 1)}</span></li>`)
      .join('') || '<li class="muted small">Sin registros</li>'}</ul></div>
    <div class="card"><h3>Configuración</h3><form class="form" data-form="exercise" data-id="${e.id}" style="margin-top:8px">${exerciseFields(e)}
      <button class="btn primary block">Guardar</button></form>
      ${e.builtin ? '' : `<button class="btn danger block" style="margin-top:8px" data-action="del-exercise" data-id="${e.id}">Eliminar ejercicio</button>`}</div>`;
}

function exerciseFields(e = {}) {
  return `<label>Nombre<input name="name" required value="${esc(e.name)}"></label>
    <label>Grupo muscular<select name="muscle">${MUSCLES.map((m) => `<option ${m === e.muscle ? 'selected' : ''}>${m}</option>`).join('')}</select></label>
    <div class="grid3"><label>Reps mín.<input name="repMin" inputmode="numeric" value="${esc(e.repMin ?? 8)}"></label>
    <label>Reps máx.<input name="repMax" inputmode="numeric" value="${esc(e.repMax ?? 12)}"></label>
    <label>Subida (kg)<input name="increment" inputmode="decimal" value="${esc(e.increment ?? 2.5)}"></label></div>`;
}

function readExercise(fd) {
  const repMin = Math.max(1, Math.round(num(fd.repMin) ?? 8));
  const repMax = Math.max(repMin, Math.round(num(fd.repMax) ?? 12));
  return { name: fd.name.trim(), muscle: fd.muscle, repMin, repMax, increment: num(fd.increment) || 2.5 };
}

function openExercisePicker() {
  const groups = {};
  for (const e of S.exercises) (groups[e.muscle] ||= []).push(e);
  openModal(
    'Añadir ejercicio',
    `<input id="ex-q" placeholder="Buscar ejercicio…" autocomplete="off" autofocus>
    <div id="ex-results" style="margin-top:8px">${MUSCLES.filter((m) => groups[m])
      .map(
        (m) => `<div class="section-title">${m}</div><ul class="list">${groups[m]
          .map((e) => `<li class="tap" data-action="pick-exercise" data-id="${e.id}" data-name="${esc(e.name.toLowerCase())}"><div class="main"><div class="name">${esc(e.name)}</div></div></li>`)
          .join('')}</ul>`,
      )
      .join('')}</div>`,
  );
}

function openRoutineEditor(id) {
  const r = S.routines.find((x) => x.id === id) || { id: null, name: '', exerciseIds: [] };
  modalCtx.routineId = r.id;
  modalCtx.routineOrder = [...r.exerciseIds];
  openModal(
    r.id ? 'Editar rutina' : 'Nueva rutina',
    `<form class="form" data-form="routine"><label>Nombre<input name="name" required value="${esc(r.name)}"></label>
    <div>${MUSCLES.map((m) => {
      const list = S.exercises.filter((e) => e.muscle === m);
      if (!list.length) return '';
      return `<div class="section-title">${m}</div>${list
        .map((e) => `<label class="row" style="padding:6px 0;color:var(--text);font-weight:500"><input type="checkbox" name="ex" value="${e.id}" style="width:auto;margin:0" ${r.exerciseIds.includes(e.id) ? 'checked' : ''}>${esc(e.name)}</label>`)
        .join('')}`;
    }).join('')}</div>
    <button class="btn primary block">Guardar rutina</button>
    ${r.id ? `<button type="button" class="btn danger block" data-action="del-routine" data-id="${r.id}">Eliminar rutina</button>` : ''}</form>`,
  );
}

// ---------- Peso ----------

function renderPeso() {
  const d = derived();
  const t = today();
  const range = ui.weightRange;
  const from = range ? C.addDays(t, -range + 1) : d.trend[0]?.date || t;
  const trendIn = d.trend.filter((x) => x.date >= from);
  const dates = trendIn.map((x) => x.date);
  const change = (n) => {
    const past = C.trendOn(d.trend, C.addDays(t, -n));
    return d.trendNow != null && past != null ? d.trendNow - past : null;
  };
  const c7 = change(7);
  const c30 = change(30);
  const latest = Object.keys(S.weights).sort().pop();
  let html = `<div class="card"><form class="row" data-form="weight">
      <input name="date" type="date" value="${t}" max="${t}" style="flex:1.2">
      <input name="kg" inputmode="decimal" placeholder="kg" required style="flex:1" value="${S.weights[t] ?? ''}">
      <button class="btn primary">Guardar</button></form></div>
    <div class="grid3">
      <div class="card stat"><strong>${fmt(d.trendNow, 1)}</strong><span>Tendencia (kg)</span></div>
      <div class="card stat"><strong class="${c7 != null && Math.sign(c7) === Math.sign(S.goal.ratePct) ? 'good' : ''}">${signed(c7, 2)}</strong><span>7 días</span></div>
      <div class="card stat"><strong>${signed(c30, 1)}</strong><span>30 días</span></div></div>`;
  html += seg('weightRange', [
    [30, '1 mes'],
    [90, '3 meses'],
    [365, '1 año'],
    [0, 'Todo'],
  ]);
  html += `<div class="card"><h3>Peso</h3>${lineChart(
    [
      { points: trendIn.filter((x) => x.scale != null).map((x) => ({ x: x.date, y: x.scale })), cls: 'scale', line: false, dots: true },
      { points: trendIn.map((x) => ({ x: x.date, y: x.trend })), cls: 'trend' },
    ],
    { dates },
  )}<div class="legend"><span><i style="background:var(--scale)"></i>Báscula</span><span><i style="background:var(--trend)"></i>Tendencia</span></div>
    <div class="tiny muted" style="margin-top:6px">La tendencia suaviza las variaciones diarias de agua, sal y digestión.</div></div>`;

  const expIn = d.exp.filter((x) => x.date >= from);
  const intake = expIn.map((x) => ({ x: x.date, y: S.foodLog[x.date]?.length ? C.sumEntries(S.foodLog[x.date]).kcal : null }));
  html += `<div class="card"><div class="card-head"><h3>Gasto energético vs ingesta</h3><span class="pill good">${fmt(d.tdee)} kcal</span></div>
    ${lineChart(
      [
        { points: intake, cls: 'intake', line: false, dots: true },
        { points: expIn.map((x) => ({ x: x.date, y: x.tdee })), cls: 'tdee' },
      ],
      { dates: expIn.map((x) => x.date), yDecimals: 0 },
    )}<div class="legend"><span><i style="background:var(--good)"></i>Gasto estimado</span><span><i style="background:var(--warn)"></i>Ingesta</span></div>
    <div class="tiny muted" style="margin-top:6px">Se calcula con tu ingesta registrada y el cambio de tu peso de tendencia. Mientras más días registres, más precisa.</div></div>`;

  const entries = Object.keys(S.weights)
    .filter((k) => k >= from)
    .sort()
    .reverse();
  html += `<div class="card"><h3>Registros</h3><ul class="list">${
    entries
      .slice(0, 30)
      .map((k) => `<li><div class="main"><div class="name">${fmt(S.weights[k], 1)} kg</div><div class="meta">${fmtDate(k)}${k === latest ? ' · último' : ''}</div></div>
      <span class="meta">tend. ${fmt(C.trendOn(d.trend, k), 1)}</span><button class="x-btn" data-action="del-weight" data-date="${k}" aria-label="Eliminar">×</button></li>`)
      .join('') || '<li class="small muted">Sin registros en este rango</li>'
  }</ul>${entries.length > 30 ? `<div class="tiny muted">Mostrando los 30 más recientes de ${entries.length}.</div>` : ''}</div>`;
  return html;
}

// ---------- Bitácora ----------

function scale15(name, value, label) {
  return `<div><label>${label}</label><div class="scale-1-5">${[1, 2, 3, 4, 5]
    .map((v) => `<label><input type="radio" name="${name}" value="${v}" ${Number(value) === v ? 'checked' : ''}><span>${v}</span></label>`)
    .join('')}</div></div>`;
}

function renderBitacora() {
  const date = ui.journalDate;
  const j = S.journal[date] || {};
  const tot = C.sumEntries(S.foodLog[date]);
  const tg = targets();
  const trend = C.trendSeries(S.weights, today());
  const ws = S.workouts.filter((w) => w.date === date);
  let html = dateNav('journalDate');
  html += `<div class="card"><h3>Resumen del día</h3><ul class="list" style="margin-top:4px">
    <li><div class="main">Calorías</div><span class="kcal">${S.foodLog[date]?.length ? `${fmt(tot.kcal)} / ${fmt(tg.kcal)}` : '—'}</span></li>
    <li><div class="main">Macros (P / C / G)</div><span class="kcal">${S.foodLog[date]?.length ? `${fmt(tot.p)} / ${fmt(tot.c)} / ${fmt(tot.f)} g` : '—'}</span></li>
    <li><div class="main">Peso</div><span class="kcal">${S.weights[date] ? fmt(S.weights[date], 1) + ' kg' : '—'} <span class="meta">tend. ${fmt(C.trendOn(trend, date), 1)}</span></span></li>
    <li><div class="main">Entreno</div><span class="kcal">${ws.length ? ws.map((w) => esc(w.name)).join(', ') : 'Descanso'}</span></li></ul></div>`;
  html += `<div class="card"><form class="form" data-form="journal">
    <div class="grid2"><label>Horas de sueño<input name="sleepH" inputmode="decimal" value="${esc(j.sleepH)}"></label>
    <label>Pasos<input name="steps" inputmode="numeric" value="${esc(j.steps)}"></label></div>
    ${scale15('sleepQ', j.sleepQ, 'Calidad del sueño')}
    ${scale15('energy', j.energy, 'Energía')}
    ${scale15('mood', j.mood, 'Ánimo')}
    ${scale15('hunger', j.hunger, 'Hambre')}
    ${scale15('stress', j.stress, 'Estrés')}
    <label>Notas<textarea name="notes" placeholder="¿Cómo te sentiste? Digestión, antojos, dolor, logros…">${esc(j.notes)}</textarea></label>
    <button class="btn primary block">Guardar bitácora</button></form></div>`;

  const keys = Object.keys(S.journal).sort().reverse();
  const last7 = keys.filter((k) => k > C.addDays(today(), -7)).map((k) => S.journal[k]);
  const avg = (f) => {
    const v = last7.map((x) => num(x[f])).filter((x) => x != null);
    return v.length ? v.reduce((a, b) => a + b, 0) / v.length : null;
  };
  html += `<div class="section-title">Promedios 7 días</div><div class="grid3">
    <div class="card stat"><strong>${fmt(avg('sleepH'), 1)}</strong><span>Horas de sueño</span></div>
    <div class="card stat"><strong>${fmt(avg('energy'), 1)}</strong><span>Energía</span></div>
    <div class="card stat"><strong>${fmt(avg('hunger'), 1)}</strong><span>Hambre</span></div></div>`;
  html += `<div class="section-title">Entradas recientes</div><div class="card"><ul class="list">${
    keys
      .slice(0, 21)
      .map((k) => `<li class="tap" data-action="journal-date" data-date="${k}"><div class="main"><div class="name">${fmtDate(k)}</div><div class="meta">${journalSummary(S.journal[k])}</div></div></li>`)
      .join('') || '<li class="small muted">Aún no hay entradas.</li>'
  }</ul></div>`;
  return html;
}

// ---------- Ajustes ----------

function renderAjustes() {
  const tg = S.targets || {};
  const d = derived();
  return `<div class="card"><h2>Perfil y objetivo</h2><form class="form" data-form="profile" style="margin-top:10px">${profileFields(S.profile, S.goal)}
      <button class="btn primary block">Guardar y recalcular</button></form></div>
    <div class="card"><h2>Objetivos diarios</h2>
      <p class="small muted">En modo <b>Coach</b> las calorías y macros se recalculan en cada check-in semanal a partir de tu gasto energético estimado (${fmt(d.tdee)} kcal). En modo <b>Manual</b> se quedan fijas.</p>
      <form class="form" data-form="targets">
        <label>Modo<select name="mode"><option value="auto" ${!tg.manual ? 'selected' : ''}>Coach (adaptativo)</option><option value="manual" ${tg.manual ? 'selected' : ''}>Manual</option></select></label>
        <div class="grid2"><label>Calorías<input name="kcal" inputmode="numeric" value="${esc(tg.kcal)}"></label><label>Proteína (g)<input name="p" inputmode="numeric" value="${esc(tg.p)}"></label>
        <label>Carbos (g)<input name="c" inputmode="numeric" value="${esc(tg.c)}"></label><label>Grasa (g)<input name="f" inputmode="numeric" value="${esc(tg.f)}"></label></div>
        <button class="btn primary block">Guardar objetivos</button></form></div>
    <div class="card"><h2>Datos</h2><p class="small muted">Todo se guarda en este dispositivo. Exporta una copia de seguridad de vez en cuando.</p>
      <div class="grid2"><button class="btn" data-action="export">Exportar</button><label class="btn" style="color:var(--text);font-size:14px">Importar<input type="file" accept="application/json,.json" id="import-file" hidden></label></div>
      <button class="btn danger block" style="margin-top:10px" data-action="reset">Borrar todos los datos</button></div>
    <div class="card small muted"><h3 style="color:var(--text)">¿Cómo funciona?</h3>
      <p><b>Peso de tendencia:</b> media móvil exponencial de tus pesadas, para ver el cambio real sin el ruido diario.</p>
      <p><b>Gasto energético:</b> parte de la fórmula Mifflin-St Jeor × actividad y se corrige con tus datos: ingesta media − (cambio de tendencia × 7700 kcal/kg) en las últimas 3 semanas.</p>
      <p><b>Progresión:</b> doble progresión dentro del rango de reps de cada ejercicio; cuando completas el máximo en todas las series sube el peso, y si te estancas dos sesiones por debajo del mínimo sugiere una descarga del 10 %.</p></div>`;
}

// ---------- Eventos ----------

const actions = {
  go(el) {
    ui.tab = el.dataset.tab;
    ui.exerciseId = null;
    ui.workoutId = null;
    location.hash = ui.tab;
    render();
    scrollTo(0, 0);
  },
  demo() {
    S = demoState();
    const d = derived();
    S.targets = { date: C.addDays(today(), -7), tdee: Math.round(d.tdee), manual: false, ...newTargets(d) };
    commit();
    toast('Datos de demostración cargados');
  },
  checkin: doCheckin,
  'shift-date'(el) {
    const key = el.dataset.key;
    const next = C.addDays(ui[key], Number(el.dataset.n));
    if (next > today()) return;
    ui[key] = next;
    render();
  },
  'add-food'(el) {
    openFoodPicker(el.dataset.meal);
  },
  'food-tab'(el) {
    openFoodPicker(modalCtx.meal, el.dataset.t);
  },
  'pick-food'(el, ev) {
    if (ev.target.closest('[data-action="del-food"]')) return;
    openQuantity(S.foods.find((f) => f.id === el.dataset.id));
  },
  'del-food'(el) {
    if (!confirm('¿Eliminar este alimento de tu lista? Los registros ya hechos se conservan.')) return;
    S.foods = S.foods.filter((f) => f.id !== el.dataset.id);
    save(S);
    $('#food-results').innerHTML = foodList($('#food-q')?.value || '');
  },
  'edit-entry'(el, ev) {
    if (ev.target.closest('[data-action="del-entry"]')) return;
    const entry = (S.foodLog[ui.dietDate] || []).find((e) => e.id === el.dataset.id);
    if (!entry) return;
    const food = entry.foodId && S.foods.find((f) => f.id === entry.foodId);
    modalCtx.meal = entry.meal;
    if (food && entry.grams != null) openQuantity(food, entry);
    else openQuickEdit(entry);
  },
  'del-entry'(el) {
    S.foodLog[ui.dietDate] = (S.foodLog[ui.dietDate] || []).filter((e) => e.id !== el.dataset.id);
    if (!S.foodLog[ui.dietDate].length) delete S.foodLog[ui.dietDate];
    commit();
  },
  'copy-day'() {
    const prev = S.foodLog[C.addDays(ui.dietDate, -1)];
    if (!prev?.length) return toast('El día anterior no tiene registros.');
    S.foodLog[ui.dietDate] = [...(S.foodLog[ui.dietDate] || []), ...prev.map((e) => ({ ...e, id: uid() }))];
    commit();
    toast(`Copiadas ${prev.length} entradas`);
  },
  'clear-day'() {
    if (!confirm('¿Borrar todo lo registrado este día?')) return;
    delete S.foodLog[ui.dietDate];
    commit();
  },
  'close-modal': closeModal,
  seg(el) {
    const v = el.dataset.v;
    ui[el.dataset.key] = /^\d+$/.test(v) ? Number(v) : v;
    render();
  },
  'start-workout'(el) {
    if (S.activeWorkout && !confirm('Ya tienes un entreno en curso. ¿Descartarlo y empezar otro?')) return;
    startWorkout(el.dataset.id);
  },
  'wk-toggle'(el) {
    const st = S.activeWorkout.exercises[el.dataset.i].sets[el.dataset.j];
    st.done = !st.done;
    commit();
  },
  'wk-add-set'(el) {
    const sets = S.activeWorkout.exercises[el.dataset.i].sets;
    const last = sets[sets.length - 1];
    sets.push(last ? { ...last, done: false } : { weight: '', reps: '', rpe: '', done: false });
    commit();
  },
  'wk-del-set'(el) {
    S.activeWorkout.exercises[el.dataset.i].sets.pop();
    commit();
  },
  'wk-del-ex'(el) {
    if (!confirm('¿Quitar este ejercicio del entreno?')) return;
    S.activeWorkout.exercises.splice(Number(el.dataset.i), 1);
    commit();
  },
  'wk-add-ex': openExercisePicker,
  'pick-exercise'(el) {
    S.activeWorkout.exercises.push(buildExerciseEntry(el.dataset.id));
    closeModal();
    commit();
  },
  'wk-discard'() {
    if (!confirm('¿Descartar este entreno? No se guardará.')) return;
    S.activeWorkout = null;
    commit();
  },
  'wk-finish': finishWorkout,
  'open-workout'(el) {
    ui.workoutId = el.dataset.id;
    render();
    scrollTo(0, 0);
  },
  'open-exercise'(el) {
    ui.exerciseId = el.dataset.id;
    ui.workoutId = null;
    render();
    scrollTo(0, 0);
  },
  'back-train'() {
    if (ui.exerciseId) ui.exerciseId = null;
    else ui.workoutId = null;
    render();
  },
  'del-workout'(el) {
    if (!confirm('¿Eliminar este entreno del historial?')) return;
    S.workouts = S.workouts.filter((w) => w.id !== el.dataset.id);
    ui.workoutId = null;
    commit();
  },
  'repeat-workout'(el) {
    const w = S.workouts.find((x) => x.id === el.dataset.id);
    if (S.activeWorkout && !confirm('Ya tienes un entreno en curso. ¿Descartarlo?')) return;
    S.activeWorkout = { id: uid(), date: today(), name: w.name, start: Date.now(), notes: '', exercises: w.exercises.map((ex) => buildExerciseEntry(ex.exerciseId)) };
    ui.workoutId = null;
    ui.trainView = 'entrenar';
    commit();
  },
  'new-exercise'() {
    modalCtx.exerciseId = null;
    openModal('Nuevo ejercicio', `<form class="form" data-form="exercise">${exerciseFields()}<button class="btn primary block">Crear</button></form>`);
  },
  'del-exercise'(el) {
    if (!confirm('¿Eliminar este ejercicio? Su historial dejará de mostrarse.')) return;
    S.exercises = S.exercises.filter((e) => e.id !== el.dataset.id);
    for (const r of S.routines) r.exerciseIds = r.exerciseIds.filter((id) => id !== el.dataset.id);
    ui.exerciseId = null;
    commit();
  },
  'edit-routine'(el) {
    openRoutineEditor(el.dataset.id);
  },
  'del-routine'(el) {
    if (!confirm('¿Eliminar esta rutina?')) return;
    S.routines = S.routines.filter((r) => r.id !== el.dataset.id);
    closeModal();
    commit();
  },
  'del-weight'(el) {
    delete S.weights[el.dataset.date];
    commit();
  },
  'journal-date'(el) {
    ui.journalDate = el.dataset.date;
    render();
    scrollTo(0, 0);
  },
  export() {
    const name = `moiehfih-${today()}.json`;
    // En la app de Android el WebView no descarga blobs: se guarda vía el puente nativo.
    if (window.MoiehFihAndroid) return window.MoiehFihAndroid.saveFile(name, exportJson(S));
    const blob = new Blob([exportJson(S)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  },
  reset() {
    if (!confirm('¿Borrar TODOS los datos? Esta acción no se puede deshacer.')) return;
    S = defaultState();
    ui.tab = 'inicio';
    commit();
  },
};

const forms = {
  onboarding(fd) {
    const { profile, goal } = readProfile(fd);
    const kg = num(fd.weight);
    if (!kg) throw new Error('Indica tu peso actual.');
    S.profile = profile;
    S.goal = goal;
    S.weights[today()] = kg;
    const d = derived();
    S.targets = { date: today(), tdee: Math.round(d.tdee), manual: false, ...newTargets(d) };
    ui.tab = 'inicio';
    commit();
    toast(`Tu objetivo inicial: ${fmt(S.targets.kcal)} kcal`);
  },
  profile(fd) {
    const { profile, goal } = readProfile(fd);
    S.profile = profile;
    S.goal = goal;
    const d = derived();
    if (!S.targets?.manual && d.tdee && d.trendNow) S.targets = { date: today(), tdee: Math.round(d.tdee), manual: false, ...newTargets(d) };
    commit();
    toast('Perfil guardado');
  },
  targets(fd) {
    if (fd.mode === 'auto') {
      const d = derived();
      S.targets = { date: today(), tdee: Math.round(d.tdee), manual: false, ...newTargets(d) };
    } else {
      const kcal = num(fd.kcal);
      const p = num(fd.p);
      const c = num(fd.c);
      const f = num(fd.f);
      if ([kcal, p, c, f].some((x) => x == null)) throw new Error('Completa calorías y macros.');
      S.targets = { date: S.targets?.date || today(), manual: true, kcal, p, c, f };
    }
    commit();
    toast('Objetivos guardados');
  },
  quantity(fd) {
    const grams = qtyGrams();
    if (!grams || grams <= 0) throw new Error('Indica una cantidad.');
    const food = modalCtx.food;
    upsertEntry(ui.dietDate, { id: modalCtx.entryId || uid(), meal: fd.meal, foodId: food.id, name: food.name, grams, ...C.scaleFood(food, grams) });
    closeModal();
    commit();
  },
  'new-food'(fd) {
    const p = num(fd.p) ?? 0;
    const c = num(fd.c) ?? 0;
    const f = num(fd.f) ?? 0;
    const kcal = num(fd.kcal) ?? C.kcalFromMacros(p, c, f);
    const servingG = num(fd.servingG);
    if (fd.basis === 'serving' && !servingG) throw new Error('Indica los gramos de la porción.');
    const k = fd.basis === 'serving' ? 100 / servingG : 1;
    const food = { id: 'u-' + uid(), name: fd.name.trim(), kcal: kcal * k, p: p * k, c: c * k, f: f * k, servingG: servingG || 100, servingName: fd.servingName.trim() || 'porción' };
    S.foods.push(food);
    save(S);
    openQuantity(food);
  },
  'quick-add'(fd) {
    const p = num(fd.p) ?? 0;
    const c = num(fd.c) ?? 0;
    const f = num(fd.f) ?? 0;
    const kcal = num(fd.kcal) ?? C.kcalFromMacros(p, c, f);
    if (!kcal) throw new Error('Indica calorías o macros.');
    upsertEntry(ui.dietDate, { id: modalCtx.entryId || uid(), meal: fd.meal || modalCtx.meal, foodId: null, name: fd.name?.trim() || 'Entrada rápida', grams: null, kcal, p, c, f });
    modalCtx.entryId = null;
    closeModal();
    commit();
  },
  weight(fd) {
    const kg = num(fd.kg);
    if (!kg || kg < 20 || kg > 400) throw new Error('Peso no válido.');
    if (fd.date > today()) throw new Error('La fecha no puede ser futura.');
    S.weights[fd.date] = kg;
    commit();
    toast(`Peso guardado: ${fmt(kg, 1)} kg`);
  },
  journal(fd) {
    const data = { sleepH: num(fd.sleepH), steps: num(fd.steps), notes: fd.notes.trim() };
    for (const k of ['sleepQ', 'energy', 'mood', 'hunger', 'stress']) data[k] = num(fd[k]);
    S.journal[ui.journalDate] = data;
    commit();
    toast('Bitácora guardada');
  },
  exercise(fd, form) {
    const data = readExercise(fd);
    if (!data.name) throw new Error('Pon un nombre.');
    const id = form.dataset.id;
    if (id) Object.assign(exById(id), data);
    else {
      S.exercises.push({ id: 'u-' + uid(), ...data });
      closeModal();
    }
    commit();
    toast('Ejercicio guardado');
  },
  routine(fd, form) {
    const checked = new FormData(form).getAll('ex');
    if (!checked.length) throw new Error('Elige al menos un ejercicio.');
    const ordered = [...modalCtx.routineOrder.filter((id) => checked.includes(id)), ...checked.filter((id) => !modalCtx.routineOrder.includes(id))];
    const existing = S.routines.find((r) => r.id === modalCtx.routineId);
    if (existing) Object.assign(existing, { name: fd.name.trim(), exerciseIds: ordered });
    else S.routines.push({ id: 'r-' + uid(), name: fd.name.trim(), exerciseIds: ordered });
    closeModal();
    commit();
  },
};

document.addEventListener('click', (ev) => {
  const el = ev.target.closest('[data-action]');
  if (!el || el.disabled) return;
  const fn = actions[el.dataset.action];
  if (!fn) return;
  ev.preventDefault();
  fn(el, ev);
});

document.addEventListener('submit', (ev) => {
  const form = ev.target.closest('form[data-form]');
  if (!form) return;
  ev.preventDefault();
  try {
    forms[form.dataset.form](formData(form), form);
  } catch (e) {
    toast(e.message);
  }
});

document.addEventListener('input', (ev) => {
  const t = ev.target;
  if (t.id === 'food-q') $('#food-results').innerHTML = foodList(t.value);
  else if (t.id === 'ex-q') {
    const q = t.value.trim().toLowerCase();
    for (const li of document.querySelectorAll('#ex-results li')) li.hidden = q && !li.dataset.name.includes(q);
  } else if (t.id === 'qty-amount') $('#qty-preview').innerHTML = previewHtml(C.scaleFood(modalCtx.food, qtyGrams()));
  else if (t.dataset.set && S.activeWorkout) {
    const [i, j, field] = t.dataset.set.split(':');
    S.activeWorkout.exercises[i].sets[j][field] = t.value;
    save(S);
  } else if (t.dataset.wkField && S.activeWorkout) {
    S.activeWorkout[t.dataset.wkField] = t.value;
    save(S);
  }
});

document.addEventListener('change', (ev) => {
  const t = ev.target;
  if (t.id === 'qty-unit') {
    const food = modalCtx.food;
    const amount = $('#qty-amount');
    const cur = num(amount.value) ?? 0;
    // Convierte la cantidad para que el total no cambie al cambiar de unidad.
    amount.value = t.value === 'serving' ? +(cur / food.servingG).toFixed(2) : Math.round(cur * food.servingG);
    $('#qty-preview').innerHTML = previewHtml(C.scaleFood(food, qtyGrams()));
  } else if (t.id === 'import-file' && t.files[0]) {
    t.files[0].text().then((text) => {
      try {
        const data = importJson(text);
        if (!confirm('Esto reemplazará tus datos actuales. ¿Continuar?')) return;
        S = data;
        commit();
        toast('Datos importados');
      } catch (e) {
        toast(e.message);
      }
    });
    t.value = '';
  }
});

$('#modal').addEventListener('click', (ev) => {
  if (ev.target === ev.currentTarget) closeModal();
});

window.addEventListener('hashchange', () => {
  const tab = location.hash.slice(1);
  if (TITLES[tab] && tab !== ui.tab) {
    ui.tab = tab;
    ui.exerciseId = null;
    ui.workoutId = null;
    render();
  }
});

setInterval(() => {
  const el = $('#wk-timer');
  if (el && S.activeWorkout) el.textContent = elapsed(S.activeWorkout.start);
}, 30000);

const initial = location.hash.slice(1);
if (TITLES[initial]) ui.tab = initial;
render();

if ('serviceWorker' in navigator && location.protocol !== 'file:' && !window.MoiehFihAndroid) {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
