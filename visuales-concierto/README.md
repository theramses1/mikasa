# Visuales en Vivo: visuales para conciertos que reaccionan al audio

Toma video en vivo de **hasta 3 cámaras USB a la vez** (DJI Osmo Action 5 Pro en modo webcam, smartphones como webcam o cualquier cámara USB). Le aplica **20 efectos** que reaccionan a la música en tiempo real (en la GPU) y manda el resultado al **proyector**. También puede **identificar la canción y proyectar su letra sincronizada**.

No hay que instalar nada: es una página web local que se abre en **Google Chrome o Microsoft Edge** (Windows, macOS o Linux).

## Arranque rápido

1. Descarga y descomprime la carpeta `visuales-concierto`.
2. Abre `index.html` con Chrome o Edge (clic derecho → Abrir con). Si Chrome bloquea la cámara, usa `iniciar.bat` (Windows) o `iniciar.sh` (Mac/Linux).
3. Sin conectar nada ya funciona en **modo simulador**: 3 cámaras de prueba animadas. Carga una canción en "Simulador: probar con una canción" y pausa o reanuda con el botón **⏸ Pausa** o la tecla `P`.
4. Conecta las cámaras y el proyector como **pantalla extendida** (no duplicada) y pulsa **Iniciar cámaras y audio**. Acepta los permisos.
5. Las cámaras detectadas se asignan solas a las ranuras 1, 2 y 3. Puedes cambiarlas en los desplegables.
6. Pulsa **Abrir ventana del proyector**, arrástrala al proyector y haz **doble clic** para ponerla a pantalla completa. Deja la ventana de control abierta (sin minimizar).

## Cámaras

### Conectar hasta 3 cámaras USB
- Cada ranura tiene un indicador: 🟢 en vivo, 🟡 simulada, 🔴 error. Pasa el ratón por encima para ver la resolución o el error.
- **Ancho de banda USB**: varias cámaras en el mismo puerto o hub pueden fallar ("no se pudo abrir"). Para evitarlo:
  - Usa **720p** por cámara (es la opción predeterminada) cuando conectes 2 o 3.
  - Conecta cada cámara a un **puerto distinto del ordenador**, mejor si es uno en cada lado, porque suelen ser controladores distintos.
  - Si usas un hub, que sea USB 3 con alimentación propia.
- Para cables de más de 3 m usa **alargadores USB activos**.

### DJI Osmo Action 5 Pro (modo webcam)
1. Conecta la cámara con el cable USB-C.
2. En la pantalla de la cámara elige **Webcam** como modo de conexión USB.
3. Aparece como "DJI Osmo Action 5 Pro" o "USB Video Device".

En sesiones largas la cámara puede calentarse: ventílala y haz una prueba con la duración del show.

### Smartphone
- **iPhone + Mac**: Continuity Camera.
- **Android o iPhone en Windows**: apps tipo webcam (DroidCam, Iriun, Camo). Por USB tienen menos retardo que por WiFi.
- **Cámara con HDMI**: con una capturadora HDMI→USB aparece como webcam.

### Mezclar las cámaras en vivo
- **Cámara al aire**: botones Cám 1, 2 y 3, o teclas `Z` `X` `C`. La que está al aire se marca en rojo.
- **Composición** (tecla `V` para rotar):
  - **Una cámara**: pantalla completa, con fundido al cambiar (se puede desactivar para cortes secos).
  - **Pantalla dividida**: todas las cámaras activas en columnas.
  - **Imagen en imagen**: la cámara al aire grande y las otras en recuadros.
  - **Superposición**: todas fundidas una sobre otra.
- **Cambiar de cámara cada N golpes** (tecla `N`): pasa de una cámara a otra al ritmo de la música.
- Los efectos se aplican **sobre la composición**, así que los 20 funcionan con cualquier combinación de cámaras.

## Audio

En "Micrófono" elige la fuente:
- 🎥 **Micrófono de una cámara**: el micrófono de una cámara USB conectada. El programa lo reconoce porque pertenece al mismo dispositivo que la cámara.
- 💻 **Micrófono de la laptop** o cualquier otra entrada, como una interfaz de audio conectada a la consola. Es la opción con la señal más limpia.
- **Simulador**: carga una canción de prueba. Se reproduce en bucle y se pausa con **⏸ Pausa** o con `P`.

La auto-ganancia adapta la reacción tanto a un micrófono lejano como a una señal de línea. Además, el programa desactiva el procesado de voz del navegador (cancelación de eco, supresión de ruido y control de ganancia automático), que estropea la música.

