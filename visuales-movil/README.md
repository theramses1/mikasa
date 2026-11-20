# Visuales Móvil: efectos de cámara que reaccionan a la música (Android)

Versión para celular de [`visuales-concierto`](../visuales-concierto). Usa la **cámara del celular**, escucha la música con el **micrófono** y aplica en tiempo real los **mismos 20 efectos** (en la GPU). Además:

- **Letras en pantalla**: modo banda en vivo (pasas las líneas tú) o música grabada (la app **reconoce la canción** y sincroniza la letra sola), con 5 formas de mostrarlas y 5 efectos de texto.
- **Modo proyección**: solo se ve el video, en el celular o en un proyector/TV conectado.
- **Video con sonido y fotos**, que se guardan en la galería.

## Instalar

**Opción A: descargar el APK ya compilado**
1. Descarga el APK desde el enlace directo: https://github.com/theramses1/mikasa/releases/download/visuales-movil-latest/visuales-movil.apk (también está en la pestaña **Releases**).
2. Ábrelo en el celular (o pásalo desde la computadora) y acepta "instalar apps de origen desconocido".

**Opción B: compilarlo tú**
1. Abre la carpeta `visuales-movil` en Android Studio.
2. Conecta el celular con la depuración USB activada y pulsa ▶ Run.

Requisitos: Android 8.0 o superior, con OpenGL ES 3.0 (casi todos los celulares desde 2016).

## Uso

| Gesto / botón | Acción |
|---|---|
| Deslizar ← → | Efecto siguiente / anterior |
| Carrusel de abajo | Elegir un efecto |
| Un toque | Ocultar o mostrar los controles (para grabar la pantalla limpia) |
| Doble toque o 🔄 | Cambiar entre cámara trasera y frontal |
| ⏺ | Grabar / parar video (MP4 con audio, 1080×1920) |
| 📷 | Foto de lo que se ve en pantalla |
| 🔀 | Cambio automático de efecto al ritmo |
| Medidor de arriba | Tócalo al ritmo para marcar el tempo a mano |
| 🎵 Letras | Setlist, letras, reconocimiento de canciones y sincronización (ver abajo) |
| 📡 Proyectar | Pantalla externa o "solo video" en el celular (ver abajo) |
| ⚙ Ajustes | Intensidad, color, sensibilidad, formato vertical/horizontal, cada cuántos golpes cambia el efecto, destello, espejo y girar imagen |

Fotos y videos se guardan en **Imágenes/Visuales** y **Películas/Visuales**.

## Letras

Pulsa **🎵** para abrir el panel de letras.

