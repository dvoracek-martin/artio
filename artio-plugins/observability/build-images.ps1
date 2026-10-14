$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$version = '1.0.0'
$images = @(
    @{ Name = 'observability-control'; Path = 'control' },
    @{ Name = 'observability-collector'; Path = 'collector' },
    @{ Name = 'observability-prometheus'; Path = 'prometheus' },
    @{ Name = 'observability-jaeger'; Path = 'jaeger' },
    @{ Name = 'observability-grafana'; Path = 'grafana' }
)
foreach ($image in $images) {
    docker build --pull -t "ghcr.io/artio-plugins/$($image.Name):$version" -f (Join-Path $root "$($image.Path)/Dockerfile") (Join-Path $root $image.Path)
    if ($LASTEXITCODE -ne 0) { throw "Docker build failed for $($image.Name)" }
}
Write-Host 'Images are built locally. Publish them through the approved release process, then replace every manifest digest placeholder with the registry-reported sha256 digest.'
