param([string[]]$Tests = @(
    'io.artio.api.extensions.DockerExtensionRuntimeIntegrationTests',
    'io.artio.api.extensions.PackagedExtensionControllerIntegrationTests'
))

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$fixturePath = Join-Path $repoRoot 'artio-extensions\reference-runtime'
$registryName = "artio-extension-registry-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
$registryStarted = $false

try {
    $occupied = docker ps --filter 'publish=5000' --format '{{.ID}}'
    if ($occupied) { throw 'Port 5000 is already published by a running container; stop it or change the fixture registry port before running this verification.' }
    docker run --detach --name $registryName --publish 127.0.0.1:5000:5000 registry:2 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the temporary local Docker registry.' }
    $registryStarted = $true

    $registryReady = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        try {
            Invoke-WebRequest -Uri 'http://127.0.0.1:5000/v2/' -TimeoutSec 2 | Out-Null
            $registryReady = $true
            break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $registryReady) { throw 'The temporary Docker registry did not become ready.' }

    foreach ($version in @('1', '2')) {
        $localTag = "artio-reference:v$version"
        $registryTag = "localhost:5000/artio-reference:v$version"
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

    Push-Location $repoRoot
    try {
        $verificationArguments = @(':artio-rest-api:integrationTest', '--rerun', '--no-daemon')
        foreach ($test in $Tests) { $verificationArguments += @('--tests', $test) }
        & .\gradlew.bat @verificationArguments
        if ($LASTEXITCODE -ne 0) { throw 'The real Docker extension lifecycle integration test failed.' }
    } finally { Pop-Location }
} finally {
    Remove-Item Env:ARTIO_TEST_EXTENSION_IMAGE_V1 -ErrorAction SilentlyContinue
    Remove-Item Env:ARTIO_TEST_EXTENSION_IMAGE_V2 -ErrorAction SilentlyContinue
    if ($registryStarted) {
        docker rm --force $registryName | Out-Null
    }
}
