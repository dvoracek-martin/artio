$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$fixtureId = [Guid]::NewGuid().ToString('N')
$fixtureImage = "artio-local-archive-acceptance:$fixtureId"
$referenceDigest = 'sha256:' + [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
$runtimeAlias = 'artio-local-cache:ref-' + $referenceDigest.Replace(':', '-')
$temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) "artio-local-archive-$fixtureId"
$previousArchiveFile = $env:ARTIO_LOCAL_ARCHIVE_TEST_FILE
$previousReferenceDigest = $env:ARTIO_LOCAL_ARCHIVE_TEST_REFERENCE_DIGEST

try {
    New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null
    Set-Content -LiteralPath (Join-Path $temporaryDirectory 'Dockerfile') -Value "FROM scratch`nCOPY payload /payload`n"
    Set-Content -LiteralPath (Join-Path $temporaryDirectory 'payload') -Value 'Artio local archive acceptance fixture'

    & docker build --pull=false --tag $fixtureImage $temporaryDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Could not build the disposable scratch image.' }
    $archive = Join-Path $temporaryDirectory 'fixture.tar'
    & docker save --output $archive $fixtureImage
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the Docker-save acceptance archive.' }

    $env:ARTIO_LOCAL_ARCHIVE_TEST_FILE = $archive
    $env:ARTIO_LOCAL_ARCHIVE_TEST_REFERENCE_DIGEST = $referenceDigest
    Push-Location $projectRoot
    try {
        & .\gradlew.bat --no-daemon :artio-rest-api:integrationTest --tests io.artio.api.extensions.DockerExtensionRuntimeIntegrationTests.inspectsDockerSaveArchiveAndImportsItsVerifiedConfigIntoLocalCache
        if ($LASTEXITCODE -ne 0) { throw 'Local archive import acceptance failed.' }
    }
    finally {
        Pop-Location
    }
}
finally {
    # Remove only the deterministic private tag and exact fixture image created above.
    try { & docker image rm --force $runtimeAlias $fixtureImage 2>$null | Out-Null } catch { }
    $env:ARTIO_LOCAL_ARCHIVE_TEST_FILE = $previousArchiveFile
    $env:ARTIO_LOCAL_ARCHIVE_TEST_REFERENCE_DIGEST = $previousReferenceDigest
    if (Test-Path -LiteralPath $temporaryDirectory) {
        Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force
    }
}
