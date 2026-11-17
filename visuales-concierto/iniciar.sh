#!/usr/bin/env sh
# Levanta un servidor local y abre los visuales en el navegador.
cd "$(dirname "$0")"
URL="http://localhost:8080"
( sleep 1; (command -v xdg-open >/dev/null && xdg-open "$URL") || open "$URL" ) >/dev/null 2>&1 &
python3 -m http.server 8080
