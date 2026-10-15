param(
    [string]$ProjectName = "artio-first-run-acceptance",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$composeFile = Join-Path $PSScriptRoot "..\artio-docker\docker-compose.yml"
$composeFile = [System.IO.Path]::GetFullPath($composeFile)
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$emptyEnvFile = Join-Path ([System.IO.Path]::GetTempPath()) ("artio-empty-env-" + [Guid]::NewGuid().ToString("N"))
Set-Content -LiteralPath $emptyEnvFile -Value "" -NoNewline -Encoding ascii

function Invoke-DockerCompose {
    param([string[]]$Arguments)
    & docker compose --project-name $ProjectName --env-file $emptyEnvFile --file $composeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

function Get-FreeLoopbackPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
}

function Invoke-Api {
    param([string]$Method, [string]$Path, [object]$Body, [string]$Token)
    $headers = @{}
    if ($Token) { $headers["X-Artio-Setup-Token"] = $Token }
    $uri = "http://127.0.0.1:$script:apiPort$Path"
    $params = @{ Method = $Method; Uri = $uri; Headers = $headers; TimeoutSec = 20 }
    if ($null -ne $Body) { $params.ContentType = "application/json"; $params.Body = ConvertTo-Json -InputObject $Body -Depth 8 -Compress }
    Invoke-RestMethod @params
}

$apiPort = Get-FreeLoopbackPort
$postgresPort = Get-FreeLoopbackPort
$savedEnvironment = @{}
foreach ($name in @("ARTIO_API_PORT", "ARTIO_POSTGRES_PORT", "ARTIO_BIND_ADDRESS", "ARTIO_EXTENSION_DOCKER_ENABLED")) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}
$env:ARTIO_API_PORT = "$apiPort"
$env:ARTIO_POSTGRES_PORT = "$postgresPort"
$env:ARTIO_BIND_ADDRESS = "127.0.0.1"
$env:ARTIO_EXTENSION_DOCKER_ENABLED = "false"

$projectContainers = @()
try {
    Push-Location $root
    try {
        $upArguments = @("up", "--detach")
        if (-not $SkipBuild) { $upArguments += "--build" }
        $upArguments += @("postgres", "kafka", "kafka-init", "installation-secrets", "extension-egress", "extension-controller", "rest-api")
        Invoke-DockerCompose -Arguments $upArguments
    }
    finally { Pop-Location }

    $deadline = [DateTime]::UtcNow.AddMinutes(5)
    $ready = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $status = Invoke-Api -Method "Get" -Path "/api/v1/setup/status" -Body $null -Token $null
            if ($status.required -and -not $status.complete) { $ready = $true; break }
        }
        catch { Start-Sleep -Seconds 3 }
    }
    if (-not $ready) { throw "First-run API did not become ready within five minutes." }

    $blocked = $false
    try { Invoke-Api -Method "Get" -Path "/api/v1/products" -Body $null -Token $null | Out-Null }
    catch {
        if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 503) { $blocked = $true }
        else { throw }
    }
    if (-not $blocked) { throw "Commerce routes were not gated before setup completion." }

    $setupToken = (& docker compose --project-name $ProjectName --env-file $emptyEnvFile --file $composeFile exec -T rest-api cat /run/artio-secrets/setup-token).Trim()
    if ($LASTEXITCODE -ne 0 -or $setupToken.Length -lt 32) { throw "Generated setup token is missing or invalid." }
    $secretBefore = (& docker compose --project-name $ProjectName --env-file $emptyEnvFile --file $composeFile run --rm --no-deps --entrypoint /bin/sh installation-secrets -c "sha256sum /run/artio-secrets/setup-token").Split(" ")[0]
    if ($LASTEXITCODE -ne 0) { throw "Could not inspect the persisted setup token." }
    $mode = (& docker compose --project-name $ProjectName --env-file $emptyEnvFile --file $composeFile run --rm --no-deps --entrypoint /bin/sh installation-secrets -c "stat -c '%a' /run/artio-secrets/setup-token").Trim()
    if ($LASTEXITCODE -ne 0 -or $mode -ne "600") { throw "Generated setup token permissions are not 0600 (observed '$mode')." }

    $draft = @{
        countryCode = "CH"; currencyCode = "CHF"; locale = "de-CH"; timezone = "Europe/Zurich"
        email = "owner@example.test"; firstName = "Artio"; lastName = "Owner"
        storeName = "Artio Docker Acceptance"; availableLanguages = "de,fr,it,en"
        externalBaseUrl = "https://shop.example.test"
    }
    Invoke-Api -Method "Post" -Path "/api/v1/setup/progress" -Body $draft -Token $setupToken | Out-Null

    Invoke-DockerCompose -Arguments @("restart", "rest-api")
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    $resumed = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $resume = Invoke-Api -Method "Post" -Path "/api/v1/setup/resume" -Body @{} -Token $setupToken
            if ($resume.draft.email -eq "owner@example.test") { $resumed = $true; break }
        }
        catch { Start-Sleep -Seconds 3 }
    }
    if (-not $resumed) { throw "Setup draft did not survive the API restart." }

    $secretAfter = (& docker compose --project-name $ProjectName --env-file $emptyEnvFile --file $composeFile run --rm --no-deps --entrypoint /bin/sh installation-secrets -c "sha256sum /run/artio-secrets/setup-token").Split(" ")[0]
    if ($LASTEXITCODE -ne 0 -or $secretAfter -ne $secretBefore) { throw "Installation secrets changed across the API restart." }

    $completion = Invoke-Api -Method "Post" -Path "/api/v1/setup/complete" -Body @{ password = "Acceptance-strong-password-2026!" } -Token $setupToken
    if ($completion.administratorRole -ne "SUPERADMIN") { throw "First-run completion did not create the initial SUPERADMIN." }
    $finished = Invoke-Api -Method "Get" -Path "/api/v1/setup/status" -Body $null -Token $null
    if (-not $finished.complete) { throw "Setup completion was not persisted." }

    Write-Host "First-run Docker acceptance passed: zero-config secrets, 0600 permissions, commerce gate, resumable draft, stable secret, and SUPERADMIN creation."
}
finally {
    try {
        Invoke-DockerCompose -Arguments @("down", "--volumes", "--remove-orphans")
    }
    catch { Write-Warning "Isolated Compose cleanup failed: $_" }
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], "Process")
    }
    Remove-Item -LiteralPath $emptyEnvFile -Force -ErrorAction SilentlyContinue
}
