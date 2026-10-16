@echo off
setlocal
cd /d "%~dp0"

where docker >nul 2>nul
if errorlevel 1 (
    echo Docker CLI was not found. Install Docker Desktop and try again.
    pause
    exit /b 1
)

echo Starting a clean Artio developer instance, Backoffice, and Storefront...
set "ARTIO_API_SPRING_PROFILES_ACTIVE=dev"
set "ARTIO_SETUP_SKIP=false"
set "ARTIO_SETUP_TOKEN=ArtioLocalDevSetupTokenOnly2026!"
call :stop_artio
if errorlevel 1 exit /b 1
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
echo Developer setup is prefilled. Credentials: admin@example.test / ArtioLocalAdmin!2026
start "" http://localhost:4201
start "" http://localhost:4200
pause
exit /b 0

:stop_artio
docker compose --profile "*" stop
if errorlevel 1 exit /b 1
for /f %%i in ('docker ps -q --filter "label=io.artio.managed=true" --filter "label=io.artio.extension"') do docker stop %%i >nul
docker compose --profile "*" down --remove-orphans
exit /b %errorlevel%
