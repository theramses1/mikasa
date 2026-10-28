#!/usr/bin/env bash
# Prueba de humo en un emulador: instala el APK de depuración, abre cada pantalla con datos de
# demostración, guarda capturas y falla si la app se cierra inesperadamente.
# Uso: emulator-smoke.sh <apk-debug> <carpeta-capturas>
set -uo pipefail
APK="$1"
OUT="$2"
PKG=com.moiehfih.app
ACT="$PKG/.ui.MainActivity"
mkdir -p "$OUT"

adb install -r "$APK"
adb logcat -c

shot() {
    sleep 3
    adb exec-out screencap -p > "$OUT/$1.png"
    echo "captura $1"
}

launch() {
    adb shell am force-stop "$PKG"
    adb shell am start -W -n "$ACT" "$@" > /dev/null
}

scroll() {
    adb shell input swipe 540 1700 540 500 400
}

launch
shot 00-bienvenida

launch --ez demo true --es tab home
shot 01-inicio
scroll; shot 02-inicio-abajo

for tab in diet train weight journal; do
    launch --es tab "$tab"
    shot "03-$tab"
    scroll; shot "04-$tab-abajo"
done

launch --es tab train --es open workout
shot 05-entreno-en-curso
launch --es tab train --es open exercise
shot 06-ejercicio
launch --es tab diet --es open food
shot 07-buscar-alimento
launch --es open settings
shot 08-ajustes
launch --es open health
shot 08b-salud
launch --es open cardio
shot 08c-cardio

adb shell cmd uimode night yes
launch --es tab home
shot 09-oscuro-inicio
launch --es tab diet
shot 10-oscuro-dieta
adb shell cmd uimode night no

if adb logcat -d | grep -E "FATAL EXCEPTION|ANR in $PKG" -A 30; then
    echo "::error::La app se cerró o dejó de responder"
    exit 1
fi
echo "Sin cierres inesperados"
