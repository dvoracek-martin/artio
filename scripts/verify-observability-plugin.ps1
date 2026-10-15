param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$pluginRoot = Join-Path $repoRoot 'artio-plugins\observability'
$registryName = "artio-observability-registry-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
$registryStarted = $false
$images = @(
    @{ Name = 'observability-control'; Variable = 'ARTIO_TEST_OBS_CONTROL_IMAGE' },
    @{ Name = 'observability-collector'; Variable = 'ARTIO_TEST_OBS_COLLECTOR_IMAGE' },
    @{ Name = 'observability-prometheus'; Variable = 'ARTIO_TEST_OBS_PROMETHEUS_IMAGE' },
    @{ Name = 'observability-jaeger'; Variable = 'ARTIO_TEST_OBS_JAEGER_IMAGE' },
    @{ Name = 'observability-grafana'; Variable = 'ARTIO_TEST_OBS_GRAFANA_IMAGE' }
)

try {
    $occupied = docker ps --filter 'publish=5000' --format '{{.ID}}'
    if ($occupied) { throw 'Port 5000 is already published by a running container; stop it or change the fixture registry port before running this verification.' }

    & (Join-Path $pluginRoot 'build-images.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Could not build all observability plugin images.' }

    docker run --detach --name $registryName --publish 127.0.0.1:5000:5000 registry:2 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the temporary observability image registry.' }
    $registryStarted = $true
    $registryReady = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        try {
            Invoke-WebRequest -Uri 'http://127.0.0.1:5000/v2/' -UseBasicParsing -TimeoutSec 2 | Out-Null
            $registryReady = $true
            break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $registryReady) { throw 'The temporary observability image registry did not become ready.' }

    foreach ($image in $images) {
        $localTag = "ghcr.io/artio-plugins/$($image.Name):1.0.0"
        $registryTag = "localhost:5000/$($image.Name):1.0.0"
        docker tag $localTag $registryTag
        if ($LASTEXITCODE -ne 0) { throw "Could not tag $($image.Name) for the temporary registry." }
        docker push $registryTag
        if ($LASTEXITCODE -ne 0) { throw "Could not push $($image.Name) to the temporary registry." }
        $digestReference = docker image inspect $registryTag --format '{{index .RepoDigests 0}}'
        if ($LASTEXITCODE -ne 0 -or -not $digestReference -or $digestReference -notmatch '@sha256:[a-f0-9]{64}$') {
            throw "Could not resolve immutable digest for $($image.Name)."
        }
        Set-Item -Path "Env:$($image.Variable)" -Value $digestReference.Trim()
    }

    Push-Location $repoRoot
    try {
        & .\gradlew.bat :artio-rest-api:integrationTest --rerun --tests io.artio.api.extensions.ObservabilityPluginIntegrationTests --no-daemon
        if ($LASTEXITCODE -ne 0) { throw 'The live observability plugin bundle integration test failed.' }
    } finally { Pop-Location }
} finally {
    foreach ($image in $images) {
        Remove-Item -Path "Env:$($image.Variable)" -ErrorAction SilentlyContinue
    }
    if ($registryStarted) { docker rm --force $registryName | Out-Null }
}
