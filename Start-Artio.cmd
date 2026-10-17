@echo off
setlocal
cd /d "%~dp0"
where docker >nul 2>nul || (echo Docker CLI was not found.& pause & exit /b 1)
if not defined ARTIO_START_LOCAL_MARKETPLACE set "ARTIO_START_LOCAL_MARKETPLACE=false"
set "MARKETPLACE_ROOT=%~dp0..\Artio-Marketplace"
call :prepare_marketplace
if errorlevel 1 exit /b 1
echo Starting the Artio platform and Backoffice...
call :stop_artio
if errorlevel 1 exit /b 1
docker compose up --build -d --wait --wait-timeout 300
if errorlevel 1 (echo Artio could not be started.& pause & exit /b 1)
echo.
echo Backoffice: http://localhost:4201
echo API:        http://localhost:8080
if "%MARKETPLACE_ENABLED%"=="1" echo Marketplace admin: %MARKETPLACE_ADMIN_URL%
start "" http://localhost:4201
echo.
echo To start the optional Storefront too, run Start-Artio-With-Storefront.cmd.
echo To stop Artio, run Stop-Artio.cmd from this folder.
pause
exit /b 0

:prepare_marketplace
set "MARKETPLACE_HELPER_OK="
for /f "tokens=1,* delims==" %%A in ('powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\prepare-local-marketplace.ps1"') do set "%%A=%%B"
if not defined MARKETPLACE_HELPER_OK (echo Optional Marketplace setup failed.& pause & exit /b 1)
if "%MARKETPLACE_ENABLED%"=="1" set "MARKETPLACE_ADMIN_URL=http://127.0.0.1:%ARTIO_MARKETPLACE_API_PORT%/dev-admin/"
exit /b 0

:stop_artio
docker compose --profile "*" stop
if errorlevel 1 exit /b 1
for /f %%i in ('docker ps -q --filter "label=io.artio.managed=true" --filter "label=io.artio.extension"') do docker stop %%i >nul
docker compose --profile "*" down --remove-orphans
exit /b %errorlevel%
