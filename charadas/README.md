# Charadas

Juego de charadas para Android: una persona se pone el teléfono en la frente y
los demás le dan pistas para que adivine la palabra.

- **51 categorías y más de 7,000 palabras**, todas gratis (películas, comida mexicana,
  telenovelas, futbolistas, mímica, refranes, anime, infancia de los 90…).
- **Crea tus propias categorías** con el botón ➕ (mantén presionada una tuya para editarla o borrarla).
- **Inclinar**: pantalla hacia el piso = correcto, hacia el techo = paso.
- **Tocar**: lado derecho = correcto, izquierdo = paso (para teléfonos sin sensor o para jugar en la mesa).
- Rondas de 30, 45, 60, 90 o 120 segundos, sonido y vibración, resumen al final.
- Ninguna palabra se repite en 4 juegos seguidos de la misma categoría (se recuerda aunque cierres la app).

Para añadir palabras edita `src/main/assets/categorias.txt` o `categorias_2.txt` (las categorías con el mismo nombre se combinan).

## Compilar

GitHub Actions compila el APK en cada push (`.github/workflows/charadas-apk.yml`)
y lo publica en *Releases → charadas-latest*. En local:

```bash
./gradlew :charadas:assembleRelease
# charadas/build/outputs/apk/release/charadas-release.apk
```
