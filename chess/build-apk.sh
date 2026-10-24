#!/usr/bin/env bash
# Compila el APK sin Gradle, solo con las herramientas del SDK de Android.
# Útil donde no hay acceso a Google Maven (el plugin de Android para Gradle).
# Con Android Studio basta con `./gradlew :chess:assembleDebug`.
#
# Requisitos (variables de entorno opcionales):
#   ANDROID_JAR   android.jar de la API 34      (def: $ANDROID_HOME/platforms/android-34/android.jar)
#   BUILD_TOOLS   carpeta con aapt2 y zipalign  (def: $ANDROID_HOME/build-tools/34.0.0)
#   KOTLIN_HOME   compilador de Kotlin 2.x      (def: /opt/kotlinc)
#   DX            d8 o dx (dalvik-exchange)     (def: d8 si existe, si no dalvik-exchange)
#   KEYSTORE      keystore de firma             (def: ~/.android/debug.keystore, se crea si falta)
#
# Salida: chess/build/puzzles-de-mate.apk
set -euo pipefail

cd "$(dirname "$0")"
ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_HOME/platforms/android-34/android.jar}"
BUILD_TOOLS="${BUILD_TOOLS:-$ANDROID_HOME/build-tools/34.0.0}"
KOTLIN_HOME="${KOTLIN_HOME:-/opt/kotlinc}"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
if [[ -z "${DX:-}" ]]; then
    if [[ -x "$BUILD_TOOLS/d8" ]]; then DX="$BUILD_TOOLS/d8"; else DX="dalvik-exchange"; fi
fi

PACKAGE=com.mikasa.chess
VERSION_CODE=1
VERSION_NAME=1.0
OUT=build
SRC=src/main

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> Recursos (aapt2)"
"$BUILD_TOOLS/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"
# El manifest no declara `package` (lo pone Gradle con `namespace`); se añade aquí.
sed "s|<manifest |<manifest package=\"$PACKAGE\" |" "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$BUILD_TOOLS/aapt2" link \
    -I "$ANDROID_JAR" \
    --manifest "$OUT/AndroidManifest.xml" \
    --min-sdk-version 26 --target-sdk-version 34 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    -A "$SRC/assets" \
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
test -f "$OUT/classes/com/mikasa/chess/ui/MainActivity.class"

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
"$BUILD_TOOLS/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [[ ! -f "$KEYSTORE" ]]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" 2>&1 | grep -v '^Picked up' || true
fi
APKSIGNER="apksigner"
[[ -x "$BUILD_TOOLS/apksigner" ]] && APKSIGNER="$BUILD_TOOLS/apksigner"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --out "$OUT/puzzles-de-mate.apk" "$OUT/aligned.apk"
"$APKSIGNER" verify "$OUT/puzzles-de-mate.apk"

echo "==> Listo: chess/$OUT/puzzles-de-mate.apk ($(du -h "$OUT/puzzles-de-mate.apk" | cut -f1))"
