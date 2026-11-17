#!/usr/bin/env bash
# Compila el APK sin Gradle, con las herramientas de Android de Debian/Ubuntu:
#   sudo apt-get install android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner
# Resultado: build/CasaOS-Docker.apk (firmado con una clave de depuración local).
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_SDK:-/usr/lib/android-sdk}
ANDROID_JAR=${ANDROID_JAR:-$SDK/platforms/android-23/android.jar}
BT=$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)
DX=${DX:-$BT/dx}
OUT=build
KEYSTORE=${KEYSTORE:-$HOME/.android/casaos-debug.keystore}

rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes"

echo "> aapt: recursos"
aapt package -f -m -J "$OUT/gen" -M AndroidManifest.xml -S res -I "$ANDROID_JAR" \
    -F "$OUT/unsigned.apk"

echo "> javac"
javac -source 8 -target 8 -nowarn -Xlint:-options -encoding UTF-8 \
    -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
    $(find src "$OUT/gen" -name '*.java')

echo "> dx: classes.dex"
"$DX" --dex --min-sdk-version=23 --output="$OUT/classes.dex" "$OUT/classes"
(cd "$OUT" && zip -q unsigned.apk classes.dex)

echo "> zipalign + apksigner"
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias casaos -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=CasaOS Docker,O=Mikasa,C=ES" >/dev/null 2>&1
fi
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias casaos --out "$OUT/CasaOS-Docker.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/CasaOS-Docker.apk"
echo "OK -> $OUT/CasaOS-Docker.apk"
