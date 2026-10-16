@echo off
setlocal
cd /d "%~dp0"

where docker >nul 2>nul
if errorlevel 1 (
    echo Docker CLI was not found. Install Docker Desktop and try again.
    pause
    exit /b 1
)

echo.
echo  +--------------------------------------------------------------------------+
echo  ^| WARNING! This script deletes all Artio containers, volumes ^& data.       ^|
echo  ^| This includes the database, setup secrets, media, and managed plugins.  ^|
echo  ^| This action cannot be undone. Other Docker projects and images are kept. ^|
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
docker compose --profile "*" down --volumes --remove-orphans
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
echo Artio containers and volumes have been removed.
pause