## Los 20 efectos

| Tecla | Efecto | Reacción al sonido |
|---|---|---|
| `1` | **Pulso RGB** | Zoom con el bombo, separación de colores con los graves y destello en cada golpe. |
| `2` | **Caleidoscopio** | Espejos radiales que giran con los medios. Los graves "respiran" el centro. |
| `3` | **Bordes Neón** | Siluetas en neón con estela. Los agudos encienden las líneas. |
| `4` | **Estela Psicodélica** | Feedback de video: los movimientos dejan rastros que giran y cambian de color. |
| `5` | **Glitch / Datamosh** | Cortes, bloques congelados, RGB desfasado y posterizado en cada golpe. |
| `6` | **Espejo Cuádruple** | Cuatro reflejos que giran. Los graves acercan el centro. |
| `7` | **Túnel** | La imagen envuelta en un túnel infinito que acelera con la música. |
| `8` | **Semitono** | Puntos de imprenta que laten con los graves. |
| `9` | **Píxeles** | Mosaico retro. Los píxeles crecen en cada golpe. |
| `0` | **Visión Térmica** | Paleta de cámara térmica. El volumen "calienta" la imagen. |
| `⇧1` | **Ondas Líquidas** | La imagen ondula como agua. Los graves agrandan las olas. |
| `⇧2` | **Multiplicador** | Cuadrícula de copias de colores. Una se ilumina al ritmo. |
| `⇧3` | **Pop Art** | Cuatro copias en serigrafía de colores planos. |
| `⇧4` | **Onda Expansiva** | Cada golpe lanza un anillo que deforma la imagen. |
| `⇧5` | **Vórtice** | Remolino que retuerce el centro con los graves. |
| `⇧6` | **Rayos de Luz** | Las zonas brillantes (focos, instrumentos) lanzan rayos que crecen con el volumen. |
| `⇧7` | **Lluvia Digital** | La imagen dibujada con columnas de símbolos que caen. |
| `⇧8` | **Solarizado** | Inversión de tonos con un umbral móvil. Negativo en los golpes fuertes. |
| `⇧9` | **Cuadro Infinito** | Efecto Droste: la imagen dentro de sí misma, una y otra vez. |
| `⇧0` | **Curvas de Nivel** | Mapa topográfico de líneas de colores que fluyen. |

`⇧` = mantener Shift. También puedes hacer clic en el panel o pasar de efecto con `←` `→`.

## Letras de la canción en la proyección

La app puede **identificar la canción que está sonando**, **buscar su letra** y **mostrarla sincronizada** encima de los visuales.

### Identificar la canción automáticamente
1. Consigue una clave (api_token) en **audd.io**, el servicio que reconoce la música. Tiene prueba gratis. Pégala en "Clave de AudD": se guarda en este navegador.
2. Con el micrófono funcionando, pulsa **🎤 Identificar canción** (tecla `I`). La app escucha 10 segundos, identifica la canción y busca la letra en **LRCLIB**, un archivo gratuito de letras.
3. Si la letra tiene tiempos, sale **sincronizada**: el servicio indica en qué segundo de la canción va, y la app sigue desde ahí.
4. "Volver a identificar cada 60 s" sirve para que la letra cambie sola entre canciones. Cada intento gasta una consulta de tu clave.

### Buscar la letra a mano (sin clave)
Escribe "Artista canción" y pulsa **Buscar**. Si hay varias versiones, elige otra en la lista; las marcadas con ✓ tienen tiempos.
- Con la **canción de prueba** del simulador, la letra se sincroniza sola con el archivo y se detiene al pausar.
- En **vivo**, pulsa **⏱ Empieza ya** cuando arranque la canción.

### Sincronizar
| Control | Qué hace |
|---|---|
| `K` / `J` | La línea siguiente / anterior empieza **ahora** (corrige el desfase al instante). En letras sin tiempos, pasa de línea. |
| `.` / `,` | Adelanta o atrasa la letra 0,25 s |
| ⏱ Empieza ya | La canción empieza en este momento |

En vivo la banda puede ir a otro tempo que la grabación: lo normal es ir corrigiendo con `K` en cada estrofa.

### 5 formas de mostrar la letra (tecla `Y`)
1. **Karaoke**: la línea se va pintando a medida que se canta y la siguiente aparece debajo.
2. **Gran centro**: una línea enorme en el centro que aparece, late con el ritmo y desaparece.
3. **Desplazamiento**: varias líneas como un teleprompter, con la actual resaltada.
4. **Palabra a palabra**: cada palabra sola y gigante, con la línea completa abajo como guía.
5. **Palabras dispersas**: las palabras saltan a posiciones y ángulos al azar por la pantalla.

