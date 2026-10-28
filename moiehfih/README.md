# MoiehFih

App **Android nativa** (Kotlin) para registrar **dieta, macros, peso corporal,
entrenamientos y bitácora diaria**, inspirada en MacroFactor: estima tu gasto
energético real a partir de tus datos y ajusta tus calorías y macros cada
semana, y te dice cuánto peso y cuántas repeticiones hacer en cada ejercicio.

**Descargar:** https://github.com/theramses1/mikasa/releases/latest/download/MoiehFih.apk

Funciona sin internet y sin cuenta: todo se guarda en el teléfono (SQLite).

> Google Fit fue reemplazado por Health Connect: activa en Google Fit (o en la app de tu reloj)
> la sincronización con Health Connect y MoiehFih importará esos datos.

## Funciones

| Pestaña | Qué hace |
| --- | --- |
| **Inicio** | Calorías, macros y **azúcares** de hoy frente a tu objetivo, **agua**, gasto energético estimado, peso de tendencia, actividad y signos vitales del reloj, plan actual y **check-in semanal** que ajusta tus objetivos. |
| **Dieta** | Registro por comida con buscador de ~40 alimentos comunes y recientes (sólidos en g, **líquidos en ml**, con azúcares), alimentos propios (por 100 g/ml o por porción), entrada rápida, copiar el día anterior, **registro de agua** y promedios de 7 días. |
| **Entreno** | **Pesas:** rutinas, entreno en vivo (kg, reps, RPE y ✓ por serie) con **temporizador de descanso** que vibra, **sugerencia de carga** por ejercicio, historial, 1RM estimado, récords y series semanales por grupo muscular. **Cardio:** sesiones con duración, distancia, pulso y calorías (estimadas por MET si no las indicas), resumen semanal. |
| **Salud y relojes** | Conexión con **Health Connect** (Android 14+): importa pasos, calorías activas, distancia, frecuencia cardiaca (media, mínima, máxima y en reposo), sueño, SpO₂, presión arterial, peso y sesiones de cardio de Google Fit, Fitbit, Samsung Health, Garmin, Wear OS, etc. |
| **Peso** | Pesadas con fecha, peso de tendencia, cambio a 7/30 días, gráfica de peso y gráfica de gasto energético frente a ingesta. |
| **Bitácora** | Sueño, pasos, calidad del sueño, energía, ánimo, hambre, estrés y notas, junto al resumen automático del día. |
| **Ajustes** (⚙) | Perfil y objetivo, modo Coach o Manual, meta de agua, segundos de descanso, Health Connect, exportar/importar copia de seguridad (JSON) y borrar datos. |

Modo claro y oscuro según el sistema; el botón «atrás» de Android navega entre pantallas.

## Algoritmos

- **Peso de tendencia:** media móvil exponencial (α = 0.1) de las pesadas; los
  días sin pesada se interpolan.
- **Gasto energético (TDEE):** empieza con Mifflin-St Jeor × actividad y se
  corrige cada día con `ingesta media − Δ tendencia × 7700 / días` en una
  ventana de 21 días (mínimo 7 días registrados).
- **Objetivos:** `kcal = TDEE + % semanal × peso × 7700 / 7`; proteína en g/kg;
  grasa como % de las kcal (mínimo 0.6 g/kg); el resto, carbohidratos.
- **Progresión (escalamiento):** doble progresión. Cada ejercicio tiene un rango
  de reps y un incremento: si completas el máximo en todas las series sube el
  peso; si no, +1 rep; dos sesiones seguidas por debajo del mínimo con el mismo
  peso → descarga del 10 %.

## Código

```
moiehfih/
├── build.gradle            # módulo Android (sin dependencias de AndroidX)
├── build-apk.sh            # compila el APK sin Gradle (solo herramientas del SDK)
├── tools/emulator-smoke.sh # prueba en emulador con capturas (CI)
└── src/
    ├── main/java/com/moiehfih/app/
    │   ├── data/   # modelos, base de datos SQLite (AppData), datos iniciales y demo
    │   ├── logic/  # cálculos puros: tendencia, TDEE, objetivos, progresión
    │   └── ui/     # actividad, pantallas, componentes y gráficas (Canvas)
    └── test/       # pruebas de la lógica (JUnit) y de las pantallas (Robolectric)
```

```bash
./gradlew :moiehfih:testDebugUnitTest   # pruebas
./gradlew :moiehfih:assembleDebug       # APK de depuración
```

GitHub Actions ([`moiehfih-apk.yml`](../.github/workflows/moiehfih-apk.yml))
corre las pruebas, abre la app en un emulador de Android 14 tomando capturas de
cada pantalla y, si todo pasa, publica el APK en **Releases**.

### Firma y actualizaciones

Sin configurar nada, cada APK se firma con una llave de depuración distinta, así
que para actualizar hay que desinstalar la versión anterior (exporta una copia de
seguridad antes). Para que las actualizaciones se instalen encima, crea una llave
y guárdala en los secretos del repositorio (Settings → Secrets → Actions):

```bash
keytool -genkeypair -keystore moiehfih.keystore -alias moiehfih -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 moiehfih.keystore   # valor de MOIEHFIH_KEYSTORE_BASE64
```

Secretos: `MOIEHFIH_KEYSTORE_BASE64`, `MOIEHFIH_KEYSTORE_PASSWORD`,
`MOIEHFIH_KEY_ALIAS` y `MOIEHFIH_KEY_PASSWORD`.
