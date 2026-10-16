@echo off
setlocal
cd /d "%~dp0"

where docker >nul 2>nul
if errorlevel 1 (
    echo Docker CLI was not found. Install Docker Desktop and try again.
    pause
    exit /b 1
)

echo Stopping all Artio services, including optional profiles and extensions...
docker compose --profile "*" stop
if errorlevel 1 (
    echo Artio services could not be stopped. Review the Docker Compose output above.
    pause
    exit /b 1
)
for /f %%i in ('docker ps -q --filter "label=io.artio.managed=true" --filter "label=io.artio.extension"') do docker stop %%i >nul
docker compose --profile "*" down --remove-orphans
if errorlevel 1 (
    echo Artio could not be stopped. Review the Docker Compose output above.
    pause
    exit /b 1
)
echo Artio services stopped. Persistent data volumes were kept.
pause
