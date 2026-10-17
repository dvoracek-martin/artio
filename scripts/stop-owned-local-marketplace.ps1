[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$MarketplaceRoot
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $MarketplaceRoot -PathType Container)) {
    Write-Output 'Marketplace worktree is not present; no Marketplace action was needed.'
    exit 0
}
$root = (Resolve-Path -LiteralPath $MarketplaceRoot).Path
$state = Join-Path $root '.local-marketplace'
$marker = Join-Path $state '.started-by-artio-launcher'
if (-not (Test-Path -LiteralPath $marker -PathType Leaf)) {
    Write-Output 'Marketplace was not started by the Artio launcher; leaving it running.'
    exit 0
}

$stopScript = Join-Path $root 'scripts\stop-local-marketplace.ps1'
if (-not (Test-Path -LiteralPath $stopScript -PathType Leaf)) {
    throw "Owned Marketplace stop script is missing: $stopScript"
}

& powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File $stopScript
if ($LASTEXITCODE -ne 0) {
    throw 'The Artio-owned Marketplace did not stop cleanly; its ownership marker was retained for retry.'
}

Remove-Item -LiteralPath $marker -Force
Write-Output 'The Artio-owned Marketplace stopped; its persistent data volumes were kept.'
