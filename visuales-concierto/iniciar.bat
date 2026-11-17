@echo off
REM Levanta un servidor local y abre los visuales en el navegador.
cd /d "%~dp0"
start "" http://localhost:8080
python -m http.server 8080 || py -m http.server 8080