### 5 efectos de texto (tecla `U`)
1. **Neón**: halo de color que respira con los graves.
2. **Cromático**: los colores rojo, verde y azul se separan al ritmo.
3. **Ola**: el texto ondula y cambia de color.
4. **Glitch**: franjas que se desplazan en cada golpe.
5. **Video en el texto**: las letras dejan ver las cámaras a través de ellas.

Además, todas las letras llevan una sombra suave para que se lean sobre cualquier imagen. El tamaño se ajusta con el deslizador y `L` las muestra u oculta.

## Todos los atajos de teclado

| Tecla | Acción |
|---|---|
| `1`–`0`, `Shift`+`1`–`0` | Elegir efecto (1–20) |
| `←` `→` | Efecto anterior o siguiente |
| `↑` `↓` | Subir o bajar la intensidad |
| `Z` `X` `C` | Poner al aire la cámara 1, 2 o 3 |
| `V` | Cambiar la composición de cámaras |
| `N` | Cambio automático de cámara con el ritmo |
| `A` | Cambio automático de efecto con el ritmo |
| `P` | Pausar o reanudar la canción de prueba |
| `I` | Identificar la canción y buscar la letra |
| `L` | Mostrar u ocultar las letras |
| `Y` / `U` | Cambiar la forma / el efecto de las letras |
| `J` / `K` | Línea anterior / siguiente (sincronizar) |
| `,` / `.` | Atrasar / adelantar la letra 0,25 s |
| `S` | Strobe: destello en cada golpe |
| `M` | Espejo horizontal |
| `B` | Blackout con fundido a negro |
| `T` | Golpe manual (marcar el ritmo a mano) |
| `F` | Pantalla completa |
| `H` | Ocultar o mostrar el panel |

Los atajos también funcionan con la ventana del proyector seleccionada.

## Cómo funciona

```
Cám 1 ─┐
Cám 2 ─┼─► mezclador (una / dividida / PiP / superposición) ─► efecto (con feedback) ─► blackout/strobe ─► proyector
Cám 3 ─┘                                                          ▲
Audio ─► FFT ─► graves / medios / agudos + detección de golpes ───┘
```

- `js/audio.js`: análisis de audio. Divide el sonido en graves (20–150 Hz), medios (150 Hz–2 kHz) y agudos (2–10 kHz) con auto-ganancia, detecta los golpes y estima el BPM. También reproduce y pausa la canción de prueba.
- `js/shaders.js`: el mezclador de cámaras y los 20 efectos en GLSL.
- `js/renderer.js`: WebGL2. Usa 3 texturas de cámara, un pase de mezcla y dos framebuffers alternos para las estelas.
- `js/lyrics.js`: identificación (AudD), búsqueda de letras (LRCLIB), sincronización y las 5 formas de mostrar el texto. Los 5 efectos de texto están en el pase final de `js/shaders.js`.
- `js/main.js`: la interfaz, las cámaras, los atajos y la ventana del proyector.

### Añadir un efecto nuevo
Añade un objeto al array `EFFECTS` en `js/shaders.js` con `name`, `desc` y `frag` (que defina `vec3 effect()`). Usa `cam(uv)` para leer la imagen de las cámaras, `uPrev` para el frame anterior y `uBass`, `uMid`, `uHigh`, `uBeat` para el audio.

## Problemas comunes

- **"No se pudo conectar" al identificar o buscar letras**: estas funciones necesitan internet. La identificación también necesita una clave de AudD válida y con consultas disponibles.
- **No reconoce la canción**: acerca el micrófono al sonido o usa una entrada de la consola. Las versiones en vivo muy distintas a la grabación cuestan más; búscala a mano.
- **La letra va adelantada o atrasada**: corrígela con `K`/`J` o con `,`/`.`.

- **Una cámara sale en rojo**: ciérrala en otras apps (Zoom, OBS, DJI Mimo), baja a 720p o cámbiala de puerto USB, y pulsa "Reconectar".
- **El proyector se ve negro o congelado**: no minimices la ventana de control.
- **Va lento**: baja la "Resolución de salida" a 1280×720 o usa menos cámaras. En `chrome://gpu`, "WebGL2" tiene que salir como *Hardware accelerated*.
- **No reacciona a la música**: mira los medidores. Si no se mueven, revisa el micrófono elegido. Si se mueven poco, sube la Sensibilidad.
