# Visuales en Vivo: visuales para conciertos que reaccionan al audio

Toma el video en vivo de una **DJI Osmo Action 5 Pro** o de un **smartphone**, le aplica efectos que reaccionan a la música en tiempo real (en la GPU) y manda el resultado al **proyector**.

No hay que instalar nada: es una página web local que se abre en **Google Chrome o Microsoft Edge** (Windows, macOS o Linux).

## Arranque rápido

1. Conecta la cámara (ver abajo), el audio y el proyector como **pantalla extendida** (no duplicada).
2. Abre `index.html` con doble clic en Chrome/Edge (o ejecuta `iniciar.bat` en Windows o `./iniciar.sh` en Mac/Linux).
3. Pulsa **Iniciar** y acepta los permisos de cámara y micrófono.
4. Elige la cámara y la entrada de audio en los desplegables.
5. Pulsa **Abrir ventana del proyector**, arrástrala al proyector y haz **doble clic** para ponerla a pantalla completa.
6. Deja la ventana de control abierta en el portátil (sin minimizar) y controla el show desde ahí.

> Si Chrome bloquea la cámara al abrir el archivo directamente, usa `iniciar.bat` / `iniciar.sh`: levantan un servidor local en `http://localhost:8080`.

## Conectar la cámara

### DJI Osmo Action 5 Pro (modo webcam, por USB)
1. Conecta la cámara al ordenador con el cable USB-C.
2. En la pantalla de la cámara aparece el modo de conexión USB: elige **Webcam**.
3. En el programa aparecerá como "DJI Osmo Action 5 Pro" o "USB Video Device".

Consejos:
- La resolución en modo webcam la fija la cámara (puede ser 1080p o 720p). El programa pide 1080p y usa lo que reciba. La resolución real aparece debajo del botón Iniciar.
- La cámara puede calentarse en sesiones largas. Ponla en un sitio ventilado, sin la batería si tu montaje lo permite, y prueba antes el tiempo total del show.
- Para distancias de más de 3 m usa un **cable USB alargador activo** (con repetidor). Un cable pasivo largo suele cortar la señal.
- Opción inalámbrica: emite por RTMP desde la app DJI Mimo a OBS en el portátil y usa la **Cámara virtual de OBS** como entrada aquí. Añade algo de latencia.

### Smartphone
- **iPhone + Mac**: Continuity Camera. El iPhone aparece como cámara automáticamente.
- **Android o iPhone en Windows**: apps tipo webcam como DroidCam, Iriun o Camo. Por USB tienen menos retardo que por WiFi.
- **Cualquier cámara con salida HDMI**: una capturadora HDMI→USB (Elgato Cam Link o una genérica) aparece como webcam.

## Conectar el audio

En "Audio" elige la entrada:
- **Lo mejor**: una salida de la **consola de mezcla** (aux, rec o matrix) a una **interfaz de audio USB**. Así la señal es limpia y no le afecta el ruido del público.
- **Rápido**: el micrófono del portátil o de la cámara. Funciona gracias a la auto-ganancia, pero reacciona también al público.
- **Para ensayar**: "Probar con archivo de audio" reproduce una canción en bucle.

El programa desactiva la cancelación de eco, la supresión de ruido y el control de ganancia automático del navegador, porque están pensados para voz y estropean la música.

## Los 5 efectos

| # | Efecto | Reacción al sonido |
|---|--------|--------------------|
| 1 | **Pulso RGB** | Zoom con el bombo, separación de colores con los graves, tinte con los medios y destello en cada golpe. |
| 2 | **Caleidoscopio** | Espejos radiales. Los medios lo hacen girar y los graves "respiran" el centro. La intensidad cambia el número de espejos. |
| 3 | **Bordes Neón** | Solo las siluetas, en neón con estela. Los agudos encienden las líneas y el color recorre la imagen. |
| 4 | **Estela Psicodélica** | Feedback de video: cada movimiento deja rastros que giran, hacen zoom y cambian de color con la música. |
| 5 | **Glitch / Datamosh** | Cortes horizontales, bloques congelados, RGB desfasado y posterizado en cada golpe. |

## Controles y atajos de teclado

| Tecla | Acción |
|-------|--------|
| `1`–`5` | Cambiar de efecto |
| `←` `→` | Efecto anterior o siguiente |
| `↑` `↓` | Subir o bajar la intensidad |
| `A` | Cambio automático de efecto cada N golpes (4/8/16/32) |
| `S` | Strobe: destello blanco en cada golpe |
| `M` | Espejo horizontal |
| `B` | Blackout con fundido a negro |
| `T` | Golpe manual (marca el ritmo a mano si el audio falla) |
| `F` | Pantalla completa (también funciona en la ventana del proyector) |
| `H` | Ocultar o mostrar el panel |

- **Sensibilidad**: súbela si los visuales reaccionan poco y bájala si "saltan" todo el rato.
- **Color base**: cambia la paleta de los efectos.
- Los medidores de graves, medios y agudos, el punto de golpe y el BPM estimado sirven para comprobar que el audio llega.

## Cómo funciona

```
Cámara (USB/webcam) ──► <video> ──► textura GPU ─┐
                                                 ├─► shader del efecto ──► framebuffer (feedback) ──► canvas ──► ventana proyector
Audio (interfaz/mic) ──► Web Audio FFT ──► graves/medios/agudos + detección de golpes ─┘
```

- `js/audio.js`: FFT de 2048 puntos. Divide el sonido en graves (20–150 Hz), medios (150 Hz–2 kHz) y agudos (2–10 kHz). Cada banda se normaliza automáticamente según sus mínimos y máximos recientes. Los golpes se detectan por subidas bruscas de graves, y el BPM sale de la mediana entre golpes.
- `js/shaders.js`: los efectos en GLSL. Cada efecto es una función `vec3 effect()`. Recibe la cámara (`uCam`), el frame anterior (`uPrev`) y las bandas de audio.
- `js/renderer.js`: WebGL2 con dos framebuffers alternos para las estelas.
- `js/main.js`: la interfaz, los atajos y la ventana del proyector (recibe el canvas mediante `captureStream`).

### Añadir un efecto nuevo
Añade un objeto al array `EFFECTS` en `js/shaders.js` con `name`, `desc` y `frag` (que defina `vec3 effect()`). Aparecerá solo en el panel con la siguiente tecla numérica.

## Problemas comunes

- **No aparece la cámara**: cierra otras apps que la usen (Zoom, OBS, la app de DJI) y pulsa Iniciar otra vez.
- **El proyector se ve negro o congelado**: no minimices la ventana de control, porque el navegador pausa el render.
- **Va lento**: baja la "Resolución de salida" a 1280×720. Comprueba también que Chrome usa la GPU: en `chrome://gpu`, "WebGL2" tiene que salir como *Hardware accelerated*.
- **No reacciona a la música**: mira los medidores. Si no se mueven, revisa la entrada de audio. Si se mueven poco, sube la Sensibilidad.
