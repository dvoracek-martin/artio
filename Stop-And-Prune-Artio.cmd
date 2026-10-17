@echo off
setlocal
cd /d "%~dp0"

where docker >nul 2>nul
if errorlevel 1 (
    echo Docker CLI was not found. Install Docker Desktop and try again.
    pause
    exit /b 1
)

set "MARKETPLACE_ROOT=%~dp0..\Artio-Marketplace"
if not exist "%MARKETPLACE_ROOT%\scripts\prune-local-marketplace.ps1" (
    echo Cannot safely prune the local Marketplace because its cleanup script was not found.
    echo Expected: %MARKETPLACE_ROOT%\scripts\prune-local-marketplace.ps1
    pause
    exit /b 1
)

echo.
echo  +--------------------------------------------------------------------------+
echo  ^| WARNING! This deletes Artio and local Marketplace data and containers.   ^|
echo  ^| This includes databases, registry, credentials, media and plugin data. ^|
echo  ^| This cannot be undone. Other projects and images in use are kept.      ^|
echo  +--------------------------------------------------------------------------+
echo.
choice /C YN /N /M "Confirm permanent deletion of Artio data? Press Y to continue, N to cancel: "
if errorlevel 2 exit /b 0

docker compose --profile "*" stop
if errorlevel 1 (
    echo Artio services could not be stopped. No resources were pruned.
    pause
    exit /b 1
)
for /f %%i in ('docker ps -aq --filter "label=io.artio.managed=true" --filter "label=io.artio.extension"') do docker rm -f %%i >nul
docker compose --profile "*" down --volumes --remove-orphans --rmi local
if errorlevel 1 (
    echo Artio could not be stopped and pruned. Review the Docker Compose output above.
    pause
    exit /b 1
)
for /f %%i in ('docker volume ls -q --filter "label=io.artio.managed=true" --filter "label=io.artio.extension"') do docker volume rm %%i >nul
if errorlevel 1 (
    echo Some extension volumes are still in use. Stop any remaining Artio containers and retry.
    pause
    exit /b 1
)
for /f %%i in ('docker network ls -q --filter "label=io.artio.managed=true" --filter "label=io.artio.extension"') do docker network rm %%i >nul
if errorlevel 1 (
    echo Some extension networks are still in use. Stop any remaining Artio containers and retry.
    pause
    exit /b 1
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%MARKETPLACE_ROOT%\scripts\prune-local-marketplace.ps1" -Confirmed
if errorlevel 1 (
    echo Artio was pruned, but Marketplace cleanup failed. Review the PowerShell output above and retry the prune script.
    pause
    exit /b 1
)

echo Artio and Marketplace containers, volumes, generated images, and local development state have been removed.
pause
