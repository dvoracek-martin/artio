@echo off
setlocal
cd /d "%~dp0"

where docker >nul 2>nul
if errorlevel 1 (
    echo Docker CLI was not found. Install Docker Desktop and try again.
    pause
    exit /b 1
)

echo Starting the Artio platform, Backoffice, and optional Storefront...
set "ARTIO_API_SPRING_PROFILES_ACTIVE=dev"
docker compose --profile storefront up --build -d --wait --wait-timeout 300
if errorlevel 1 (
    echo Artio could not be started. Review the Docker Compose output above.
    pause
    exit /b 1
)

echo.
echo Backoffice: http://localhost:4201
echo Storefront: http://localhost:4200
echo API:        http://localhost:8080
start "" http://localhost:4201
start "" http://localhost:4200
echo.
echo To stop Artio, run Stop-Artio.cmd from this folder.
pause
