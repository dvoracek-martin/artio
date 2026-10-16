@echo off
setlocal
cd /d "%~dp0"

where docker >nul 2>nul
if errorlevel 1 (
    echo Docker CLI was not found. Install Docker Desktop and try again.
    pause
    exit /b 1
)

echo Starting a clean Artio developer instance and Backoffice...
set "ARTIO_API_SPRING_PROFILES_ACTIVE=dev"
set "ARTIO_SETUP_SKIP=false"
set "ARTIO_SETUP_TOKEN=ArtioLocalDevSetupTokenOnly2026!"
set "MARKETPLACE_ROOT=%~dp0..\Artio-Marketplace"
if not exist "%MARKETPLACE_ROOT%\scripts\start-local-marketplace.ps1" (
    echo The sibling Artio-Marketplace worktree is required for the development extension catalog.
    echo Expected: %MARKETPLACE_ROOT%
    pause
    exit /b 1
)
echo Starting the local Artio Marketplace for extension discovery...
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%MARKETPLACE_ROOT%\scripts\start-local-marketplace.ps1"
if errorlevel 1 (
    echo Marketplace could not be started. Artio will not have an extension catalog.
    pause
    exit /b 1
)
for /f "usebackq delims=" %%A in (`powershell.exe -NoLogo -NoProfile -Command "$path = Join-Path '%MARKETPLACE_ROOT%' '.local-marketplace\connection.txt'; $line = Get-Content -LiteralPath $path | Where-Object { $_ -like 'Marketplace API:*' } | Select-Object -First 1; if (-not $line) { exit 1 }; $uri = [uri](($line -replace '^Marketplace API:\s*', '').Trim()); if (-not $uri.IsAbsoluteUri -or $uri.Port -lt 1) { exit 1 }; Write-Output ('http://host.docker.internal:' + $uri.Port)"`) do set "ARTIO_MARKETPLACE_BASE_URL=%%A"
if not defined ARTIO_MARKETPLACE_BASE_URL (
    echo Could not resolve the local Marketplace API endpoint.
    pause
    exit /b 1
)
for /f "usebackq delims=" %%A in (`powershell.exe -NoLogo -NoProfile -Command "$path = Join-Path '%MARKETPLACE_ROOT%' '.local-marketplace\advisory-trusted-key.txt'; if (-not (Test-Path -LiteralPath $path)) { exit 1 }; $key = (Get-Content -Raw -LiteralPath $path).Trim(); if ($key -notmatch '^[A-Za-z0-9._-]+=[A-Za-z0-9_-]+$') { exit 1 }; Write-Output $key"`) do set "ARTIO_MARKETPLACE_ADVISORY_TRUSTED_KEYS=%%A"
if not defined ARTIO_MARKETPLACE_ADVISORY_TRUSTED_KEYS (
    echo Could not resolve the local Marketplace advisory verification key.
    echo Review %MARKETPLACE_ROOT%\.local-marketplace\advisory-trusted-key.txt
    pause
    exit /b 1
)
call :stop_artio
if errorlevel 1 exit /b 1
docker compose up --build -d --wait --wait-timeout 300
if errorlevel 1 (
    echo Artio could not be started. Review the Docker Compose output above.
    pause
    exit /b 1
)

echo.
echo Backoffice: http://localhost:4201
echo API:        http://localhost:8080
echo Developer setup is prefilled. Credentials: admin@example.test / ArtioLocalAdmin!2026
start "" http://localhost:4201
pause
exit /b 0

:stop_artio
docker compose --profile "*" stop
if errorlevel 1 exit /b 1
for /f %%i in ('docker ps -q --filter "label=io.artio.managed=true" --filter "label=io.artio.extension"') do docker stop %%i >nul
docker compose --profile "*" down --remove-orphans
exit /b %errorlevel%
