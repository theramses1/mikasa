# CasaOS Docker (Android)

App Android ligera para acceder directamente a los contenedores Docker de tu servidor CasaOS.

**Descarga:** [`CasaOS-Docker.apk`](CasaOS-Docker.apk) (Android 6.0+, ~30 KB)

## Qué hace
- Lista todas las apps Docker de CasaOS con su estado (en ejecución / detenido), icono e imagen.
- **Iniciar / Detener / Reiniciar** cada contenedor.
- **Abrir** la interfaz web de cada contenedor (IP:puerto) dentro de la app.
- **Panel CasaOS**: abre la web completa de CasaOS en un navegador integrado (sirve también de plan B si la API no responde).
- Soporta HTTP en la red local y pregunta antes de aceptar certificados HTTPS autofirmados.

## Uso
1. Instala el APK (permite "instalar apps de orígenes desconocidos").
2. Escribe la IP de tu CasaOS (ej. `192.168.1.50` o `192.168.1.50:8080`), tu usuario y contraseña de CasaOS.
3. Listo: verás tus contenedores. Menú ⋮ → *Ajustes* para cambiar de servidor.

API usada (CasaOS 0.4.x): `POST /v1/users/login`, `GET /v2/app_management/web/appgrid`,
`PUT /v2/app_management/compose/{id}/status`.

## Compilar
Sin Gradle, con las herramientas de Android de Debian/Ubuntu:
```bash
sudo apt-get install android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner
./build.sh   # -> build/CasaOS-Docker.apk
```
