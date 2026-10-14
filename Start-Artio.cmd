@echo off
setlocal
cd /d "%~dp0"

where docker >nul 2>nul
if errorlevel 1 (
    echo Docker CLI was not found. Install Docker Desktop and try again.
    pause
    exit /b 1
)

echo Starting the Artio platform and Backoffice...
docker compose up --build -d --wait --wait-timeout 300
if errorlevel 1 (
    echo Artio could not be started. Review the Docker Compose output above.
    pause
    exit /b 1
)

echo.
echo Backoffice: http://localhost:4201
echo API:        http://localhost:8080
start "" http://localhost:4201
echo.
echo To start the optional Storefront too, run Start-Artio-With-Storefront.cmd.
echo To stop Artio, run Stop-Artio.cmd from this folder.
pause
