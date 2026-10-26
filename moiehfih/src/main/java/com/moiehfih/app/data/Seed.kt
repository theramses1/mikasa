package com.moiehfih.app.data

/** Datos iniciales. Valores nutricionales aproximados por 100 g (tipo USDA); se pueden editar. */
object Seed {
    // nombre, kcal, proteína, carbos, grasa, porción (g), nombre de porción
    val foods: List<Food> = listOf(
        f("Pechuga de pollo cocida", 165, 31, 0, 3.6, 120, "filete"),
        f("Arroz blanco cocido", 130, 2.7, 28, 0.3, 150, "taza"),
        f("Huevo entero", 143, 12.6, 0.7, 9.5, 50, "pieza"),
        f("Clara de huevo", 52, 11, 0.7, 0.2, 33, "clara"),
        f("Avena en hojuelas", 389, 16.9, 66, 6.9, 40, "media taza"),
        f("Plátano", 89, 1.1, 23, 0.3, 118, "pieza"),
        f("Manzana", 52, 0.3, 14, 0.2, 180, "pieza"),
        f("Fresas", 32, 0.7, 7.7, 0.3, 150, "taza"),
        f("Leche entera", 61, 3.2, 4.8, 3.3, 240, "vaso"),
        f("Leche descremada", 34, 3.4, 5, 0.1, 240, "vaso"),
        f("Yogur griego natural 0 %", 59, 10.2, 3.6, 0.4, 170, "envase"),
        f("Proteína whey", 400, 80, 8, 6, 30, "scoop"),
        f("Atún en agua", 116, 26, 0, 1, 120, "lata"),
        f("Salmón", 208, 20, 0, 13, 150, "filete"),
        f("Carne molida de res 90/10 cocida", 217, 26, 0, 12, 120, "porción"),
        f("Lomo de cerdo", 143, 26, 0, 3.5, 120, "porción"),
        f("Jamón de pavo", 104, 17, 2, 3, 20, "rebanada"),
        f("Tortilla de maíz", 218, 5.7, 44.6, 2.9, 30, "pieza"),
        f("Pan integral", 247, 13, 41, 3.4, 30, "rebanada"),
        f("Frijoles cocidos", 127, 8.7, 22.8, 0.5, 130, "taza"),
        f("Papa cocida", 87, 1.9, 20, 0.1, 170, "pieza"),
        f("Camote cocido", 90, 2, 20.7, 0.2, 150, "pieza"),
        f("Pasta cocida", 158, 5.8, 31, 0.9, 140, "taza"),
        f("Aguacate", 160, 2, 8.5, 14.7, 50, "medio"),
        f("Aceite de oliva", 884, 0, 0, 100, 14, "cucharada"),
        f("Almendras", 579, 21, 22, 50, 28, "puño"),
        f("Crema de cacahuate", 588, 25, 20, 50, 16, "cucharada"),
        f("Queso cottage", 98, 11, 3.4, 4.3, 110, "media taza"),
        f("Queso panela", 229, 18, 3, 16, 40, "rebanada"),
        f("Brócoli", 34, 2.8, 7, 0.4, 90, "taza"),
        f("Espinaca", 23, 2.9, 3.6, 0.4, 30, "taza"),
        f("Jitomate", 18, 0.9, 3.9, 0.2, 120, "pieza"),
        f("Granola", 471, 10, 64, 20, 30, "porción"),
        f("Miel", 304, 0.3, 82, 0, 21, "cucharada"),
        f("Chocolate oscuro 70 %", 598, 7.8, 46, 43, 20, "cuadros"),
        f("Refresco de cola", 42, 0, 10.6, 0, 355, "lata"),
        f("Cerveza", 43, 0.5, 3.6, 0, 355, "lata"),
    )

    // nombre, músculo, reps mín, reps máx, incremento (kg)
    val exercises: List<Exercise> = listOf(
        e("Press de banca", "Pecho", 6, 10, 2.5),
        e("Press inclinado con mancuernas", "Pecho", 8, 12, 2.0),
        e("Aperturas en polea", "Pecho", 10, 15, 2.5),
        e("Fondos", "Pecho", 6, 12, 2.5),
        e("Sentadilla", "Cuádriceps", 5, 8, 2.5),
        e("Prensa", "Cuádriceps", 8, 12, 5.0),
        e("Extensión de cuádriceps", "Cuádriceps", 10, 15, 2.5),
        e("Peso muerto", "Isquiotibiales", 3, 6, 5.0),
        e("Peso muerto rumano", "Isquiotibiales", 6, 10, 2.5),
        e("Curl femoral", "Isquiotibiales", 10, 15, 2.5),
        e("Hip thrust", "Glúteos", 8, 12, 5.0),
        e("Dominadas", "Espalda", 5, 10, 2.5),
        e("Jalón al pecho", "Espalda", 8, 12, 2.5),
        e("Remo con barra", "Espalda", 6, 10, 2.5),
        e("Remo en polea", "Espalda", 10, 12, 2.5),
        e("Press militar", "Hombros", 5, 8, 2.5),
        e("Elevaciones laterales", "Hombros", 12, 20, 1.0),
        e("Face pull", "Hombros", 12, 15, 2.5),
        e("Curl con barra", "Bíceps", 8, 12, 2.5),
        e("Curl martillo", "Bíceps", 10, 12, 2.0),
        e("Extensión de tríceps en polea", "Tríceps", 10, 15, 2.5),
        e("Press francés", "Tríceps", 8, 12, 2.5),
        e("Elevación de talones", "Pantorrillas", 10, 15, 5.0),
        e("Crunch en polea", "Abdomen", 10, 15, 2.5),
    )

    /** Rutinas iniciales por nombre de ejercicio. */
    val routines: List<Pair<String, List<String>>> = listOf(
        "Torso A" to listOf(
            "Press de banca", "Remo con barra", "Press militar", "Jalón al pecho", "Curl con barra",
            "Extensión de tríceps en polea",
        ),
        "Pierna A" to listOf("Sentadilla", "Peso muerto rumano", "Prensa", "Curl femoral", "Elevación de talones"),
    )

    private fun f(name: String, kcal: Number, p: Number, c: Number, fat: Number, servingG: Number, servingName: String) =
        Food(0, name, Macros(kcal.toDouble(), p.toDouble(), c.toDouble(), fat.toDouble()), servingG.toDouble(), servingName, true)

    private fun e(name: String, muscle: String, repMin: Int, repMax: Int, inc: Double) =
        Exercise(0, name, muscle, repMin, repMax, inc, true)
}
