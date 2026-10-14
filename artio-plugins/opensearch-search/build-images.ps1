$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$version = '1.0.0'
docker build --pull -t "ghcr.io/artio-plugins/opensearch-search:$version" -f (Join-Path $root 'Dockerfile') $root
if ($LASTEXITCODE -ne 0) { throw 'OpenSearch plugin image build failed' }
docker build --pull -t "ghcr.io/artio-plugins/opensearch-search-node:$version" -f (Join-Path $root 'opensearch/Dockerfile') (Join-Path $root 'opensearch')
if ($LASTEXITCODE -ne 0) { throw 'OpenSearch node image build failed' }
Write-Host 'Images are built locally. Publish them through the approved release process, verify both registry sha256 digests, then replace the manifest placeholders.'
