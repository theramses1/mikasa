# MoiehFih

App (PWA) para registrar **dieta, macros, peso corporal, entrenamientos y bitácora
diaria**, inspirada en MacroFactor: estima tu gasto energético real a partir de
tus datos y ajusta tus calorías y macros cada semana.

Funciona sin conexión, se instala en el móvil («Añadir a pantalla de inicio») y
guarda todo en el dispositivo (sin cuentas ni servidores).

## Funciones

| Sección | Qué hace |
| --- | --- |
| **Inicio** | Calorías y macros del día frente a tu objetivo, gasto energético estimado, peso de tendencia, plan actual y check-in semanal. |
| **Dieta** | Registro por comida (desayuno, comida, cena, snacks). Buscador con ~40 alimentos comunes y recientes, alimentos propios (por 100 g o por porción), entrada rápida de macros, editar cantidades, copiar el día anterior y promedios de 7 días. |
| **Entreno** | Rutinas, entreno en vivo (kg, reps, RPE y ✓ por serie), **sugerencia de carga** por ejercicio (escalamiento por doble progresión), historial, 1RM estimado por sesión, récords y series semanales por grupo muscular. |
| **Peso** | Registro de pesadas, peso de tendencia (suavizado), cambio a 7/30 días, gráfica de peso y gráfica de gasto energético frente a ingesta. |
| **Bitácora** | Sueño, pasos, calidad del sueño, energía, ánimo, hambre, estrés y notas por día, junto al resumen automático del día (calorías, macros, peso y entreno). |
| **Ajustes** | Perfil, objetivo (perder, mantener o ganar a un % semanal), proteína g/kg, % de grasa, modo Coach o Manual, exportar/importar copia de seguridad y datos de demostración. |

## Algoritmos

- **Peso de tendencia:** media móvil exponencial (α = 0.1) sobre las pesadas;
  los días sin pesada se interpolan.
- **Gasto energético (TDEE):** arranca con Mifflin-St Jeor × actividad y se
  corrige cada día con `ingesta media − Δ tendencia × 7700 / días` en una ventana
  de 21 días (mínimo 7 días registrados), con suavizado según cuántos días
  hayas registrado.
- **Objetivos:** `kcal = TDEE + % semanal × peso × 7700 / 7`; proteína =
  g/kg × peso; grasa = % de las kcal (mínimo 0.6 g/kg); el resto, carbohidratos.
  Se recalculan en el **check-in semanal** (modo Coach).
- **Progresión:** cada ejercicio tiene un rango de reps y un incremento. Si
  completas el máximo del rango en todas las series → sube el peso; si no → +1
  rep; dos sesiones seguidas por debajo del mínimo con el mismo peso → descarga
  del 10 %.

## App de Android (APK)

El módulo [`moiehfih-android/`](../moiehfih-android) empaqueta esta carpeta en un
WebView. GitHub Actions ([`moiehfih-apk.yml`](../.github/workflows/moiehfih-apk.yml))
compila el APK en cada cambio y lo publica en **Releases**:

https://github.com/theramses1/mikasa/releases/latest/download/MoiehFih.apk

Sin configurar nada, cada APK se firma con una llave de depuración distinta, así que
para actualizar hay que desinstalar la versión anterior (y se pierden los datos:
exporta una copia antes). Para que las actualizaciones se instalen encima, crea una
llave y guárdala en los secretos del repositorio (Settings → Secrets → Actions):

```bash
keytool -genkeypair -keystore moiehfih.keystore -alias moiehfih -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 moiehfih.keystore   # valor de MOIEHFIH_KEYSTORE_BASE64
```

Secretos: `MOIEHFIH_KEYSTORE_BASE64`, `MOIEHFIH_KEYSTORE_PASSWORD`,
`MOIEHFIH_KEY_ALIAS` y `MOIEHFIH_KEY_PASSWORD`.

## Uso

```bash
cd moiehfih
python3 -m http.server 8080   # abre http://localhost:8080
npm test                      # pruebas de los cálculos (node --test)
```

Para usarla en el móvil, publica la carpeta `moiehfih/` en cualquier hosting
estático con HTTPS (por ejemplo GitHub Pages), ábrela en el navegador y elige
«Instalar app» o «Añadir a pantalla de inicio».

## Estructura

```
moiehfih/
├── index.html, styles.css, manifest.webmanifest, sw.js, icon.svg
├── js/
│   ├── app.js      # vistas y eventos
│   ├── calc.js     # cálculos puros (tendencia, TDEE, objetivos, progresión)
│   ├── charts.js   # gráficas SVG sin dependencias
│   ├── data.js     # alimentos, ejercicios y rutinas iniciales
│   └── store.js    # localStorage, exportar/importar, datos demo
└── tests/calc.test.mjs
```
