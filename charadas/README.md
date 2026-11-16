# Charadas

Juego de charadas para Android: una persona se pone el teléfono en la frente y
los demás le dan pistas para que adivine la palabra.

- **51 categorías y ~2,900 palabras**, todas gratis (películas, comida mexicana,
  telenovelas, futbolistas, mímica, refranes, anime, infancia de los 90…).
- **Crea tus propias categorías** con el botón ➕ (mantén presionada una tuya para editarla o borrarla).
- **Inclinar**: pantalla hacia el piso = correcto, hacia el techo = paso.
- **Tocar**: lado derecho = correcto, izquierdo = paso (para teléfonos sin sensor o para jugar en la mesa).
- Rondas de 30, 45, 60, 90 o 120 segundos, sonido y vibración, resumen al final.
- No repite palabras hasta agotar la categoría.

Para añadir palabras edita `src/main/assets/categorias.txt`.

## Compilar

GitHub Actions compila el APK en cada push (`.github/workflows/charadas-apk.yml`)
y lo publica en *Releases → charadas-latest*. En local:

```bash
./gradlew :charadas:assembleRelease
# charadas/build/outputs/apk/release/charadas-release.apk
```
