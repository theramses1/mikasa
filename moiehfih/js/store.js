// Persistencia local (localStorage) + exportar/importar + datos de demostración.
import { seedFoods, seedExercises, seedRoutines } from './data.js';
import { addDays, ymd, round, scaleFood, MEALS } from './calc.js';

const KEY = 'moiehfih-v1';
const OLD_KEY = 'mikasa-fit-v1'; // nombre anterior de la app

export const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 7);

export function defaultState() {
  return {
    version: 1,
    profile: null,
    goal: { type: 'maintain', ratePct: 0, proteinGPerKg: 2, fatPct: 0.27 },
    targets: null, // { date, kcal, p, c, f, tdee, manual }
    checkins: [],
    foods: seedFoods(),
    foodLog: {}, // { 'YYYY-MM-DD': [{ id, meal, foodId, name, grams, kcal, p, c, f }] }
    weights: {}, // { 'YYYY-MM-DD': kg }
    exercises: seedExercises(),
    routines: seedRoutines(),
    workouts: [], // [{ id, date, name, start, end, notes, exercises: [{ exerciseId, sets: [{ weight, reps, rpe, done }] }] }]
    activeWorkout: null,
    journal: {}, // { 'YYYY-MM-DD': { sleepH, sleepQ, energy, mood, hunger, stress, steps, notes } }
  };
}

function migrate(s) {
  const base = defaultState();
  const out = { ...base, ...s };
  // Añade alimentos/ejercicios nuevos de la base sin pisar los del usuario.
  for (const key of ['foods', 'exercises']) {
    const ids = new Set(out[key].map((x) => x.id));
    for (const item of base[key]) if (!ids.has(item.id)) out[key].push(item);
  }
  return out;
}

export function load() {
  try {
    const raw = localStorage.getItem(KEY) ?? localStorage.getItem(OLD_KEY);
    if (raw) return migrate(JSON.parse(raw));
  } catch (e) {
    console.error('No se pudieron leer los datos guardados', e);
  }
  return defaultState();
}

export function save(state) {
  try {
    localStorage.setItem(KEY, JSON.stringify(state));
  } catch (e) {
    console.error('No se pudieron guardar los datos', e);
    alert('No se pudieron guardar los datos (¿almacenamiento lleno o en modo privado?).');
  }
}

export function exportJson(state) {
  return JSON.stringify(state, null, 2);
}

export function importJson(text) {
  const data = JSON.parse(text);
  if (!data || typeof data !== 'object' || !('foodLog' in data) || !('weights' in data)) {
    throw new Error('El archivo no parece una copia de seguridad de esta app.');
  }
  return migrate(data);
}

// Genera ~10 semanas de datos realistas para explorar la app.
export function demoState(today = ymd()) {
  const s = defaultState();
  const days = 70;
  const start = addDays(today, -days + 1);
  s.profile = { name: 'Demo', sex: 'm', age: 29, heightCm: 178, activity: 'moderado' };
  s.goal = { type: 'lose', ratePct: -0.5, proteinGPerKg: 2, fatPct: 0.27 };
  let rnd = 42;
  const rand = () => ((rnd = (rnd * 16807) % 2147483647) / 2147483647);
  const trueTdee = 2750;
  let fat = 84; // peso "real" sin ruido
  const foods = Object.fromEntries(s.foods.map((f) => [f.id, f]));
  const plan = [
    ['Desayuno', 'f-avena-en-hojuelas', 60],
    ['Desayuno', 'f-leche-descremada', 250],
    ['Desayuno', 'f-platano', 120],
    ['Desayuno', 'f-proteina-whey', 30],
    ['Comida', 'f-pechuga-de-pollo-cocida', 180],
    ['Comida', 'f-arroz-blanco-cocido', 220],
    ['Comida', 'f-aguacate', 50],
    ['Comida', 'f-brocoli', 100],
    ['Cena', 'f-huevo-entero', 150],
    ['Cena', 'f-tortilla-de-maiz', 90],
    ['Cena', 'f-frijoles-cocidos', 130],
    ['Snacks', 'f-yogur-griego-natural-0', 170],
    ['Snacks', 'f-almendras', 28],
  ];
  for (let i = 0; i < days; i++) {
    const d = addDays(start, i);
    const skip = rand() < 0.08 && i < days - 1;
    if (!skip) {
      const factor = 0.85 + rand() * 0.35;
      const entries = plan.map(([meal, foodId, g]) => {
        const food = foods[foodId];
        const grams = round(g * factor, 5);
        return { id: uid() + i, meal, foodId, name: food.name, grams, ...scaleFood(food, grams) };
      });
      if (rand() < 0.2) {
        const beer = foods['f-cerveza'];
        entries.push({ id: uid() + 'b' + i, meal: 'Snacks', foodId: beer.id, name: beer.name, grams: 710, ...scaleFood(beer, 710) });
      }
      s.foodLog[d] = entries;
      const kcal = entries.reduce((t, e) => t + e.kcal, 0);
      fat += (kcal - trueTdee) / 7700;
    } else {
      fat += (2600 - trueTdee) / 7700;
    }
    if (rand() < 0.85) s.weights[d] = round(fat + (rand() - 0.5) * 1.6, 0.1);
    s.journal[d] = {
      sleepH: round(6 + rand() * 2.5, 0.5),
      sleepQ: 2 + Math.floor(rand() * 4),
      energy: 2 + Math.floor(rand() * 4),
      mood: 2 + Math.floor(rand() * 4),
      hunger: 1 + Math.floor(rand() * 5),
      stress: 1 + Math.floor(rand() * 4),
      steps: Math.round(5000 + rand() * 7000),
      notes: i % 9 === 0 ? 'Buen día, mucha energía en el gym.' : '',
    };
  }
  // Entrenos: Torso A lunes/jueves, Pierna A martes/viernes con progresión.
  const strength = {};
  for (let i = 0; i < days; i++) {
    const d = addDays(start, i);
    const dow = new Date(d + 'T12:00').getDay();
    const routine = dow === 1 || dow === 4 ? s.routines[0] : dow === 2 || dow === 5 ? s.routines[1] : null;
    if (!routine) continue;
    const exercises = routine.exerciseIds.map((exId) => {
      const ex = s.exercises.find((e) => e.id === exId);
      const st = (strength[exId] ??= { w: { 'e-press-de-banca': 70, 'e-sentadilla': 90, 'e-peso-muerto-rumano': 80, 'e-prensa': 140, 'e-press-militar': 45, 'e-remo-con-barra': 60 }[exId] ?? 25, r: ex.repMin });
      const sets = [0, 1, 2].map((k) => ({ weight: st.w, reps: Math.max(ex.repMin - 1, st.r - (k === 2 && rand() < 0.5 ? 1 : 0)), rpe: 8, done: true }));
      if (sets.every((x) => x.reps >= ex.repMax)) {
        st.w += ex.increment;
        st.r = ex.repMin;
      } else st.r = Math.min(ex.repMax, st.r + (rand() < 0.7 ? 1 : 0));
      return { exerciseId: exId, sets };
    });
    const t = new Date(d + 'T18:00').getTime();
    s.workouts.push({ id: uid() + i, date: d, name: routine.name, start: t, end: t + 65 * 60000, notes: '', exercises });
  }
  return s;
}

export { MEALS };
