// Cálculos puros: fechas, tendencia de peso, gasto energético, objetivos y progresión.
// No dependen del DOM para poder probarlos con `node --test`.

export const KCAL_PER_KG = 7700;
export const MEALS = ['Desayuno', 'Comida', 'Cena', 'Snacks'];

export const ACTIVITY = {
  sedentario: { label: 'Sedentario (poco o nada de ejercicio)', factor: 1.2 },
  ligero: { label: 'Ligero (1-3 días/semana)', factor: 1.375 },
  moderado: { label: 'Moderado (3-5 días/semana)', factor: 1.55 },
  alto: { label: 'Alto (6-7 días/semana)', factor: 1.725 },
  muy_alto: { label: 'Muy alto (trabajo físico + entreno)', factor: 1.9 },
};

// ---------- Fechas (cadenas locales YYYY-MM-DD) ----------

const pad = (n) => String(n).padStart(2, '0');

export function ymd(d = new Date()) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export function parseYmd(s) {
  const [y, m, d] = s.split('-').map(Number);
  return new Date(y, m - 1, d, 12);
}

export function addDays(s, n) {
  const d = parseYmd(s);
  d.setDate(d.getDate() + n);
  return ymd(d);
}

export function daysBetween(a, b) {
  return Math.round((parseYmd(b) - parseYmd(a)) / 86400000);
}

export function round(x, step = 1) {
  return Math.round(x / step) * step;
}

// ---------- Nutrición ----------

export function kcalFromMacros(p, c, f) {
  return p * 4 + c * 4 + f * 9;
}

// Escala un alimento (valores por 100 g) a una cantidad en gramos.
export function scaleFood(food, grams) {
  const k = grams / 100;
  return {
    kcal: food.kcal * k,
    p: food.p * k,
    c: food.c * k,
    f: food.f * k,
  };
}

export function sumEntries(entries = []) {
  return entries.reduce(
    (t, e) => ({ kcal: t.kcal + e.kcal, p: t.p + e.p, c: t.c + e.c, f: t.f + e.f }),
    { kcal: 0, p: 0, c: 0, f: 0 },
  );
}

// Mifflin-St Jeor
export function bmr({ sex, age, heightCm, weightKg }) {
  const base = 10 * weightKg + 6.25 * heightCm - 5 * age;
  return sex === 'm' ? base + 5 : base - 161;
}

export function initialTdee(profile, weightKg) {
  const factor = ACTIVITY[profile.activity]?.factor ?? 1.4;
  return bmr({ ...profile, weightKg }) * factor;
}

// ---------- Peso de tendencia ----------

// Serie diaria desde la primera pesada hasta `endDate`. Los días sin pesada se
// interpolan linealmente entre pesadas (y se mantiene la última hacia delante);
// la tendencia es una media móvil exponencial (alpha 0.1) sobre esa serie.
export function trendSeries(weights, endDate, alpha = 0.1) {
  const dates = Object.keys(weights).sort();
  if (!dates.length) return [];
  const last = endDate && endDate > dates[dates.length - 1] ? endDate : dates[dates.length - 1];
  const out = [];
  let trend = weights[dates[0]];
  let i = 0;
  for (let d = dates[0]; d <= last; d = addDays(d, 1)) {
    while (i < dates.length - 1 && dates[i + 1] <= d) i++;
    const prev = dates[i];
    const next = dates[i + 1];
    let value;
    if (d === prev || !next) value = weights[prev];
    else {
      const t = daysBetween(prev, d) / daysBetween(prev, next);
      value = weights[prev] + (weights[next] - weights[prev]) * t;
    }
    trend = out.length ? trend + alpha * (value - trend) : value;
    out.push({ date: d, scale: weights[d] ?? null, trend });
  }
  return out;
}

export function trendOn(series, date) {
  if (!series.length) return null;
  if (date < series[0].date) return null;
  const idx = Math.min(series.length - 1, daysBetween(series[0].date, date));
  return series[idx].trend;
}

// ---------- Gasto energético (TDEE) adaptativo ----------

// Para cada día estima el gasto como: ingesta media de los días registrados de
// la ventana − (cambio de peso de tendencia × 7700 / días). El valor se mezcla
// suavemente con la estimación anterior, ponderado por cuántos días hay
// registrados, para que unos pocos días raros no lo descontrolen.
export function expenditureSeries({ profile, weights, foodLog }, endDate, opts = {}) {
  const windowDays = opts.windowDays ?? 21;
  const minLogged = opts.minLogged ?? 7;
  const smoothing = opts.smoothing ?? 0.15;
  const series = trendSeries(weights, endDate);
  if (!profile || !series.length) return [];
  const start = series[0].date;
  const intake = (d) => {
    const entries = foodLog[d];
    if (!entries || !entries.length) return null;
    return sumEntries(entries).kcal;
  };
  let tdee = profile.tdeeOverride || initialTdee(profile, series[0].trend);
  const out = [{ date: start, tdee, raw: null, logged: 0 }];
  for (let d = addDays(start, 1); d <= series[series.length - 1].date; d = addDays(d, 1)) {
    const from = addDays(d, -(windowDays - 1)) < start ? start : addDays(d, -(windowDays - 1));
    const span = daysBetween(from, d);
    let total = 0;
    let logged = 0;
    for (let x = from; x <= d; x = addDays(x, 1)) {
      const k = intake(x);
      if (k != null) {
        total += k;
        logged++;
      }
    }
    let raw = null;
    if (logged >= minLogged && span >= 6) {
      const dTrend = trendOn(series, d) - trendOn(series, from);
      raw = total / logged - (dTrend * KCAL_PER_KG) / span;
      raw = Math.min(6000, Math.max(900, raw));
      const confidence = Math.min(1, logged / 14);
      tdee += smoothing * confidence * (raw - tdee);
    }
    out.push({ date: d, tdee, raw, logged });
  }
  return out;
}

