package com.moiehfih.app.data

/** Datos iniciales. Valores nutricionales aproximados por 100 g (tipo USDA); se pueden editar. */
object Seed {
    // nombre, kcal, proteína, carbos, grasa, azúcares, fibra, porción, nombre de porción.
    // Valores por 100 g (o 100 ml en las bebidas con ml); los platillos son preparaciones caseras típicas.
    val foods: List<Food> = listOf(
        // Básicos
        f("Pechuga de pollo cocida", 165, 31, 0, 3.6, 0, 0, 120, "filete"),
        f("Arroz blanco cocido", 130, 2.7, 28, 0.3, 0.1, 0.4, 150, "taza"),
        f("Huevo entero", 143, 12.6, 0.7, 9.5, 0.4, 0, 50, "pieza"),
        f("Clara de huevo", 52, 11, 0.7, 0.2, 0.7, 0, 33, "clara"),
        f("Avena en hojuelas", 389, 16.9, 66, 6.9, 1, 10.6, 40, "media taza"),
        f("Plátano", 89, 1.1, 23, 0.3, 12.2, 2.6, 118, "pieza"),
        f("Manzana", 52, 0.3, 14, 0.2, 10.4, 2.4, 180, "pieza"),
        f("Fresas", 32, 0.7, 7.7, 0.3, 4.9, 2, 150, "taza"),
        ml("Leche entera", 61, 3.2, 4.8, 3.3, 4.8, 0, 240, "vaso"),
        ml("Leche descremada", 34, 3.4, 5, 0.1, 5.0, 0, 240, "vaso"),
        ml("Jugo de naranja", 45, 0.7, 10.4, 0.2, 8.4, 0.2, 250, "vaso"),
        ml("Bebida de almendra sin azúcar", 15, 0.6, 0.3, 1.2, 0, 0.3, 240, "vaso"),
        f("Yogur griego natural 0 %", 59, 10.2, 3.6, 0.4, 3.2, 0, 170, "envase"),
        f("Proteína whey", 400, 80, 8, 6, 5, 0, 30, "scoop"),
        f("Atún en agua", 116, 26, 0, 1, 0, 0, 120, "lata"),
        f("Salmón", 208, 20, 0, 13, 0, 0, 150, "filete"),
        f("Carne molida de res 90/10 cocida", 217, 26, 0, 12, 0, 0, 120, "porción"),
        f("Lomo de cerdo", 143, 26, 0, 3.5, 0, 0, 120, "porción"),
        f("Jamón de pavo", 104, 17, 2, 3, 2, 0, 20, "rebanada"),
        f("Tortilla de maíz", 218, 5.7, 44.6, 2.9, 0.9, 6.3, 30, "pieza"),
        f("Pan integral", 247, 13, 41, 3.4, 6, 6, 30, "rebanada"),
        f("Frijoles cocidos", 127, 8.7, 22.8, 0.5, 0.3, 7.5, 130, "taza"),
        f("Papa cocida", 87, 1.9, 20, 0.1, 0.9, 1.8, 170, "pieza"),
        f("Camote cocido", 90, 2, 20.7, 0.2, 6.5, 3.3, 150, "pieza"),
        f("Pasta cocida", 158, 5.8, 31, 0.9, 0.6, 1.8, 140, "taza"),
        f("Aguacate", 160, 2, 8.5, 14.7, 0.7, 6.7, 50, "medio"),
        f("Aceite de oliva", 884, 0, 0, 100, 0, 0, 14, "cucharada"),
        f("Almendras", 579, 21, 22, 50, 4.4, 12.5, 28, "puño"),
        f("Crema de cacahuate", 588, 25, 20, 50, 9, 6, 16, "cucharada"),
        f("Queso cottage", 98, 11, 3.4, 4.3, 2.7, 0, 110, "media taza"),
        f("Queso panela", 229, 18, 3, 16, 3, 0, 40, "rebanada"),
        f("Brócoli", 34, 2.8, 7, 0.4, 1.7, 2.6, 90, "taza"),
        f("Espinaca", 23, 2.9, 3.6, 0.4, 0.4, 2.2, 30, "taza"),
        f("Jitomate", 18, 0.9, 3.9, 0.2, 2.6, 1.2, 120, "pieza"),
        f("Granola", 471, 10, 64, 20, 25, 7, 30, "porción"),
        f("Miel", 304, 0.3, 82, 0, 82, 0.2, 21, "cucharada"),
        f("Chocolate oscuro 70 %", 598, 7.8, 46, 43, 24, 11, 20, "cuadros"),
        ml("Refresco de cola", 42, 0, 10.6, 0, 10.6, 0, 355, "lata"),
        ml("Cerveza", 43, 0.5, 3.6, 0, 0, 0, 355, "lata"),

        // Comida mexicana: antojitos
        f("Gordita de chicharrón prensado", 300, 10, 32, 15, 1, 3, 120, "pieza"),
        f("Gordita de frijol con queso", 260, 9, 33, 10, 1.5, 4.5, 120, "pieza"),
        f("Gordita de harina (dulce)", 380, 6, 58, 14, 18, 1.5, 60, "pieza"),
        f("Enchiladas rojas con pollo", 170, 9, 16, 8, 2.5, 2.5, 250, "orden (3)"),
        f("Enchiladas verdes con pollo", 160, 10, 15, 7, 2, 2.5, 250, "orden (3)"),
        f("Enchiladas suizas", 190, 10, 14, 11, 2.5, 1.8, 250, "orden (3)"),
        f("Enmoladas de pollo", 210, 10, 18, 11, 6, 3, 250, "orden (3)"),
        f("Chilaquiles rojos", 190, 6, 22, 9, 2.5, 3, 250, "plato"),
        f("Chilaquiles verdes con pollo", 175, 9, 19, 7.5, 2, 2.5, 300, "plato"),
        f("Taco al pastor", 220, 12, 20, 10, 2.5, 2.5, 90, "taco"),
        f("Taco de bistec", 210, 14, 18, 9, 1, 2.5, 95, "taco"),
        f("Taco de suadero", 250, 12, 18, 14.5, 0.5, 2.5, 95, "taco"),
        f("Taco de carnitas", 240, 14, 18, 12.5, 0.5, 2.5, 95, "taco"),
        f("Taco de barbacoa", 205, 14, 18, 8.5, 0.5, 2.5, 100, "taco"),
        f("Taco de canasta", 230, 6, 26, 11, 1, 3.5, 70, "taco"),
        f("Flauta de pollo", 260, 11, 22, 14, 1, 3, 70, "pieza"),
        f("Quesadilla de queso (maíz)", 280, 13, 24, 15, 1, 3, 100, "pieza"),
        f("Quesadilla de harina con queso", 320, 13, 30, 16, 2, 1.5, 110, "pieza"),
        f("Sope sencillo", 230, 7, 28, 10, 1.5, 4, 110, "pieza"),
        f("Huarache con bistec", 230, 12, 25, 9, 1.5, 4, 220, "pieza"),
        f("Tlayuda", 250, 12, 25, 11, 1.5, 5, 350, "pieza"),
        f("Tostada de tinga de pollo", 210, 12, 18, 10, 2, 3, 100, "pieza"),
        f("Tostada horneada", 430, 9, 68, 13, 1, 9, 12, "pieza"),
        f("Totopos", 500, 7, 63, 25, 1, 5.5, 30, "puño"),
        f("Tortilla de harina", 310, 8, 51, 8, 2.5, 2.5, 45, "pieza"),
        f("Tamal de pollo en salsa verde", 220, 7, 25, 10, 1, 2.5, 150, "pieza"),
        f("Tamal de rajas con queso", 230, 6, 26, 11, 1.5, 2.5, 150, "pieza"),
        f("Tamal dulce", 260, 3, 40, 10, 15, 1.5, 120, "pieza"),
        f("Torta de jamón", 240, 11, 30, 8.5, 4, 2.5, 250, "pieza"),
        f("Torta ahogada", 220, 11, 25, 8, 3, 2.5, 330, "pieza"),
        f("Chile relleno capeado (queso)", 190, 8, 9, 14, 3, 1.8, 180, "pieza"),
        f("Esquites", 150, 4, 18, 7, 3.5, 2.2, 200, "vaso"),
        f("Elote cocido", 96, 3.4, 21, 1.5, 4.5, 2.4, 150, "pieza"),

        // Comida mexicana: guisados, sopas y guarniciones
        f("Pozole rojo de cerdo", 85, 6.5, 8, 3, 1, 1.8, 450, "plato"),
        f("Menudo", 75, 8, 4, 3, 0.5, 0.8, 450, "plato"),
        f("Caldo de res", 50, 5, 4, 1.5, 1, 1, 450, "plato"),
        f("Sopa de fideo", 60, 1.5, 9, 2, 1.5, 0.6, 300, "plato"),
        f("Arroz rojo a la mexicana", 150, 2.7, 27, 3.5, 1, 0.8, 150, "taza"),
        f("Frijoles refritos", 140, 6.5, 17, 5.5, 0.5, 5.5, 130, "taza"),
        f("Frijoles charros", 110, 6.5, 12, 4, 1, 4, 250, "plato"),
        f("Mole poblano (salsa)", 210, 4.5, 18, 14, 9, 4, 80, "porción"),
        f("Cochinita pibil", 210, 22, 3, 12, 1.5, 0.5, 150, "porción"),
        f("Tinga de pollo", 140, 15, 5, 6.5, 2.5, 1.2, 150, "porción"),
        f("Carne asada (arrachera)", 230, 26, 0, 14, 0, 0, 150, "porción"),
        f("Chorizo", 455, 24, 2, 38, 0, 0, 50, "porción"),
        f("Chicharrón de cerdo", 545, 61, 0, 31, 0, 0, 30, "porción"),
        f("Nopales cocidos", 15, 1.4, 3.3, 0.1, 1.1, 2, 150, "taza"),
        f("Queso Oaxaca", 300, 22, 2, 22, 1, 0, 30, "porción"),
        f("Queso fresco", 299, 18, 3, 24, 2, 0, 30, "rebanada"),
        f("Crema ácida", 330, 2.5, 3, 34, 3, 0, 15, "cucharada"),

        // Chiles y salsas
        f("Chile jalapeño", 29, 0.9, 6.5, 0.4, 4.1, 2.8, 15, "pieza"),
        f("Chile serrano", 32, 1.7, 6.7, 0.4, 3.8, 3.7, 6, "pieza"),
        f("Chile poblano", 20, 0.9, 4.6, 0.2, 2.4, 1.7, 120, "pieza"),
        f("Chile habanero", 40, 1.9, 8.8, 0.4, 5, 1.5, 8, "pieza"),
        f("Chiles en vinagre", 25, 0.8, 4.5, 0.6, 2, 2.5, 30, "porción"),
        f("Salsa verde", 30, 1, 6, 0.6, 3.5, 1.8, 30, "2 cucharadas"),
        f("Salsa roja de molcajete", 32, 1.3, 6.5, 0.4, 4, 1.8, 30, "2 cucharadas"),
        f("Pico de gallo", 20, 0.8, 4.3, 0.2, 2.5, 1.2, 60, "porción"),
        f("Guacamole", 155, 2, 9, 13.5, 0.8, 6, 60, "porción"),

        // Frutas y verduras
        f("Mango", 60, 0.8, 15, 0.4, 13.7, 1.6, 200, "pieza"),
        f("Papaya", 43, 0.5, 11, 0.3, 7.8, 1.7, 150, "taza"),
        f("Jícama", 38, 0.7, 9, 0.1, 1.8, 4.9, 120, "taza"),
        f("Pepino", 15, 0.7, 3.6, 0.1, 1.7, 0.5, 100, "medio"),
        f("Limón", 29, 1.1, 9, 0.3, 2.5, 2.8, 50, "pieza"),

        // Bebidas y postres
        ml("Agua de horchata", 70, 0.5, 15, 1, 12, 0.2, 250, "vaso"),
        ml("Agua de jamaica", 40, 0, 10, 0, 10, 0, 250, "vaso"),
        ml("Atole / champurrado", 85, 2.5, 15, 2, 9, 0.8, 250, "taza"),
        ml("Café de olla", 35, 0.2, 9, 0, 8.5, 0, 240, "taza"),
        f("Pan dulce (concha)", 390, 7, 56, 15, 18, 1.5, 70, "pieza"),
        f("Churro", 450, 5, 50, 25, 15, 1.5, 30, "pieza"),
        f("Arroz con leche", 140, 3.5, 24, 3.5, 13, 0.3, 150, "taza"),
        f("Flan napolitano", 210, 6, 30, 7, 28, 0, 120, "rebanada"),
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

    private fun f(name: String, kcal: Number, p: Number, c: Number, fat: Number, sugar: Number, fiber: Number, serving: Number, servingName: String) =
        Food(
            0, name, Macros(kcal.toDouble(), p.toDouble(), c.toDouble(), fat.toDouble(), sugar.toDouble(), fiber.toDouble()),
            serving.toDouble(), servingName, true,
        )

    private fun ml(name: String, kcal: Number, p: Number, c: Number, fat: Number, sugar: Number, fiber: Number, serving: Number, servingName: String) =
        f(name, kcal, p, c, fat, sugar, fiber, serving, servingName).copy(unit = FoodUnit.ML)

    private fun e(name: String, muscle: String, repMin: Int, repMax: Int, inc: Double) =
        Exercise(0, name, muscle, repMin, repMax, inc, true)
}
