# Visuales Móvil: efectos de cámara que reaccionan a la música (Android)

Versión para celular de [`visuales-concierto`](../visuales-concierto). Usa la **cámara del celular**, escucha la música con el **micrófono** y aplica en tiempo real los **mismos 20 efectos** (en la GPU). Puedes **grabar video con sonido** o **sacar fotos**, que se guardan en la galería.

## Instalar

**Opción A: descargar el APK ya compilado**
1. Descarga el APK desde el enlace directo: https://github.com/theramses1/mikasa/releases/download/visuales-movil-latest/visuales-movil.apk (también está en la pestaña **Releases**).
2. Ábrelo en el celular (o pásalo desde la computadora), ábrelo y acepta "instalar apps de origen desconocido".

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
| ⚙ Ajustes | Intensidad, color, sensibilidad al sonido, cada cuántos golpes cambia el efecto, destello en los golpes y espejo |

Fotos y videos se guardan en **Imágenes/Visuales** y **Películas/Visuales**.

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
| `MainActivity.kt` | Interfaz, permisos, CameraX y gestos. |
| `MediaSaver.kt` | Guarda en la galería. |
