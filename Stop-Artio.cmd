@echo off
setlocal
cd /d "%~dp0"
docker compose down
if errorlevel 1 (
    echo Artio could not be stopped. Review the Docker Compose output above.
    pause
    exit /b 1
)
echo Artio services stopped. Persistent database and installation data were kept.
pause
