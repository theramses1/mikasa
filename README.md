# Mikasa Stickers

> Este repositorio también incluye **[MoiehFih](moiehfih/README.md)**, una app
> (PWA) de dieta, macros, peso corporal, entrenamientos y bitácora.

App de Android para **crear stickers de WhatsApp** a partir de tus propias
imágenes. Selecciona fotos de la galería, la app las convierte automáticamente
al formato que exige WhatsApp (WebP 512×512 con fondo transparente) y las
organiza en packs que puedes añadir a WhatsApp con un solo toque.

## Características

- Crear varios packs de stickers, cada uno con nombre y autor.
- Añadir stickers eligiendo imágenes de la galería (selector de fotos del
  sistema, sin permisos de almacenamiento).
- Conversión automática a **WebP 512×512**, centrado sobre lienzo transparente
  y comprimido por debajo de 100 KB (requisito de WhatsApp).
- Generación automática del icono del pack (tray 96×96 PNG).
- Asignar emojis a cada sticker y elegir cuál se usa como icono del pack.
- Validación de las reglas de WhatsApp (entre 3 y 30 stickers por pack).
- Botón **"Añadir a WhatsApp"** que usa el intent oficial de WhatsApp.

## Cómo funciona la integración con WhatsApp

WhatsApp no importa archivos: lee los packs a través de un `ContentProvider`.
Esta app expone [`StickerContentProvider`](app/src/main/java/com/mikasa/stickers/StickerContentProvider.kt),
que sirve los metadatos y las imágenes desde el almacenamiento interno mediante
estas URIs:

```
content://<authority>/metadata                        -> todos los packs
content://<authority>/metadata/<identifier>           -> un pack
content://<authority>/stickers/<identifier>           -> stickers de un pack
content://<authority>/stickers_asset/<id>/<archivo>   -> imagen WebP o tray PNG
```

Para añadir un pack se lanza el intent
`com.whatsapp.intent.action.ENABLE_STICKER_PACK` con el `identifier`, el
`authority` del provider y el nombre del pack (ver
[`WhatsAppHelper`](app/src/main/java/com/mikasa/stickers/util/WhatsAppHelper.kt)).

## Estructura del proyecto

```
app/src/main/java/com/mikasa/stickers/
├── StickerContentProvider.kt     # Expone los packs a WhatsApp
├── StickerPackRepository.kt      # Almacenamiento (contents.json + archivos)
├── model/                        # StickerPack, Sticker
├── util/
│   ├── ImageUtils.kt             # Conversión a WebP 512×512 y tray 96×96
│   └── WhatsAppHelper.kt         # Intent de añadir + comprobación whitelist
└── ui/
    ├── MainActivity.kt           # Lista de packs
    ├── StickerPackDetailActivity.kt  # Editar pack / crear stickers
    └── adapters (PackListAdapter, StickerGridAdapter)
```

Los packs se guardan en `filesDir/stickerpacks/`:

```
stickerpacks/
├── contents.json                 # metadatos compatibles con WhatsApp
└── <identifier>/
    ├── tray.png                  # icono 96×96
    └── <uuid>.webp               # stickers 512×512
```

## Compilar

Requisitos: Android Studio (o el SDK de Android con `ANDROID_HOME`
configurado) y JDK 17.

```bash
./gradlew assembleDebug
# APK en app/build/outputs/apk/debug/app-debug.apk
```

- `compileSdk` / `targetSdk`: 34
- `minSdk`: 26 (Android 8.0)
- Lenguaje: Kotlin · UI con Material 3 y ViewBinding

## Requisitos de WhatsApp que la app cumple

| Elemento          | Requisito                        |
|-------------------|----------------------------------|
| Sticker estático  | WebP 512×512, ≤ 100 KB           |
| Icono del pack    | PNG 96×96, ≤ 50 KB               |
| Stickers por pack | entre 3 y 30                     |
| Emojis            | hasta 3 por sticker (opcional)   |
