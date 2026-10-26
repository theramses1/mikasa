// Datos iniciales. Valores nutricionales aproximados por 100 g (fuentes tipo USDA);
// el usuario puede editarlos o crear los suyos.

// [nombre, kcal, proteína, carbohidratos, grasa, porción (g), nombre de porción]
const FOODS = [
  ['Pechuga de pollo cocida', 165, 31, 0, 3.6, 120, 'filete'],
  ['Arroz blanco cocido', 130, 2.7, 28, 0.3, 150, 'taza'],
  ['Huevo entero', 143, 12.6, 0.7, 9.5, 50, 'pieza'],
  ['Clara de huevo', 52, 11, 0.7, 0.2, 33, 'clara'],
  ['Avena en hojuelas', 389, 16.9, 66, 6.9, 40, 'media taza'],
  ['Plátano', 89, 1.1, 23, 0.3, 118, 'pieza'],
  ['Manzana', 52, 0.3, 14, 0.2, 180, 'pieza'],
  ['Fresas', 32, 0.7, 7.7, 0.3, 150, 'taza'],
  ['Leche entera', 61, 3.2, 4.8, 3.3, 240, 'vaso'],
  ['Leche descremada', 34, 3.4, 5, 0.1, 240, 'vaso'],
  ['Yogur griego natural 0 %', 59, 10.2, 3.6, 0.4, 170, 'envase'],
  ['Proteína whey', 400, 80, 8, 6, 30, 'scoop'],
  ['Atún en agua', 116, 26, 0, 1, 120, 'lata'],
  ['Salmón', 208, 20, 0, 13, 150, 'filete'],
  ['Carne molida de res 90/10 cocida', 217, 26, 0, 12, 120, 'porción'],
  ['Lomo de cerdo', 143, 26, 0, 3.5, 120, 'porción'],
  ['Jamón de pavo', 104, 17, 2, 3, 20, 'rebanada'],
  ['Tortilla de maíz', 218, 5.7, 44.6, 2.9, 30, 'pieza'],
  ['Pan integral', 247, 13, 41, 3.4, 30, 'rebanada'],
  ['Frijoles cocidos', 127, 8.7, 22.8, 0.5, 130, 'taza'],
  ['Papa cocida', 87, 1.9, 20, 0.1, 170, 'pieza'],
  ['Camote cocido', 90, 2, 20.7, 0.2, 150, 'pieza'],
  ['Pasta cocida', 158, 5.8, 31, 0.9, 140, 'taza'],
  ['Aguacate', 160, 2, 8.5, 14.7, 50, 'medio'],
  ['Aceite de oliva', 884, 0, 0, 100, 14, 'cucharada'],
  ['Almendras', 579, 21, 22, 50, 28, 'puño'],
  ['Crema de cacahuate', 588, 25, 20, 50, 16, 'cucharada'],
  ['Queso cottage', 98, 11, 3.4, 4.3, 110, 'media taza'],
  ['Queso panela', 229, 18, 3, 16, 40, 'rebanada'],
  ['Brócoli', 34, 2.8, 7, 0.4, 90, 'taza'],
  ['Espinaca', 23, 2.9, 3.6, 0.4, 30, 'taza'],
  ['Jitomate', 18, 0.9, 3.9, 0.2, 120, 'pieza'],
  ['Granola', 471, 10, 64, 20, 30, 'porción'],
  ['Miel', 304, 0.3, 82, 0, 21, 'cucharada'],
  ['Chocolate oscuro 70 %', 598, 7.8, 46, 43, 20, 'cuadros'],
  ['Refresco de cola', 42, 0, 10.6, 0, 355, 'lata'],
  ['Cerveza', 43, 0.5, 3.6, 0, 355, 'lata'],
];

// [nombre, grupo muscular, reps mín, reps máx, incremento kg]
const EXERCISES = [
  ['Press de banca', 'Pecho', 6, 10, 2.5],
  ['Press inclinado con mancuernas', 'Pecho', 8, 12, 2],
  ['Aperturas en polea', 'Pecho', 10, 15, 2.5],
  ['Fondos', 'Pecho', 6, 12, 2.5],
  ['Sentadilla', 'Cuádriceps', 5, 8, 2.5],
  ['Prensa', 'Cuádriceps', 8, 12, 5],
  ['Extensión de cuádriceps', 'Cuádriceps', 10, 15, 2.5],
  ['Peso muerto', 'Isquiotibiales', 3, 6, 5],
  ['Peso muerto rumano', 'Isquiotibiales', 6, 10, 2.5],
  ['Curl femoral', 'Isquiotibiales', 10, 15, 2.5],
  ['Hip thrust', 'Glúteos', 8, 12, 5],
  ['Dominadas', 'Espalda', 5, 10, 2.5],
  ['Jalón al pecho', 'Espalda', 8, 12, 2.5],
  ['Remo con barra', 'Espalda', 6, 10, 2.5],
  ['Remo en polea', 'Espalda', 10, 12, 2.5],
  ['Press militar', 'Hombros', 5, 8, 2.5],
  ['Elevaciones laterales', 'Hombros', 12, 20, 1],
  ['Face pull', 'Hombros', 12, 15, 2.5],
  ['Curl con barra', 'Bíceps', 8, 12, 2.5],
  ['Curl martillo', 'Bíceps', 10, 12, 2],
  ['Extensión de tríceps en polea', 'Tríceps', 10, 15, 2.5],
  ['Press francés', 'Tríceps', 8, 12, 2.5],
  ['Elevación de talones', 'Pantorrillas', 10, 15, 5],
  ['Crunch en polea', 'Abdomen', 10, 15, 2.5],
];

export const MUSCLES = ['Pecho', 'Espalda', 'Hombros', 'Bíceps', 'Tríceps', 'Cuádriceps', 'Isquiotibiales', 'Glúteos', 'Pantorrillas', 'Abdomen', 'Otro'];

const slug = (s) =>
  s
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/(^-|-$)/g, '');

export function seedFoods() {
  return FOODS.map(([name, kcal, p, c, f, servingG, servingName]) => ({
    id: 'f-' + slug(name),
    name,
    kcal,
    p,
    c,
    f,
    servingG,
    servingName,
    builtin: true,
  }));
}

export function seedExercises() {
  return EXERCISES.map(([name, muscle, repMin, repMax, increment]) => ({
    id: 'e-' + slug(name),
    name,
    muscle,
    repMin,
    repMax,
    increment,
    builtin: true,
  }));
}

export function seedRoutines() {
  return [
    {
      id: 'r-torso-a',
      name: 'Torso A',
      exerciseIds: ['e-press-de-banca', 'e-remo-con-barra', 'e-press-militar', 'e-jalon-al-pecho', 'e-curl-con-barra', 'e-extension-de-triceps-en-polea'],
    },
    {
      id: 'r-pierna-a',
      name: 'Pierna A',
      exerciseIds: ['e-sentadilla', 'e-peso-muerto-rumano', 'e-prensa', 'e-curl-femoral', 'e-elevacion-de-talones'],
    },
  ];
}