// ---------- Objetivos ----------

// goal.ratePct: % del peso corporal por semana (negativo = perder).
export function computeTargets({ tdee, weightKg, goal, sex }) {
  const delta = ((goal.ratePct / 100) * weightKg * KCAL_PER_KG) / 7;
  const floor = sex === 'm' ? 1500 : 1200;
  const kcal = Math.max(floor, round(tdee + delta, 10));
  const p = round(goal.proteinGPerKg * weightKg);
  const f = round(Math.max(0.6 * weightKg, (goal.fatPct * kcal) / 9));
  const c = Math.max(0, round((kcal - p * 4 - f * 9) / 4));
  return { kcal, p, c, f };
}

// ---------- Entrenamiento ----------

// Fórmula de Epley para 1RM estimado.
export function e1rm(weight, reps) {
  if (!reps || reps <= 0) return 0;
  if (reps === 1) return weight;
  return weight * (1 + reps / 30);
}

export function setVolume(sets) {
  return sets.reduce((v, s) => v + (s.weight || 0) * (s.reps || 0), 0);
}

function workingSets(sets) {
  return sets.filter((s) => s.done !== false && s.reps > 0);
}

export function exerciseHistory(workouts, exerciseId) {
  const out = [];
  for (const w of workouts) {
    for (const ex of w.exercises) {
      if (ex.exerciseId !== exerciseId) continue;
      const sets = workingSets(ex.sets);
      if (!sets.length) continue;
      out.push({
        date: w.date,
        workoutId: w.id,
        sets,
        best1rm: Math.max(...sets.map((s) => e1rm(s.weight || 0, s.reps))),
        topWeight: Math.max(...sets.map((s) => s.weight || 0)),
        volume: setVolume(sets),
      });
    }
  }
  return out.sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0));
}

// Doble progresión: se trabaja dentro de un rango de repeticiones; cuando todas
// las series con el peso principal llegan al tope del rango se sube el peso, si
// no se busca una repetición más. Dos sesiones seguidas por debajo del mínimo
// con el mismo peso => descarga del 10 %.
export function suggestNext(history, exercise) {
  const { repMin, repMax } = exercise;
  const inc = exercise.increment || 2.5;
  if (!history.length) {
    return {
      weight: null,
      reps: repMin,
      sets: 3,
      kind: 'new',
      reason: `Primera vez: elige un peso con el que hagas ${repMin}-${repMax} reps dejando 2-3 en reserva.`,
    };
  }
  const last = history[history.length - 1];
  const top = last.topWeight;
  const topSets = last.sets.filter((s) => (s.weight || 0) === top);
  const nSets = Math.max(topSets.length, 1);
  const minReps = Math.min(...topSets.map((s) => s.reps));
  const hardRpe = topSets.some((s) => s.rpe && s.rpe > 9);

  if (top === 0) {
    // Ejercicio con peso corporal: se progresa en repeticiones.
    return { weight: 0, reps: minReps + 1, sets: nSets, kind: 'reps', reason: 'Peso corporal: suma una repetición por serie.' };
  }
  if (minReps >= repMax && !hardRpe) {
    return {
      weight: top + inc,
      reps: repMin,
      sets: nSets,
      kind: 'up',
      reason: `Llegaste a ${repMax} reps en todas las series: sube ${inc} kg y vuelve a ${repMin} reps.`,
    };
  }
  if (minReps < repMin) {
    const prev = history[history.length - 2];
    const prevFailed =
      prev && prev.topWeight === top && Math.min(...prev.sets.filter((s) => (s.weight || 0) === top).map((s) => s.reps)) < repMin;
    if (prevFailed) {
      return {
        weight: Math.max(inc, round(top * 0.9, inc)),
        reps: repMin,
        sets: nSets,
        kind: 'deload',
        reason: 'Dos sesiones por debajo del rango con este peso: descarga un 10 % y reconstruye.',
      };
    }
    return { weight: top, reps: repMin, sets: nSets, kind: 'hold', reason: `Quedaste por debajo de ${repMin} reps: repite el peso y busca llegar al rango.` };
  }
  return {
    weight: top,
    reps: Math.min(repMax, minReps + 1),
    sets: nSets,
    kind: 'reps',
    reason: `Mantén ${top} kg y busca +1 rep (meta: ${repMax} reps en todas las series).`,
  };
}

// Series efectivas por grupo muscular en los últimos 7 días hasta `endDate`.
export function weeklySetsByMuscle(workouts, exercises, endDate) {
  const from = addDays(endDate, -6);
  const byId = Object.fromEntries(exercises.map((e) => [e.id, e]));
  const out = {};
  for (const w of workouts) {
    if (w.date < from || w.date > endDate) continue;
    for (const ex of w.exercises) {
      const muscle = byId[ex.exerciseId]?.muscle || 'Otro';
      out[muscle] = (out[muscle] || 0) + workingSets(ex.sets).length;
    }
  }
  return out;
}
