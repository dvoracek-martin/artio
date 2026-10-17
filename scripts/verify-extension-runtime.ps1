param(
    [string[]]$Tests = @(
    'io.artio.api.extensions.DockerExtensionRuntimeIntegrationTests',
    'io.artio.api.extensions.PackagedExtensionControllerIntegrationTests',
    'io.artio.api.extensions.PluginEventDeliveryDockerIntegrationTests'
),
    [switch]$SkipObjectStorageTest
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$fixturePath = [System.IO.Path]::Combine($repoRoot, 'artio-extensions', 'reference-runtime')
$registryName = "artio-extension-registry-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
$runSuffix = [guid]::NewGuid().ToString('N').Substring(0, 8)
$portReservation = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$portReservation.Start()
$registryPort = $portReservation.LocalEndpoint.Port
$portReservation.Stop()
$createdImageReferences = [System.Collections.Generic.List[string]]::new()
$registryStarted = $false
$registryImagePreexisting = $false

docker image inspect registry:2 *> $null
if ($LASTEXITCODE -eq 0) { $registryImagePreexisting = $true }

try {
    docker run --detach --name $registryName --publish "127.0.0.1:${registryPort}:5000" registry:2 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the temporary local Docker registry.' }
    $registryStarted = $true
    Set-Item -Path 'Env:ARTIO_TEST_RUN_ID' -Value $runSuffix

    $registryReady = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        try {
            Invoke-WebRequest -Uri "http://127.0.0.1:${registryPort}/v2/" -UseBasicParsing -TimeoutSec 2 | Out-Null
            $registryReady = $true
            break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $registryReady) { throw 'The temporary Docker registry did not become ready.' }

    foreach ($version in @('1', '2')) {
        $createdImageReferences.Add("artio-reference:$runSuffix-v$version")
        $createdImageReferences.Add("localhost:${registryPort}/artio-reference:$runSuffix-v$version")
    }
    $createdImageReferences.Add("artio-extension-egress:$runSuffix")
    $createdImageReferences.Add("localhost:${registryPort}/artio-extension-egress:$runSuffix")
    $createdImageReferences.Add("artio-object-storage:$runSuffix")
    $createdImageReferences.Add("localhost:${registryPort}/artio-object-storage:$runSuffix")

    foreach ($version in @('1', '2')) {
        $localTag = "artio-reference:$runSuffix-v$version"
        $registryTag = "localhost:${registryPort}/artio-reference:$runSuffix-v$version"
        docker build --platform linux/amd64 --build-arg "ARTIO_REFERENCE_VERSION=$version" --tag $localTag $fixturePath
        if ($LASTEXITCODE -ne 0) { throw "Could not build reference extension version $version." }
        docker tag $localTag $registryTag
        if ($LASTEXITCODE -ne 0) { throw "Could not tag reference extension version $version." }
        docker push $registryTag
        if ($LASTEXITCODE -ne 0) { throw "Could not push reference extension version $version to the temporary registry." }
        $digestReference = docker image inspect $registryTag --format '{{index .RepoDigests 0}}'
        if ($LASTEXITCODE -ne 0 -or -not $digestReference -or $digestReference -notmatch '@sha256:[a-f0-9]{64}$') {
            throw "Could not resolve immutable digest for reference extension version $version."
        }
        Set-Item -Path "Env:ARTIO_TEST_EXTENSION_IMAGE_V$version" -Value $digestReference.Trim()
    }

    $localEgressTag = "artio-extension-egress:$runSuffix"
    $egressTag = "localhost:${registryPort}/artio-extension-egress:$runSuffix"
    docker build --platform linux/amd64 --tag $localEgressTag (Join-Path $repoRoot 'artio-docker\extension-egress')
    if ($LASTEXITCODE -ne 0) { throw 'Could not build the provider-egress gateway fixture.' }
    docker tag $localEgressTag $egressTag
    if ($LASTEXITCODE -ne 0) { throw 'Could not tag the provider-egress gateway fixture.' }
    docker push $egressTag
    if ($LASTEXITCODE -ne 0) { throw 'Could not push the provider-egress gateway fixture to the temporary registry.' }
    $egressDigest = docker image inspect $egressTag --format '{{index .RepoDigests 0}}'
    if ($LASTEXITCODE -ne 0 -or -not $egressDigest -or $egressDigest -notmatch '@sha256:[a-f0-9]{64}$') {
        throw 'Could not resolve immutable digest for the provider-egress gateway fixture.'
    }
    Set-Item -Path 'Env:ARTIO_TEST_EGRESS_IMAGE' -Value $egressDigest.Trim()

    $storageLocalTag = "artio-object-storage:$runSuffix"
    $storageRegistryTag = "localhost:${registryPort}/artio-object-storage:$runSuffix"
    docker build --platform linux/amd64 --tag $storageLocalTag (Join-Path $repoRoot 'artio-plugins\object-storage')
    if ($LASTEXITCODE -ne 0) { throw 'Could not build the object-storage plugin fixture.' }
    docker tag $storageLocalTag $storageRegistryTag
    if ($LASTEXITCODE -ne 0) { throw 'Could not tag the object-storage plugin fixture.' }
    docker push $storageRegistryTag
    if ($LASTEXITCODE -ne 0) { throw 'Could not push the object-storage fixture to the temporary registry.' }
    $storageDigest = docker image inspect $storageRegistryTag --format '{{index .RepoDigests 0}}'
    if ($LASTEXITCODE -ne 0 -or -not $storageDigest -or $storageDigest -notmatch '@sha256:[a-f0-9]{64}$') {
        throw 'Could not resolve immutable digest for the object-storage fixture.'
    }
    Set-Item -Path 'Env:ARTIO_TEST_OBJECT_STORAGE_IMAGE' -Value $storageDigest.Trim()

    Push-Location $repoRoot
    try {
        $verificationArguments = @(':artio-rest-api:integrationTest', '--rerun', '--no-daemon', '--max-workers=1')
        foreach ($test in $Tests) { $verificationArguments += @('--tests', $test) }
        if (-not $SkipObjectStorageTest) {
            $verificationArguments += @('--tests', 'io.artio.api.extensions.ObjectStorageDockerIntegrationTests')
        }
        $gradleWrapper = if ($IsWindows) { Join-Path $repoRoot 'gradlew.bat' } else { Join-Path $repoRoot 'gradlew' }
        if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
            throw "Gradle wrapper was not found at '$gradleWrapper'."
        }
        if ($IsWindows) {
            & $gradleWrapper @verificationArguments
        } else {
            bash $gradleWrapper @verificationArguments
        }
        if ($LASTEXITCODE -ne 0) { throw 'The real Docker extension lifecycle integration test failed.' }
    } finally { Pop-Location }
} finally {
    Remove-Item Env:ARTIO_TEST_EXTENSION_IMAGE_V1 -ErrorAction SilentlyContinue
    Remove-Item Env:ARTIO_TEST_EXTENSION_IMAGE_V2 -ErrorAction SilentlyContinue
    Remove-Item Env:ARTIO_TEST_EGRESS_IMAGE -ErrorAction SilentlyContinue
    Remove-Item Env:ARTIO_TEST_OBJECT_STORAGE_IMAGE -ErrorAction SilentlyContinue
    Remove-Item Env:ARTIO_TEST_RUN_ID -ErrorAction SilentlyContinue
    if ($registryStarted) {
        $registryDetails = @(docker inspect $registryName 2>$null | ConvertFrom-Json)
        $registryVolumes = @($registryDetails[0].Mounts | Where-Object { $_.Type -eq 'volume' } |
            ForEach-Object { $_.Name })
        docker rm --force $registryName | Out-Null
        foreach ($volumeName in $registryVolumes) { docker volume rm $volumeName 2>$null | Out-Null }
    }

    # Every cleanup selector below includes this run's random ID or refers to a volume mounted
    # directly by the temporary registry. Never sweep resources by inventory difference: another
    # Artio project may be running Docker tests at the same time.
    foreach ($containerId in @(docker ps -aq --no-trunc)) {
        try {
            $inspect = docker inspect $containerId | ConvertFrom-Json
            $labels = $inspect[0].Config.Labels
            $extensionId = [string]$labels.'io.artio.extension'
            if ($extensionId.StartsWith("runtime.e2e$runSuffix") -or $extensionId.StartsWith("secret.e2e$runSuffix") -or
                    $extensionId.StartsWith("runtime.egress$runSuffix") -or
                    $extensionId.StartsWith("controller.e2e$runSuffix") -or $labels.'io.artio.test.egress' -eq $runSuffix -or
                    $labels.'io.artio.test.run' -eq $runSuffix) {
                docker rm --force $containerId | Out-Null
            }
        } catch { Write-Warning "Could not inspect/remove test container ${containerId}: $_" }
    }

    foreach ($volumeName in @(docker volume ls -q)) {
        try {
            $volume = docker volume inspect $volumeName | ConvertFrom-Json
            $labels = $volume[0].Labels
            $extensionId = [string]$labels.'io.artio.extension'
            $fixtureIdentity = [string]$labels.'io.artio.test.fixture'
            $runtimeIdentity = [string]$labels.'io.artio.extension.runtime'
            if (($extensionId.StartsWith("runtime.e2e$runSuffix") -or $extensionId.StartsWith("secret.e2e$runSuffix") -or
                    $extensionId.StartsWith("runtime.egress$runSuffix") -or
                    $extensionId.StartsWith("controller.e2e$runSuffix")) -or $runtimeIdentity.StartsWith("runtime.e2e$runSuffix") -or
                    $fixtureIdentity.StartsWith($runSuffix)) {
                docker volume rm $volumeName | Out-Null
            }
        } catch { Write-Warning "Could not inspect/remove test volume ${volumeName}: $_" }
    }

    foreach ($networkId in @(docker network ls -q)) {
        try {
            $network = docker network inspect $networkId | ConvertFrom-Json
            if ($network[0].Labels.'io.artio.test.run' -eq $runSuffix) {
                docker network rm $networkId | Out-Null
            }
        } catch { Write-Warning "Could not inspect/remove test network ${networkId}: $_" }
    }

    foreach ($imageReference in $createdImageReferences) {
        docker image rm $imageReference 2>$null | Out-Null
    }
    foreach ($imageReference in @(docker image ls --format '{{.Repository}}:{{.Tag}}' |
            Where-Object { $_ -like "artio/controller-it:$runSuffix*" })) {
        docker image rm $imageReference 2>$null | Out-Null
    }

    if (-not $registryImagePreexisting -and @(docker ps -aq --filter ancestor=registry:2).Count -eq 0) {
        docker image rm registry:2 2>$null | Out-Null
    }
}