### Antes del show: el setlist (con internet)
- **➕ Canción**: escribe artista y título y pulsa 🔎 para traer la letra gratis de [LRCLIB](https://lrclib.net). Para canciones propias, **pégala** en el cuadro. Deja una línea en blanco entre estrofas: durante los instrumentales la pantalla queda limpia.
- **🎵 Desde audio**: elige los audios de las canciones (mp3, m4a, wav…). La app saca su **huella acústica** para poder reconocerlas y busca la letra sola si el archivo se llama "Artista - Título".
- Toca una canción para cargarla. **↑ ↓** cambian el orden y **✎** edita o quita.
- Todo queda guardado en el celular: durante el show no hace falta internet.
- **Buscar letra en internet** muestra cualquier letra al momento, sin añadirla al setlist.

### 🎸 Banda en vivo (predeterminado)
El operador pasa la letra **línea a línea** siguiendo al cantante, en la barra de abajo (teleprompter: línea anterior, actual y siguientes):
- **▶ ◀** o tocar el texto: siguiente / anterior línea. **⏭ ⏮**: siguiente / anterior canción.
- **Botones de volumen** del celular: subir = siguiente línea, bajar = anterior.
- **Clicker de presentaciones** o teclado Bluetooth: `AvPág`/`→`/`Espacio` siguiente, `RePág`/`←` anterior, `Shift`+`AvPág`/`RePág` o `]` `[` cambian de canción.

### 🌐 Reconocer cualquier canción por internet (predeterminado)
Arriba en el panel de letras, en **Reconocer la canción que suena**, con **🌐 Internet** elegido:
1. Pulsa **🎤 Identificar**. La app manda los últimos 10 s del micrófono al servicio **AudD** (como Shazam), que responde artista, título y en qué segundo va la canción.
2. Busca la letra en **LRCLIB** y la proyecta **sincronizada** desde ese punto (si tiene tiempos). No hace falta tener la canción guardada.
3. Con **Automático** activado (modo música grabada), vuelve a identificar cuando la canción debería terminar y cada 60 s, así la letra cambia sola. Si no se oye nada no consulta.

Sin clave hay unas pocas consultas gratis al día. Para más, crea una cuenta en [audd.io](https://audd.io) y pega tu **api_token** en "Clave de AudD". Cada identificación gasta una consulta. Necesita internet, y el audio de la muestra se envía a AudD.

**📁 Mi setlist** usa en cambio el reconocedor propio, sin internet, solo con las canciones añadidas desde audio (ver abajo).

### 💿 Música grabada
- **Reconocimiento en tiempo real** (activado por defecto): la app escucha **continuamente con el micrófono del celular** y analiza el audio cada segundo. Reconoce la canción que suena (de las añadidas **desde audio**) en unos **4 s**, pone la letra en el segundo exacto, la mantiene sincronizada y **cambia de letra sola en 3-5 s** cuando cambia la canción. Arriba en la pantalla se ve qué canción reconoció y en qué minuto va.
- **🎤 Identificar** fuerza un reconocimiento al momento.
- Funciona sin internet y aguanta ruido y eco, pero reconoce **grabaciones** (DJ, pistas, música entre bandas): una banda tocando en vivo suena distinta a la grabación. Con banda en vivo usa el otro modo.
- Para sincronizar a mano: **⏱ Empieza ya**, **▶ ◀** (esa línea empieza ahora) y **±0,25 s**.

### Cómo se ven
5 formas (**Karaoke**, **Gran centro**, **Desplazamiento**, **Palabra a palabra**, **Palabras dispersas**) y 5 efectos de texto (**Neón**, **Cromático**, **Ola**, **Glitch**, **Video en el texto**), más el tamaño. Las letras salen en pantalla, en el proyector y en los videos grabados.

## Modo proyección

Pulsa **📡**:
- **Con un proyector o TV conectado** como pantalla externa (cable USB-C a HDMI, o pantalla inalámbrica si tu celular la ofrece como pantalla extendida): la salida limpia, **solo el video** con efectos y letras, va a esa pantalla a pantalla completa, y el celular se queda con todos los controles. Se activa sola al conectarla.
- **Sin pantalla externa** ("Solo video en este celular"): se ocultan todos los controles y solo queda el video. Sirve para compartir la pantalla (Smart View, Chromecast…). Las teclas de volumen siguen pasando la letra. Para salir, **mantén pulsado** o pulsa **Atrás**.

Para proyectores y TVs elige **Formato de salida → Horizontal** en Ajustes: la app gira a horizontal y graba los videos en 16:9. Si con el celular de lado la imagen sale girada, usa **Girar imagen 90°**.

## Los 20 efectos

Pulso RGB · Caleidoscopio · Bordes Neón · Estela Psicodélica · Glitch / Datamosh · Espejo Cuádruple · Túnel · Semitono · Píxeles · Visión Térmica · Ondas Líquidas · Multiplicador · Pop Art · Onda Expansiva · Vórtice · Rayos de Luz · Lluvia Digital · Solarizado · Cuadro Infinito · Curvas de Nivel.

Cada uno reacciona a los graves, los medios, los agudos y los golpes del bombo, igual que en la versión para conciertos (la tabla con cómo reacciona cada uno está en su [README](../visuales-concierto/README.md#los-20-efectos)).

## Consejos

- El micrófono se usa **sin procesar** cuando el celular lo permite (sin cancelación de eco ni control de ganancia), que es lo mejor para música.
- La **auto-ganancia** ajusta la reacción sola: funciona igual en primera fila que al fondo.
- Si el ritmo no se detecta bien, sube **Sensibilidad al sonido** o marca el tempo tocando el medidor.
- En videos largos el celular se calienta: quítale la funda y baja la intensidad si se pone lento.

## Cómo está hecho

| Archivo | Qué hace |
|---|---|
| `Shaders.kt` | Los 20 efectos en GLSL ES 3.0, copiados de `visuales-concierto/js/shaders.js`, más el shader que endereza la cámara. |
| `VisualRenderer.kt` | Motor OpenGL: cámara (textura externa) → efecto con feedback → pantalla y codificador de video. |
| `AudioEngine.kt` | Micrófono (AudioRecord) + FFT de 2048 + bandas, auto-ganancia, golpes y BPM (el mismo algoritmo que `audio.js`). |
| `VideoRecorder.kt` | Grabación MP4 (H.264 + AAC) con MediaCodec y MediaMuxer. |
| `Fingerprint.kt` | Reconocedor de canciones por huella acústica (el mismo de `fingerprint.js`) y remuestreo a 11 025 Hz. |
| `SongLibrary.kt` | El setlist guardado en el celular: letras, huellas y decodificación de los audios. |
| `Lrclib.kt` | Búsqueda de letras en LRCLIB. |
| `LyricsEngine.kt` | Sincronización de la letra y las 5 formas de dibujarla (los 5 efectos de texto están en `Shaders.kt`). |
| `LyricsController.kt` | Panel de letras, teleprompter, reconocimiento y teclas. |
| `ProjectorPresentation.kt` | La ventana de la pantalla externa (solo video). |
| `MainActivity.kt` | Interfaz, permisos, CameraX, gestos y modo proyección. |
| `MediaSaver.kt` | Guarda en la galería. |
