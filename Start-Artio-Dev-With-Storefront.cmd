@echo off
setlocal
cd /d "%~dp0"
where docker >nul 2>nul || (echo Docker CLI was not found.& pause & exit /b 1)
echo Starting Artio developer mode, Backoffice, Storefront, and Marketplace...
set "ARTIO_API_SPRING_PROFILES_ACTIVE=dev"
set "ARTIO_SETUP_SKIP=false"
set "ARTIO_SETUP_TOKEN=ArtioLocalDevSetupTokenOnly2026!"
set "MARKETPLACE_ROOT=%~dp0..\Artio-Marketplace"
call :prepare_marketplace -RequireMarketplace
if errorlevel 1 exit /b 1
call :stop_artio
if errorlevel 1 exit /b 1
docker compose --profile storefront up --build -d --wait --wait-timeout 300
if errorlevel 1 (echo Artio could not be started.& pause & exit /b 1)
echo.
echo Backoffice:       http://localhost:4201
echo Storefront:       http://localhost:4200
echo API:              http://localhost:8080
echo Marketplace admin: http://127.0.0.1:%ARTIO_MARKETPLACE_API_PORT%/dev-admin/
echo Zot UI:            %ARTIO_MARKETPLACE_ZOT_UI_URL%
echo Developer setup: admin@example.test / ArtioLocalAdmin!2026
start "" http://localhost:4201
start "" http://localhost:4200
pause
exit /b 0

:prepare_marketplace
set "MARKETPLACE_HELPER_OK="
for /f "tokens=1,* delims==" %%A in ('powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\prepare-local-marketplace.ps1" %*') do set "%%A=%%B"
if not defined MARKETPLACE_HELPER_OK (echo Marketplace could not be prepared. Artio will not start without its required development catalog.& pause & exit /b 1)
if not "%MARKETPLACE_ENABLED%"=="1" (echo Marketplace is required for this developer launcher.& exit /b 1)
exit /b 0

:stop_artio
docker compose --profile "*" stop
if errorlevel 1 exit /b 1
for /f %%i in ('docker ps -q --filter "label=io.artio.managed=true" --filter "label=io.artio.extension"') do docker stop %%i >nul
docker compose --profile "*" down --remove-orphans
exit /b %errorlevel%
