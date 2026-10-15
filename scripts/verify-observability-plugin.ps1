param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$pluginRoot = [System.IO.Path]::Combine($repoRoot, 'artio-plugins', 'observability')
$registryName = "artio-observability-registry-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
$testTag = "test-$([guid]::NewGuid().ToString('N').Substring(0, 12))"
$registryStarted = $false
$initialContainers = @((docker ps -aq --no-trunc) | Where-Object { $_ } | Sort-Object -Unique)
$initialImages = @((docker image ls -aq --no-trunc) | Where-Object { $_ } | Sort-Object -Unique)
$initialVolumes = @((docker volume ls -q) | Where-Object { $_ } | Sort-Object -Unique)
$initialNetworks = @((docker network ls -q --no-trunc) | Where-Object { $_ } | Sort-Object -Unique)
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

    & (Join-Path $pluginRoot 'build-images.ps1') -Tag $testTag
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
        $localTag = "ghcr.io/artio-plugins/$($image.Name):$testTag"
        $registryTag = "localhost:5000/$($image.Name):$testTag"
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
        $gradleWrapper = if ($IsWindows) { Join-Path $repoRoot 'gradlew.bat' } else { Join-Path $repoRoot 'gradlew' }
        & $gradleWrapper :artio-rest-api:integrationTest --rerun --tests io.artio.api.extensions.ObservabilityPluginIntegrationTests --no-daemon
        if ($LASTEXITCODE -ne 0) { throw 'The live observability plugin bundle integration test failed.' }
    } finally { Pop-Location }
} finally {
    foreach ($image in $images) {
        Remove-Item -Path "Env:$($image.Variable)" -ErrorAction SilentlyContinue
    }
    if ($registryStarted) { docker rm --force --volumes $registryName | Out-Null }

    # Remove only Docker objects created while this isolated acceptance test ran.
    $newContainers = @((docker ps -aq --no-trunc) | Where-Object { $_ -and $_ -notin $initialContainers } | Sort-Object -Unique)
    if ($newContainers.Count -gt 0) { docker rm --force --volumes $newContainers | Out-Null }

    $newVolumes = @((docker volume ls -q) | Where-Object { $_ -and $_ -notin $initialVolumes } | Sort-Object -Unique)
    if ($newVolumes.Count -gt 0) { docker volume rm $newVolumes | Out-Null }

    $newNetworks = @((docker network ls -q --no-trunc) | Where-Object { $_ -and $_ -notin $initialNetworks } | Sort-Object -Unique)
    foreach ($network in $newNetworks) { docker network rm $network | Out-Null }

    $newImages = @((docker image ls -aq --no-trunc) | Where-Object { $_ -and $_ -notin $initialImages } | Sort-Object -Unique)
    if ($newImages.Count -gt 0) { docker image rm --force $newImages | Out-Null }
}
