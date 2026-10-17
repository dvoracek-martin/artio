[CmdletBinding()]
param([switch]$RequireMarketplace)

$ErrorActionPreference='Stop'
$repoRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$marketplaceRoot=if($env:MARKETPLACE_ROOT){$env:MARKETPLACE_ROOT}else{Join-Path $repoRoot '..\Artio-Marketplace'}
$enabled=$RequireMarketplace -or $env:ARTIO_START_LOCAL_MARKETPLACE -eq 'true'
if(-not $enabled){Write-Output 'MARKETPLACE_HELPER_OK=1';Write-Output 'MARKETPLACE_ENABLED=0';exit 0}
$startScript=Join-Path $marketplaceRoot 'scripts\start-local-marketplace.ps1'
if(-not(Test-Path -LiteralPath $startScript)){throw "The Marketplace worktree is required at $marketplaceRoot."}
$state=Join-Path $marketplaceRoot '.local-marketplace'
$connection=Join-Path $state 'connection.txt'
$ownershipMarker=Join-Path $state '.started-by-artio-launcher'
$ready=$false
$startedByThisLauncher=$false

function Read-Endpoint([string]$label){
  $line=Get-Content -LiteralPath $connection | Where-Object {$_ -like ($label+':*')} | Select-Object -First 1
  if(-not $line){throw "Marketplace connection file has no $label endpoint."}
  return ($line -replace ('^'+[regex]::Escape($label)+':\s*'),'').Trim()
}

if(Test-Path -LiteralPath $connection){
  try{
    $api=Read-Endpoint 'Marketplace API';$registry=Read-Endpoint 'Public OCI registry'
    $apiUri=[uri]$api
    $management=Read-Endpoint 'Readiness';$managementUri=[uri]$management
    $registryPort=0
    if($apiUri.Scheme -ne 'http' -or $apiUri.Host -notin @('127.0.0.1','localhost') -or $apiUri.Port -lt 1 -or
       $managementUri.Scheme -ne 'http' -or $managementUri.Host -notin @('127.0.0.1','localhost') -or $managementUri.Port -lt 1 -or
       $registry -notmatch '^localhost:([0-9]{1,5})$' -or -not[int]::TryParse($Matches[1],[ref]$registryPort) -or $registryPort -lt 1 -or $registryPort -gt 65535){throw 'Marketplace endpoints must use valid loopback addresses.'}
    $registryUri='http://'+$registry+'/v2/'
    $health=Invoke-RestMethod -Uri $management -TimeoutSec 3
    $registryResponse=Invoke-WebRequest -UseBasicParsing -Uri $registryUri -TimeoutSec 3
    $uiResponse=Invoke-WebRequest -UseBasicParsing -Uri ($api.TrimEnd('/')+'/dev-admin/') -TimeoutSec 3
    $ready=($health.status -eq 'UP' -and [int]$registryResponse.StatusCode -eq 200 -and [int]$uiResponse.StatusCode -eq 200 -and $uiResponse.Content.Contains('<ama-root>'))
  }catch{$ready=$false}
}
if(-not $ready){$startOutput=@(& $startScript *>&1);if($LASTEXITCODE -ne 0){[Console]::Error.WriteLine(($startOutput -join [Environment]::NewLine));throw 'Marketplace failed to start; Artio was not launched.'};$startedByThisLauncher=$true}
if(-not(Test-Path -LiteralPath $connection)){throw 'Marketplace did not write its connection file.'}
$api=Read-Endpoint 'Marketplace API';$management=Read-Endpoint 'Readiness';$registry=Read-Endpoint 'Public OCI registry'
$apiUri=[uri]$api
$managementUri=[uri]$management;$registryPort=0
if($apiUri.Scheme -ne 'http' -or $apiUri.Host -notin @('127.0.0.1','localhost') -or $apiUri.Port -lt 1 -or
   $managementUri.Scheme -ne 'http' -or $managementUri.Host -notin @('127.0.0.1','localhost') -or $managementUri.Port -lt 1 -or
   $registry -notmatch '^localhost:([0-9]{1,5})$' -or -not[int]::TryParse($Matches[1],[ref]$registryPort) -or $registryPort -lt 1 -or $registryPort -gt 65535){throw 'Marketplace endpoints must use valid loopback addresses.'}
$releasePath=Join-Path $state 'release-trusted-key.txt';$advisoryPath=Join-Path $state 'advisory-trusted-key.txt'
foreach($path in @($releasePath,$advisoryPath)){if(-not(Test-Path -LiteralPath $path)){throw "Marketplace trust key is missing: $path"};$key=(Get-Content -Raw -LiteralPath $path).Trim();if($key -notmatch '^[A-Za-z0-9._-]+=[A-Za-z0-9_-]+$'){throw "Marketplace trust key is malformed: $path"}}
$release=(Get-Content -Raw -LiteralPath $releasePath).Trim();$advisory=(Get-Content -Raw -LiteralPath $advisoryPath).Trim()
if($startedByThisLauncher){[IO.File]::WriteAllText($ownershipMarker,((Resolve-Path $repoRoot).Path+[Environment]::NewLine),[Text.UTF8Encoding]::new($false))}
Write-Output 'MARKETPLACE_HELPER_OK=1'
Write-Output 'MARKETPLACE_ENABLED=1'
Write-Output ('ARTIO_MARKETPLACE_BASE_URL=http://host.docker.internal:'+$apiUri.Port)
Write-Output ('ARTIO_MARKETPLACE_API_PORT='+$apiUri.Port)
Write-Output ('ARTIO_MARKETPLACE_TRUSTED_KEYS='+$release)
Write-Output ('ARTIO_MARKETPLACE_ADVISORY_TRUSTED_KEYS='+$advisory)
Write-Output ('ARTIO_EXTENSION_TRUSTED_REGISTRIES='+$registry)
Write-Output ('ARTIO_MARKETPLACE_ZOT_UI_URL=http://127.0.0.1:'+($registry -replace '^localhost:',''))
