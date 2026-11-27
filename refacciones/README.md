# Mis Refacciones

Visor para Android del sitio de AutoZone México (www.autozone.com.mx) que
recuerda tus preferencias. App personal, no oficial; no tiene relación con AutoZone.

## Qué se guarda

| Qué | Dónde |
|-----|-------|
| Sesión, carrito y vehículo elegido **dentro del sitio** | Cookies y localStorage del WebView (persisten al cerrar la app) |
| **Mis vehículos** (año, marca, modelo, motor, apodo) y vehículo activo | SharedPreferences de la app |
| Favoritos, búsquedas recientes, última página, modo escritorio | SharedPreferences de la app |

## Funciones

- Barra superior con el vehículo activo; tócala para cambiarlo o agregar otro.
- **Buscar refacción**: agrega el vehículo activo a la búsqueda (ej. "balatas 2015 Nissan Versa").
- Favoritos, compartir página, deslizar para recargar, botón atrás del sistema.
- Enlaces `tel:`, `mailto:`, WhatsApp y mapas se abren en su app.
- Ubicación para "tiendas cercanas" (pide permiso solo cuando el sitio lo necesita).
- Preferencias: abrir en la última página, versión escritorio, URL de búsqueda
  editable, borrar datos del sitio.

## Compilar

```bash
./gradlew :refacciones:assembleDebug
# APK: refacciones/build/outputs/apk/debug/refacciones-debug.apk
```
