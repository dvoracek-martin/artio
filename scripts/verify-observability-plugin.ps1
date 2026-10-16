param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$pluginRoot = [System.IO.Path]::Combine($repoRoot, 'artio-plugins', 'observability')
$registryName = "artio-observability-registry-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
$testTag = "test-$([guid]::NewGuid().ToString('N').Substring(0, 12))"
$portReservation = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$portReservation.Start()
$registryPort = $portReservation.LocalEndpoint.Port
$portReservation.Stop()
$registryStarted = $false
$createdImageReferences = [System.Collections.Generic.List[string]]::new()
$testRunId = $testTag.Substring(5)
$images = @(
    @{ Name = 'observability-control'; Variable = 'ARTIO_TEST_OBS_CONTROL_IMAGE' },
    @{ Name = 'observability-collector'; Variable = 'ARTIO_TEST_OBS_COLLECTOR_IMAGE' },
    @{ Name = 'observability-prometheus'; Variable = 'ARTIO_TEST_OBS_PROMETHEUS_IMAGE' },
    @{ Name = 'observability-jaeger'; Variable = 'ARTIO_TEST_OBS_JAEGER_IMAGE' },
    @{ Name = 'observability-grafana'; Variable = 'ARTIO_TEST_OBS_GRAFANA_IMAGE' }
)

try {
    foreach ($image in $images) { $createdImageReferences.Add("ghcr.io/artio-plugins/$($image.Name):$testTag") }
    & (Join-Path $pluginRoot 'build-images.ps1') -Tag $testTag
    if ($LASTEXITCODE -ne 0) { throw 'Could not build all observability plugin images.' }

    Set-Item -Path 'Env:ARTIO_TEST_RUN_ID' -Value $testRunId
    docker run --detach --name $registryName --label "io.artio.test.run=$testRunId" --publish "127.0.0.1:${registryPort}:5000" registry:2 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the temporary observability image registry.' }
    $registryStarted = $true
    $registryReady = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        try {
            Invoke-WebRequest -Uri "http://127.0.0.1:${registryPort}/v2/" -UseBasicParsing -TimeoutSec 2 | Out-Null
            $registryReady = $true
            break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $registryReady) { throw 'The temporary observability image registry did not become ready.' }

    foreach ($image in $images) {
        $localTag = "ghcr.io/artio-plugins/$($image.Name):$testTag"
        $registryTag = "localhost:${registryPort}/$($image.Name):$testTag"
        $createdImageReferences.Add($registryTag)
        docker tag $localTag $registryTag
        if ($LASTEXITCODE -ne 0) { throw "Could not tag $($image.Name) for the temporary registry." }
        docker push $registryTag
        if ($LASTEXITCODE -ne 0) { throw "Could not push $($image.Name) to the temporary registry." }
        $accept = 'application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json'
        $manifestResponse = Invoke-WebRequest -Uri "http://127.0.0.1:${registryPort}/v2/$($image.Name)/manifests/$testTag" `
            -Method Head -Headers @{ Accept = $accept } -UseBasicParsing -TimeoutSec 15
        $manifestDigest = $manifestResponse.Headers['Docker-Content-Digest']
        if (-not $manifestDigest -or $manifestDigest -notmatch '^sha256:[a-f0-9]{64}$') {
            throw "Could not resolve immutable digest for $($image.Name)."
        }
        $digestReference = "localhost:${registryPort}/$($image.Name)@$manifestDigest"
        $createdImageReferences.Add($digestReference.Trim())
        Set-Item -Path "Env:$($image.Variable)" -Value $digestReference.Trim()
    }

    Push-Location $repoRoot
    try {
        $gradleWrapper = if ($IsWindows) { Join-Path $repoRoot 'gradlew.bat' } else { Join-Path $repoRoot 'gradlew' }
        $verificationArguments = @(
            ':artio-rest-api:integrationTest', '--rerun', '--tests',
            'io.artio.api.extensions.ObservabilityPluginIntegrationTests', '--no-daemon'
        )
        if ($IsWindows) {
            & $gradleWrapper @verificationArguments
        } else {
            bash $gradleWrapper @verificationArguments
        }
        if ($LASTEXITCODE -ne 0) { throw 'The live observability plugin bundle integration test failed.' }
    } finally { Pop-Location }
} finally {
    foreach ($image in $images) {
        Remove-Item -Path "Env:$($image.Variable)" -ErrorAction SilentlyContinue
    }
    Remove-Item Env:ARTIO_TEST_RUN_ID -ErrorAction SilentlyContinue
    if ($registryStarted) {
        $registryVolumes = @(docker inspect $registryName --format '{{range .Mounts}}{{if eq .Type "volume"}}{{.Name}}{{end}}{{end}}' 2>$null |
            Where-Object { $_ -and $_ -ne '<no value>' })
        docker rm --force --volumes $registryName | Out-Null
        foreach ($volumeName in $registryVolumes) { docker volume rm $volumeName 2>$null | Out-Null }
    }

    foreach ($containerId in @(docker ps -aq --no-trunc)) {
        try {
            $container = docker inspect $containerId | ConvertFrom-Json
            if ($container[0].Config.Labels.'io.artio.test.run' -eq $testRunId) {
                docker rm --force $containerId | Out-Null
            }
        } catch { Write-Warning "Could not inspect/remove observability test container ${containerId}: $_" }
    }
    foreach ($volumeName in @(docker volume ls -q)) {
        try {
            $volume = docker volume inspect $volumeName | ConvertFrom-Json
            if ($volume[0].Labels.'io.artio.test.run' -eq $testRunId) { docker volume rm $volumeName | Out-Null }
        } catch { Write-Warning "Could not inspect/remove observability test volume ${volumeName}: $_" }
    }
    foreach ($networkId in @(docker network ls -q)) {
        try {
            $network = docker network inspect $networkId | ConvertFrom-Json
            if ($network[0].Labels.'io.artio.test.run' -eq $testRunId) { docker network rm $networkId | Out-Null }
        } catch { Write-Warning "Could not inspect/remove observability test network ${networkId}: $_" }
    }
    foreach ($imageReference in $createdImageReferences) { docker image rm $imageReference 2>$null | Out-Null }
}
