# Puzzles de Mate

App de Android de **puzzles de ajedrez**: el tablero muestra una posición de una
partida (real o generada) y hay que encontrar la jugada o la secuencia de
jugadas que da **jaque mate**. Inspirada en los puzzles de chess.com, pero sin
límite diario y sin conexión a internet.

## Características

- **Más de mil puzzles** incluidos (mate en 1, 2, 3 y 4+), todos verificados
  con Stockfish: la solución es única en cada jugada y el rival responde con la
  defensa más resistente.
- **Partidas célebres**: remates reales de Morphy (la partida de la Ópera),
  Anderssen (la Inmortal y la Siempreviva), Réti–Tartakower, Legal… y trampas
  clásicas como el mate del loco o el del pastor.
- **Rating de puzzles** tipo Elo: el modo "Puzzles" elige posiciones cercanas a
  tu nivel y sube o baja tu rating (cada puzzle cuenta solo la primera vez).
- Modos de práctica por número de jugadas, y **Repasar fallados**.
- Como en chess.com: el rival hace su última jugada (animada), tú buscas el
  mate, el rival responde a tus jugadas correctas, y en la jugada final se
  acepta **cualquier mate**, aunque no sea el de la solución.
- Mover tocando origen y destino o **arrastrando** la pieza; casillas
  posibles marcadas, coronación con selector, resaltado de jaque y de última
  jugada.
- **Pista** (marca la pieza a mover) y **Solución** (reproduce las jugadas);
  ambas cuentan como fallo.
- Rachas, mejor racha y rating máximo guardados en el móvil.
- Notación algebraica en español (R, D, T, A, C).

## Estructura

```
chess/
├── build-apk.sh                  # Compila el APK sin Gradle (solo SDK + kotlinc)
├── tools/
│   ├── generate_puzzles.py       # Generador de puzzles con Stockfish
│   └── famous_games.py           # Partidas célebres (SAN) usadas como puzzles
└── src/main/
    ├── assets/
    │   ├── puzzles.tsv           # Base de puzzles
    │   └── fonts/chess_pieces.ttf  # Glifos de piezas (subconjunto de DejaVu Sans)
    └── java/com/mikasa/chess/
        ├── engine/               # Motor: posición, jugadas legales, FEN, SAN
        ├── puzzle/               # Puzzle, sesión de resolución, rating, modos
        ├── data/                 # Carga de assets y SharedPreferences
        └── ui/                   # MainActivity, PuzzleActivity, BoardView
```

La app no usa AndroidX ni otras librerías: solo el framework de Android y la
librería estándar de Kotlin.

### Formato de `puzzles.tsv`

```
id  fen  jugadas_uci  rating  mate_en  fuente
```

`fen` es la posición **antes** de la última jugada del rival. La primera jugada
de la lista es esa jugada del rival (la app la anima al empezar) y el resto es
la solución, alternando jugador y rival, terminando en mate.

## Compilar

Con Android Studio o Gradle (JDK 17):

```bash
./gradlew :chess:assembleDebug
# APK en chess/build/outputs/apk/debug/chess-debug.apk
./gradlew :chess:testDebugUnitTest   # motor, puzzles y pantallas (Robolectric)
```

Sin Gradle, con las herramientas del SDK (`aapt2`, `zipalign`, `apksigner`),
`d8` o `dx`, y `kotlinc` 2.x:

```bash
chess/build-apk.sh
# APK en chess/build/puzzles-de-mate.apk
```

El workflow `.github/workflows/chess-apk.yml` ejecuta los tests y compila el APK
en GitHub Actions; se descarga desde la pestaña *Actions* (artefacto
`puzzles-de-mate-apk`).

## Regenerar los puzzles

Requiere [python-chess](https://pypi.org/project/chess/) y Stockfish:

```bash
cd chess/tools
python3 generate_puzzles.py --games 1600 --seed 2026 --out a.tsv
python3 generate_puzzles.py --games 2000 --seed 2027 --out b.tsv
python3 generate_puzzles.py --merge a.tsv b.tsv --out ../src/main/assets/puzzles.tsv
```

El generador juega partidas de Stockfish con jugadas semi-aleatorias (para que
haya errores tácticos) y, en cuanto un bando tiene mate forzado en ≤ 4, lo
verifica con un análisis más profundo y lo descarta si hay más de un camino al
mate. La dificultad se estima a partir del número de jugadas, si la primera es
silenciosa (sin jaque ni captura), si es un sacrificio y la profundidad que
necesita el motor para verla.

## Créditos

- Piezas: glifos de [DejaVu Sans](https://dejavu-fonts.github.io/) (licencia
  libre de Bitstream Vera / dominio público).
- Puzzles verificados con [Stockfish](https://stockfishchess.org/) (GPLv3;
  solo se usa para generar los datos, no se incluye en la app).
