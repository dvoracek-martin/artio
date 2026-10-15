@echo off
setlocal
cd /d "%~dp0"

where docker >nul 2>nul
if errorlevel 1 (
    echo Docker CLI was not found. Install Docker Desktop and try again.
    pause
    exit /b 1
)

echo Stopping all Artio services and removing their containers and network...
echo Persistent database, installation secrets, and media volumes will be kept.
docker compose --profile storefront down --remove-orphans
if errorlevel 1 (
    echo Artio could not be stopped. Review the Docker Compose output above.
    pause
    exit /b 1
)
echo Artio services stopped. Persistent data volumes were kept.
pause
