#!/usr/bin/env bash
# Compila el APK sin Gradle, solo con las herramientas del SDK de Android.
# Útil donde no hay acceso a Google Maven (el plugin de Android para Gradle).
# Con Android Studio basta con `./gradlew :moiehfih:assembleDebug`.
#
# Variables de entorno opcionales:
#   ANDROID_JAR   android.jar de la API 34      (def: $ANDROID_HOME/platforms/android-34/android.jar)
#   BUILD_TOOLS   carpeta con aapt2/zipalign/apksigner, o vacío para usar los del PATH
#   KOTLIN_HOME   compilador de Kotlin 2.x      (def: /opt/kotlinc)
#   DX            d8 o dalvik-exchange          (def: d8 si existe, si no dalvik-exchange)
#   KEYSTORE      keystore de firma             (def: ~/.android/debug.keystore, se crea si falta)
#   DEBUGGABLE    1 para marcar el APK como depurable (habilita los extras de prueba)
#
# Salida: moiehfih/build/MoiehFih.apk
set -euo pipefail

cd "$(dirname "$0")"
ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_HOME/platforms/android-34/android.jar}"
BUILD_TOOLS="${BUILD_TOOLS-$ANDROID_HOME/build-tools/34.0.0}"
KOTLIN_HOME="${KOTLIN_HOME:-/opt/kotlinc}"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
tool() { if [[ -n "$BUILD_TOOLS" && -x "$BUILD_TOOLS/$1" ]]; then echo "$BUILD_TOOLS/$1"; else echo "$1"; fi; }
if [[ -z "${DX:-}" ]]; then
    if command -v "$(tool d8)" >/dev/null 2>&1; then DX="$(tool d8)"; else DX="dalvik-exchange"; fi
fi

PACKAGE=com.moiehfih.app
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0}"
OUT=build
SRC=src/main

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> Recursos (aapt2)"
"$(tool aapt2)" compile --dir "$SRC/res" -o "$OUT/res.zip"
# El manifest no declara `package` (lo pone Gradle con `namespace`); se añade aquí.
sed "s|<manifest |<manifest package=\"$PACKAGE\" |" "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
if [[ "${DEBUGGABLE:-0}" == 1 ]]; then
    sed -i 's|<application|<application android:debuggable="true"|' "$OUT/AndroidManifest.xml"
fi
"$(tool aapt2)" link \
    -I "$ANDROID_JAR" \
    --manifest "$OUT/AndroidManifest.xml" \
    --min-sdk-version 26 --target-sdk-version 34 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    --java "$OUT/gen" \
    -o "$OUT/unsigned.apk" \
    "$OUT/res.zip"

echo "==> Compilando R.java y Kotlin"
javac -nowarn --release 8 -cp "$ANDROID_JAR" -d "$OUT/classes" $(find "$OUT/gen" -name '*.java') 2>&1 \
    | grep -v -e '^Picked up' -e 'bootstrap classpath' -e '^1 warning' || true
"$KOTLIN_HOME/bin/kotlinc" \
    -jvm-target 1.8 \
    -Xlambdas=class -Xsam-conversions=class \
    -no-reflect \
    -cp "$ANDROID_JAR:$OUT/classes" \
    -d "$OUT/classes" \
    $(find "$SRC/java" -name '*.kt') 2>&1 | grep -v '^Picked up' || true
test -f "$OUT/classes/com/moiehfih/app/ui/MainActivity.class"

echo "==> Dex"
cp "$KOTLIN_HOME/lib/kotlin-stdlib.jar" "$OUT/kotlin-stdlib.jar"
zip -q -d "$OUT/kotlin-stdlib.jar" 'META-INF/versions/*' || true
if [[ "$(basename "$DX")" == d8* ]]; then
    "$DX" --min-api 26 --release --lib "$ANDROID_JAR" --output "$OUT/dex" \
        $(find "$OUT/classes" -name '*.class') "$OUT/kotlin-stdlib.jar"
else
    "$DX" --dex --min-sdk-version=26 --output="$OUT/dex/classes.dex" "$OUT/classes" "$OUT/kotlin-stdlib.jar" 2>&1 \
        | grep -v '^Picked up' || true
fi
test -f "$OUT/dex/classes.dex"
(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes*.dex)

echo "==> Alineando y firmando"
"$(tool zipalign)" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [[ ! -f "$KEYSTORE" ]]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" 2>&1 | grep -v '^Picked up' || true
fi
"$(tool apksigner)" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --out "$OUT/MoiehFih.apk" "$OUT/aligned.apk"
"$(tool apksigner)" verify "$OUT/MoiehFih.apk"

echo "==> Listo: moiehfih/$OUT/MoiehFih.apk ($(du -h "$OUT/MoiehFih.apk" | cut -f1))"
