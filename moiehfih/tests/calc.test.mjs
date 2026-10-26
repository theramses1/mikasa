import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../js/calc.js';

test('fechas', () => {
  assert.equal(C.addDays('2026-02-28', 1), '2026-03-01');
  assert.equal(C.addDays('2026-03-01', -1), '2026-02-28');
  assert.equal(C.daysBetween('2026-10-01', '2026-10-26'), 25);
  // Cambio de horario (DST) no debe romper el conteo de días.
  assert.equal(C.daysBetween('2026-03-01', '2026-04-01'), 31);
});

test('escalar alimento y sumar', () => {
  const food = { kcal: 165, p: 31, c: 0, f: 3.6 };
  const m = C.scaleFood(food, 200);
  assert.equal(m.kcal, 330);
  assert.equal(m.p, 62);
  const t = C.sumEntries([m, m]);
  assert.equal(t.kcal, 660);
});

test('tendencia interpola y suaviza', () => {
  const s = C.trendSeries({ '2026-01-01': 80, '2026-01-03': 82 }, '2026-01-05');
  assert.equal(s.length, 5);
  assert.equal(s[0].trend, 80);
  assert.equal(s[1].scale, null);
  // día 2 interpolado = 81 -> 80 + 0.1 * 1
  assert.ok(Math.abs(s[1].trend - 80.1) < 1e-9);
  assert.ok(s[4].trend < 82 && s[4].trend > 80);
  assert.equal(C.trendOn(s, '2025-12-31'), null);
});

test('gasto energético converge al real', () => {
  const profile = { sex: 'm', age: 30, heightCm: 180, activity: 'sedentario' }; // estimación inicial baja
  const trueTdee = 2800;
  const intake = 2300;
  const weights = {};
  const foodLog = {};
  let w = 85;
  const start = '2026-01-01';
  for (let i = 0; i < 120; i++) {
    const d = C.addDays(start, i);
    foodLog[d] = [{ kcal: intake, p: 0, c: 0, f: 0 }];
    w += (intake - trueTdee) / C.KCAL_PER_KG;
    weights[d] = w;
  }
  const exp = C.expenditureSeries({ profile, weights, foodLog }, C.addDays(start, 119));
  const initial = exp[0].tdee;
  const final = exp[exp.length - 1].tdee;
  assert.ok(initial < 2500, `inicial ${initial}`);
  assert.ok(Math.abs(final - trueTdee) < 120, `final ${final}`);
});

test('objetivos de déficit', () => {
  const t = C.computeTargets({ tdee: 2800, weightKg: 80, goal: { ratePct: -0.5, proteinGPerKg: 2, fatPct: 0.25 }, sex: 'm' });
  // 0.5 % de 80 kg = 0.4 kg/sem ≈ 440 kcal/día
  assert.equal(t.kcal, 2360);
  assert.equal(t.p, 160);
  assert.ok(Math.abs(t.p * 4 + t.c * 4 + t.f * 9 - t.kcal) < 10);
  const floor = C.computeTargets({ tdee: 1300, weightKg: 60, goal: { ratePct: -1, proteinGPerKg: 2, fatPct: 0.25 }, sex: 'f' });
  assert.equal(floor.kcal, 1200);
});

const bench = { id: 'b', repMin: 6, repMax: 10, increment: 2.5 };
const session = (date, weight, reps) => ({ id: date, date, exercises: [{ exerciseId: 'b', sets: reps.map((r) => ({ weight, reps: r, done: true })) }] });

test('progresión: sube peso al tope del rango', () => {
  const h = C.exerciseHistory([session('2026-01-01', 60, [10, 10, 10])], 'b');
  const s = C.suggestNext(h, bench);
  assert.equal(s.kind, 'up');
  assert.equal(s.weight, 62.5);
  assert.equal(s.reps, 6);
});

test('progresión: +1 rep dentro del rango', () => {
  const s = C.suggestNext(C.exerciseHistory([session('2026-01-01', 60, [8, 7, 7])], 'b'), bench);
  assert.equal(s.kind, 'reps');
  assert.equal(s.weight, 60);
  assert.equal(s.reps, 8);
});

test('progresión: descarga tras dos fallos', () => {
  const ws = [session('2026-01-01', 70, [5, 5, 4]), session('2026-01-04', 70, [5, 4, 4])];
  const s = C.suggestNext(C.exerciseHistory(ws, 'b'), bench);
  assert.equal(s.kind, 'deload');
  assert.equal(s.weight, 62.5);
  const one = C.suggestNext(C.exerciseHistory(ws.slice(0, 1), 'b'), bench);
  assert.equal(one.kind, 'hold');
});

test('progresión: primera vez', () => {
  assert.equal(C.suggestNext([], bench).kind, 'new');
});

test('1RM y series semanales', () => {
  assert.equal(C.e1rm(100, 1), 100);
  assert.ok(Math.abs(C.e1rm(100, 10) - 133.33) < 0.01);
  const sets = C.weeklySetsByMuscle([session('2026-01-05', 60, [8, 8, 8]), session('2025-12-01', 60, [8])], [{ id: 'b', muscle: 'Pecho' }], '2026-01-07');
  assert.deepEqual(sets, { Pecho: 3 });
});
