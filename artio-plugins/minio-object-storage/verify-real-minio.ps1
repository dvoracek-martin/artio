param(
  [string]$RunId = "minio-$(Get-Date -Format 'yyyyMMddHHmmss')-$([Guid]::NewGuid().ToString('N').Substring(0,8))"
)

$ErrorActionPreference = 'Stop'
if ($RunId -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,50}$') { throw 'RunId contains unsupported characters.' }
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$testPath = Join-Path $PSScriptRoot 'test_minio_integration.py'
$serverDockerfile = Join-Path $PSScriptRoot 'minio-server.Dockerfile'
$adapterDockerfile = Join-Path $PSScriptRoot 'Dockerfile'
$provisionerDockerfile = Join-Path $PSScriptRoot 'provisioner\Dockerfile'
$serverImage = "artio-minio-server:$RunId"
$adapterImage = "artio-minio-adapter:$RunId"
$provisionerImage = "artio-minio-provisioner:$RunId"
$rotationCleanupImage = "artio-minio-provisioner-cleanup:$RunId"
$network = "artio-minio-test-net-$RunId"
$dataVolume = "artio-minio-test-data-$RunId"
$adapterDataVolume = "artio-minio-test-adapter-data-$RunId"
$secretVolume = "artio-minio-test-secrets-$RunId"
$serverSecretVolume = "artio-minio-test-server-secrets-$RunId"
$runtimeSecretVolume = "artio-minio-test-runtime-secrets-$RunId"
$serverContainer = "artio-minio-test-server-$RunId"
$secretWriterContainer = "artio-minio-test-secret-writer-$RunId"
$artifactDirectory = Join-Path $PSScriptRoot 'acceptance'
$logPath = Join-Path $artifactDirectory "$RunId.log"
$temporaryRoot = [IO.Path]::GetFullPath($env:TEMP)
$secretDirectory = [IO.Path]::GetFullPath((Join-Path $temporaryRoot "artio-minio-secrets-$RunId"))
$createdNetwork = $false
$createdDataVolume = $false
$createdAdapterDataVolume = $false
$createdSecretVolume = $false
$createdServerSecretVolume = $false
$createdRuntimeSecretVolume = $false
$createdServer = $false
$createdSecretWriter = $false

function Invoke-DockerChecked([string[]]$Arguments) {
  $previousPreference = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  $output = & docker.exe @Arguments 2>&1
  $exitCode = $LASTEXITCODE
  $output | Tee-Object -FilePath $logPath -Append
  $ErrorActionPreference = $previousPreference
  if ($exitCode -ne 0) { throw "docker $($Arguments[0]) failed with exit code $exitCode" }
}

function New-RandomSecret([int]$ByteCount) {
  $bytes = [byte[]]::new($ByteCount)
  $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
  return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null
Set-Content -LiteralPath $logPath -Value "Real MinIO acceptance run: $RunId" -Encoding utf8

try {
  Invoke-DockerChecked @('build', '--file', $serverDockerfile, '--tag', $serverImage, $repo)
  Invoke-DockerChecked @('build', '--file', $adapterDockerfile, '--tag', $adapterImage, $PSScriptRoot)
  Invoke-DockerChecked @('build', '--file', $provisionerDockerfile, '--tag', $provisionerImage,
    (Join-Path $PSScriptRoot 'provisioner'))
  Invoke-DockerChecked @('build', '--build-arg', 'LIFECYCLE_STAGE=cleanup', '--file', $provisionerDockerfile,
    '--tag', $rotationCleanupImage, (Join-Path $PSScriptRoot 'provisioner'))

  $serverImageId = (& docker.exe image inspect $serverImage --format '{{.Id}} {{.Os}}/{{.Architecture}}').Trim()
  if ($LASTEXITCODE -ne 0) { throw 'Built MinIO image could not be inspected.' }
  $adapterImageId = (& docker.exe image inspect $adapterImage --format '{{.Id}} {{.Os}}/{{.Architecture}}').Trim()
  if ($LASTEXITCODE -ne 0) { throw 'Built adapter image could not be inspected.' }
  $provisionerImageId = (& docker.exe image inspect $provisionerImage --format '{{.Id}} {{.Os}}/{{.Architecture}}').Trim()
  if ($LASTEXITCODE -ne 0) { throw 'Built provisioner image could not be inspected.' }
  $rotationCleanupImageId = (& docker.exe image inspect $rotationCleanupImage --format '{{.Id}} {{.Os}}/{{.Architecture}}').Trim()
  if ($LASTEXITCODE -ne 0) { throw 'Built rotation cleanup image could not be inspected.' }
  Add-Content -LiteralPath $logPath -Value "MinIO image: $serverImageId`nAdapter image: $adapterImageId`nProvisioner image: $provisionerImageId`nRotation cleanup image: $rotationCleanupImageId"

  Invoke-DockerChecked @('network', 'create', '--label', "io.artio.test.run=$RunId", $network)
  $createdNetwork = $true
  Invoke-DockerChecked @('volume', 'create', '--label', "io.artio.test.run=$RunId", $dataVolume)
  $createdDataVolume = $true
  Invoke-DockerChecked @('volume', 'create', '--label', "io.artio.test.run=$RunId", $adapterDataVolume)
  $createdAdapterDataVolume = $true
  Invoke-DockerChecked @(
    'run', '--rm', '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges', '--user', '10001:10001',
    '--tmpfs', '/tmp:rw,noexec,nosuid,size=16m',
    '--mount', "type=volume,source=$adapterDataVolume,target=/data/minio",
    '--entrypoint', 'python', $adapterImage, '-c', "import server; root=server.root_path(); assert str(root) == '/data/minio'; assert all((root/name).is_dir() for name in ('staging','records','operations','tombstones'))"
  )
  Add-Content -LiteralPath $logPath -Value 'Fresh named adapter volume is initialized writable by the unprivileged plugin UID.'
  Invoke-DockerChecked @(
    'volume', 'create', '--driver', 'local', '--opt', 'type=tmpfs', '--opt', 'device=tmpfs',
    '--opt', 'o=size=1m,uid=10001,gid=10001,mode=0700', '--label', "io.artio.test.run=$RunId", $secretVolume
  )
  $createdSecretVolume = $true
  foreach ($volume in @($serverSecretVolume, $runtimeSecretVolume)) {
    Invoke-DockerChecked @('volume', 'create', '--driver', 'local', '--opt', 'type=tmpfs', '--opt', 'device=tmpfs',
      '--opt', 'o=size=1m,uid=10001,gid=10001,mode=0700', '--label', "io.artio.test.run=$RunId", $volume)
    if ($volume -eq $serverSecretVolume) { $createdServerSecretVolume = $true }
    else { $createdRuntimeSecretVolume = $true }
  }

  $rootUser = 'artio' + (New-RandomSecret 9)
  $rootPassword = New-RandomSecret 48
  $runtimeAccessKey = 'artio' + (New-RandomSecret 11)
  $runtimeSecretKey = New-RandomSecret 30
  $credentialsJson = @{ accessKeyId = $runtimeAccessKey; secretAccessKey = $runtimeSecretKey } | ConvertTo-Json -Compress
  if (-not $secretDirectory.StartsWith($temporaryRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The generated secret directory escaped the Windows temporary directory.'
  }
  [IO.Directory]::CreateDirectory($secretDirectory) | Out-Null
  $userSid = [Security.Principal.WindowsIdentity]::GetCurrent().User
  $acl = New-Object Security.AccessControl.DirectorySecurity
  $acl.SetAccessRuleProtection($true, $false)
  $acl.SetOwner($userSid)
  $inherit = [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit
  $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($userSid, 'FullControl', $inherit, 'None', 'Allow')))
  $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule([Security.Principal.SecurityIdentifier]::new('S-1-5-18'), 'FullControl', $inherit, 'None', 'Allow')))
  Set-Acl -LiteralPath $secretDirectory -AclObject $acl
  $utf8 = New-Object System.Text.UTF8Encoding($false)
  [IO.File]::WriteAllText((Join-Path $secretDirectory 'minio-root-user'), $rootUser, $utf8)
  [IO.File]::WriteAllText((Join-Path $secretDirectory 'minio-root-password'), $rootPassword, $utf8)
  [IO.File]::WriteAllText((Join-Path $secretDirectory 'runtime-access-key'), $runtimeAccessKey, $utf8)
  [IO.File]::WriteAllText((Join-Path $secretDirectory 'runtime-secret-key'), $runtimeSecretKey, $utf8)
  [IO.File]::WriteAllText((Join-Path $secretDirectory 's3-credentials.json'), $credentialsJson, $utf8)
  [IO.File]::WriteAllText((Join-Path $secretDirectory 'old-runtime-credentials.json'), $credentialsJson, $utf8)
  $rotationCheckPath = Join-Path $secretDirectory 'check-runtime-identity.py'
  [IO.File]::WriteAllText($rotationCheckPath, @'
import json
import os
import uuid
import boto3
from botocore.exceptions import ClientError

credentials = json.load(open(os.environ['ARTIO_ROTATION_SECRET_FILE'], encoding='utf-8'))
client = boto3.client('s3', endpoint_url='http://minio:9000', region_name='us-east-1',
    aws_access_key_id=credentials['accessKeyId'], aws_secret_access_key=credentials['secretAccessKey'])
key = 'objects/.artio-rotation/' + os.environ['ARTIO_ROTATION_RUN'] + '/' + uuid.uuid4().hex
try:
    client.put_object(Bucket='artio-objects', Key=key, Body=b'rotation-check')
except ClientError:
    if os.environ['ARTIO_ROTATION_EXPECT'] == 'denied':
        print('PASS: superseded runtime identity is denied')
    else:
        raise
else:
    if os.environ['ARTIO_ROTATION_EXPECT'] == 'denied':
        client.delete_object(Bucket='artio-objects', Key=key)
        raise SystemExit('superseded runtime identity still has write access')
    client.delete_object(Bucket='artio-objects', Key=key)
    print('PASS: prior runtime identity remains usable until cleanup')
'@, $utf8)
  $secretWriter = @(
    'run', '--detach', '--name', $secretWriterContainer, '--label', "io.artio.test.run=$RunId",
    '--network', 'none', '--user', '10001:10001',
    '--mount', "type=volume,source=$secretVolume,target=/run/secrets",
    '--mount', "type=volume,source=$serverSecretVolume,target=/run/server-secrets",
    '--mount', "type=volume,source=$runtimeSecretVolume,target=/run/runtime-secrets",
    '--mount', "type=bind,source=$secretDirectory,target=/host-secrets,readonly",
    'alpine:3.23@sha256:85fe1e81d6758c208f3e1eed4338a1997e19d4be002d4dd32d3100c9a8c010a0',
    'sh', '-c', 'umask 077; cp /host-secrets/minio-root-user /run/secrets/minio-root-user && cp /host-secrets/minio-root-password /run/secrets/minio-root-password && cp /host-secrets/runtime-access-key /run/secrets/runtime-access-key && cp /host-secrets/runtime-secret-key /run/secrets/runtime-secret-key && cp /host-secrets/minio-root-user /run/server-secrets/minio-root-user && cp /host-secrets/minio-root-password /run/server-secrets/minio-root-password && cp /host-secrets/runtime-access-key /run/runtime-secrets/runtime-access-key && cp /host-secrets/runtime-secret-key /run/runtime-secrets/runtime-secret-key && cp /host-secrets/s3-credentials.json /run/runtime-secrets/s3-credentials.json && chmod 0600 /run/secrets/* /run/server-secrets/* /run/runtime-secrets/* && sleep 300'
  )
  $previousPreference = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  $writerOutput = & docker.exe @secretWriter 2>&1
  $secretExit = $LASTEXITCODE
  if ($secretExit -eq 0) { $createdSecretWriter = $true }
  $writerOutput | Add-Content -LiteralPath $logPath
  $ErrorActionPreference = $previousPreference
  if ($secretExit -ne 0) { throw 'Could not populate the private test-only tmpfs secret volume.' }
  $secretCheck = @(
    'run', '--rm', '--network', 'none', '--user', '10001:10001',
    '--mount', "type=volume,source=$secretVolume,target=/run/secrets,readonly",
    '--mount', "type=volume,source=$serverSecretVolume,target=/run/server-secrets,readonly",
    '--mount', "type=volume,source=$runtimeSecretVolume,target=/run/runtime-secrets,readonly",
    'alpine:3.23@sha256:85fe1e81d6758c208f3e1eed4338a1997e19d4be002d4dd32d3100c9a8c010a0',
    'sh', '-c', 'test -s /run/secrets/minio-root-user && test -s /run/secrets/runtime-access-key && test -s /run/server-secrets/minio-root-user && test -s /run/runtime-secrets/runtime-access-key && test -s /run/runtime-secrets/s3-credentials.json'
  )
  & docker.exe @secretCheck 2>&1 | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Protected secret tmpfs did not retain all required files.' }
  Add-Content -LiteralPath $logPath -Value 'Protected secret tmpfs populated with required files.'

  Invoke-DockerChecked @(
    'run', '--detach', '--name', $serverContainer, '--label', "io.artio.test.run=$RunId",
    '--network', $network, '--network-alias', 'minio',
    '--mount', "type=volume,source=$dataVolume,target=/data/minio",
    '--mount', "type=volume,source=$serverSecretVolume,target=/run/secrets,readonly",
    '--tmpfs', '/tmp:rw,noexec,nosuid,nodev,size=16m,uid=10001,gid=10001,mode=0700',
    '--read-only', '--memory', '1g', '--cpus', '1.0', '--pids-limit', '128',
    '--security-opt', 'no-new-privileges:true', '--cap-drop', 'ALL',
    '--env', 'MINIO_ROOT_USER_FILE=/run/secrets/minio-root-user',
    '--env', 'MINIO_ROOT_PASSWORD_FILE=/run/secrets/minio-root-password',
    '--env', 'MINIO_BROWSER=off', $serverImage
  )
  $createdServer = $true

  $deadline = [DateTime]::UtcNow.AddSeconds(90)
  do {
    $running = (& docker.exe inspect $serverContainer --format '{{.State.Running}}' 2>$null).Trim()
    if ($LASTEXITCODE -ne 0 -or $running -ne 'true') { throw 'MinIO exited before reaching readiness.' }
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & docker.exe run --rm --network $network `
      alpine:3.23@sha256:85fe1e81d6758c208f3e1eed4338a1997e19d4be002d4dd32d3100c9a8c010a0 `
      wget -q -O - http://minio:9000/minio/health/ready 2>$null | Out-Null
    $healthExit = $LASTEXITCODE
    $ErrorActionPreference = $previousPreference
    if ($healthExit -eq 0) { break }
    Start-Sleep -Seconds 2
  } while ([DateTime]::UtcNow -lt $deadline)
  if ([DateTime]::UtcNow -ge $deadline) { throw 'MinIO did not become ready within 90 seconds.' }
  Invoke-DockerChecked @(
    'run', '--rm', '--network', $network,
    '--mount', "type=volume,source=$secretVolume,target=/run/secrets,readonly",
    '--env', 'ARTIO_EXTENSION_SERVICE_MINIO_HOST=minio',
    '--env', 'ARTIO_EXTENSION_SERVICE_MINIO_PORT=9000',
    $provisionerImage
  )
  Add-Content -LiteralPath $logPath -Value 'Scoped service account provisioning and positive/negative S3 permission checks passed.'

  Invoke-DockerChecked @(
    'run', '--rm', '--network', $network,
    '--mount', "type=volume,source=$runtimeSecretVolume,target=/run/secrets,readonly",
    '--tmpfs', '/data:rw,noexec,nosuid,nodev,size=16m,uid=10001,gid=10001,mode=0700',
    '--read-only', '--user', '10001:10001',
    '--env', 'ARTIO_STORAGE_MODE=managed',
    '--env', 'ARTIO_EXTENSION_SERVICE_MINIO_HOST=minio',
    '--env', 'ARTIO_EXTENSION_SERVICE_MINIO_PORT=9000',
    '--entrypoint', 'python', $adapterImage, '-c', "import server; assert server.diagnose()['status'] == 'ready'"
  )
  Add-Content -LiteralPath $logPath -Value 'Managed-mode adapter diagnostics passed with runtime-only secret files.'

  $testMount = "type=bind,source=$testPath,target=/tmp/test_minio_integration.py,readonly"
  Invoke-DockerChecked @(
    'run', '--rm', '--network', $network,
    '--mount', "type=volume,source=$runtimeSecretVolume,target=/run/secrets,readonly",
    '--mount', $testMount,
    '--env', 'ARTIO_MINIO_INTEGRATION_ENDPOINT=http://minio:9000',
    '--env', 'ARTIO_MINIO_INTEGRATION_CREDENTIALS_FILE=/run/secrets/s3-credentials.json',
    '--env', 'ARTIO_MINIO_INTEGRATION_MANAGED=true',
    '--env', 'PYTHONPATH=/opt/artio-extension',
    '--entrypoint', 'python', $adapterImage, '/tmp/test_minio_integration.py'
  )
  Add-Content -LiteralPath $logPath -Value 'Real MinIO object, versioning, tombstone, and denied-credential checks passed.'

  $nextAccessKey = 'artio' + (New-RandomSecret 11)
  $nextSecretKey = New-RandomSecret 30
  $nextCredentialsJson = @{ accessKeyId = $nextAccessKey; secretAccessKey = $nextSecretKey } | ConvertTo-Json -Compress
  [IO.File]::WriteAllText((Join-Path $secretDirectory 'runtime-access-key-next'), $nextAccessKey, $utf8)
  [IO.File]::WriteAllText((Join-Path $secretDirectory 'runtime-secret-key-next'), $nextSecretKey, $utf8)
  [IO.File]::WriteAllText((Join-Path $secretDirectory 's3-credentials-next.json'), $nextCredentialsJson, $utf8)
  $volumeUpdater = @(
    'run', '--rm', '--network', 'none', '--user', '10001:10001',
    '--mount', "type=volume,source=$secretVolume,target=/run/bootstrap-secrets",
    '--mount', "type=volume,source=$runtimeSecretVolume,target=/run/runtime-secrets",
    '--mount', "type=bind,source=$secretDirectory,target=/host-secrets,readonly",
    'alpine:3.23@sha256:85fe1e81d6758c208f3e1eed4338a1997e19d4be002d4dd32d3100c9a8c010a0',
    'sh', '-c', 'cp /host-secrets/runtime-access-key-next /run/bootstrap-secrets/runtime-access-key && cp /host-secrets/runtime-secret-key-next /run/bootstrap-secrets/runtime-secret-key && cp /host-secrets/runtime-access-key-next /run/runtime-secrets/runtime-access-key && cp /host-secrets/runtime-secret-key-next /run/runtime-secrets/runtime-secret-key && cp /host-secrets/s3-credentials-next.json /run/runtime-secrets/s3-credentials.json && chmod 0600 /run/bootstrap-secrets/runtime-access-key /run/bootstrap-secrets/runtime-secret-key /run/runtime-secrets/runtime-access-key /run/runtime-secrets/runtime-secret-key /run/runtime-secrets/s3-credentials.json'
  )
  Invoke-DockerChecked $volumeUpdater
  Invoke-DockerChecked @(
    'run', '--rm', '--network', $network,
    '--mount', "type=volume,source=$secretVolume,target=/run/secrets,readonly",
    '--env', 'ARTIO_EXTENSION_SERVICE_MINIO_HOST=minio',
    '--env', 'ARTIO_EXTENSION_SERVICE_MINIO_PORT=9000', $provisionerImage
  )
  Add-Content -LiteralPath $logPath -Value 'Replacement scoped identity was provisioned while the existing identity remained present.'

  Invoke-DockerChecked @(
    'run', '--rm', '--network', $network, '--mount', "type=bind,source=$secretDirectory,target=/run/test,readonly",
    '--mount', "type=volume,source=$secretVolume,target=/run/bootstrap-secrets,readonly",
    '--env', 'ARTIO_ROTATION_SECRET_FILE=/run/test/old-runtime-credentials.json',
    '--env', "ARTIO_ROTATION_RUN=$RunId", '--env', 'ARTIO_ROTATION_EXPECT=allowed',
    '--entrypoint', 'python', $adapterImage, '/run/test/check-runtime-identity.py'
  )
  Add-Content -LiteralPath $logPath -Value 'Pre-cleanup check confirmed the old identity still works after the replacement was staged.'

  $badRotationSecret = New-RandomSecret 30
  [IO.File]::WriteAllText((Join-Path $secretDirectory 'runtime-secret-key-invalid'), $badRotationSecret, $utf8)
  $badSecretUpdater = @(
    'run', '--rm', '--network', 'none', '--user', '10001:10001',
    '--mount', "type=volume,source=$secretVolume,target=/run/bootstrap-secrets",
    '--mount', "type=bind,source=$secretDirectory,target=/host-secrets,readonly",
    'alpine:3.23@sha256:85fe1e81d6758c208f3e1eed4338a1997e19d4be002d4dd32d3100c9a8c010a0',
    'sh', '-c', 'cp /host-secrets/runtime-secret-key-invalid /run/bootstrap-secrets/runtime-secret-key && chmod 0600 /run/bootstrap-secrets/runtime-secret-key'
  )
  Invoke-DockerChecked $badSecretUpdater
  $previousPreference = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  & docker.exe run --rm --network $network `
    --mount "type=volume,source=$secretVolume,target=/run/secrets,readonly" `
    --env ARTIO_EXTENSION_SERVICE_MINIO_HOST=minio --env ARTIO_EXTENSION_SERVICE_MINIO_PORT=9000 $rotationCleanupImage 2>&1 |
    Tee-Object -FilePath $logPath -Append
  $failedCleanupExitCode = $LASTEXITCODE
  $ErrorActionPreference = $previousPreference
  if ($failedCleanupExitCode -eq 0) { throw 'Rotation cleanup unexpectedly succeeded with an invalid replacement secret.' }
  Add-Content -LiteralPath $logPath -Value 'Expected replacement verification failure was rejected before revoking the previous identity.'
  Invoke-DockerChecked @(
    'run', '--rm', '--network', $network, '--mount', "type=bind,source=$secretDirectory,target=/run/test,readonly",
    '--env', 'ARTIO_ROTATION_SECRET_FILE=/run/test/old-runtime-credentials.json',
    '--env', "ARTIO_ROTATION_RUN=$RunId", '--env', 'ARTIO_ROTATION_EXPECT=allowed',
    '--entrypoint', 'python', $adapterImage, '/run/test/check-runtime-identity.py'
  )
  Add-Content -LiteralPath $logPath -Value 'Old identity remains usable after replacement verification fails.'
  Invoke-DockerChecked $volumeUpdater

  Invoke-DockerChecked @(
    'run', '--rm', '--network', $network,
    '--mount', "type=volume,source=$secretVolume,target=/run/secrets,readonly",
    '--env', 'ARTIO_EXTENSION_SERVICE_MINIO_HOST=minio',
    '--env', 'ARTIO_EXTENSION_SERVICE_MINIO_PORT=9000', $rotationCleanupImage
  )
  Add-Content -LiteralPath $logPath -Value 'Post-health identity cleanup revoked the superseded runtime identity.'
  Invoke-DockerChecked @(
    'run', '--rm', '--network', $network, '--mount', "type=bind,source=$secretDirectory,target=/run/test,readonly",
    '--env', 'ARTIO_ROTATION_SECRET_FILE=/run/test/old-runtime-credentials.json',
    '--env', "ARTIO_ROTATION_RUN=$RunId", '--env', 'ARTIO_ROTATION_EXPECT=denied',
    '--entrypoint', 'python', $adapterImage, '/run/test/check-runtime-identity.py'
  )
  Invoke-DockerChecked @(
    'run', '--rm', '--network', $network,
    '--mount', "type=volume,source=$runtimeSecretVolume,target=/run/secrets,readonly",
    '--tmpfs', '/data:rw,noexec,nosuid,nodev,size=16m,uid=10001,gid=10001,mode=0700',
    '--read-only', '--user', '10001:10001', '--env', 'ARTIO_STORAGE_MODE=managed',
    '--env', 'ARTIO_EXTENSION_SERVICE_MINIO_HOST=minio', '--env', 'ARTIO_EXTENSION_SERVICE_MINIO_PORT=9000',
    '--entrypoint', 'python', $adapterImage, '-c', "import server; assert server.diagnose()['status'] == 'ready'"
  )
  Add-Content -LiteralPath $logPath -Value 'Replacement identity remains ready after old identity revocation.'
  Invoke-DockerChecked @('logs', $serverContainer)
  Add-Content -LiteralPath $logPath -Value 'PASS: real MinIO S3 adapter and staged credential rotation acceptance completed.'
  Write-Output "MinIO acceptance passed. Evidence: $logPath"
}
catch {
  Add-Content -LiteralPath $logPath -Value "FAIL: $($_.Exception.Message)"
  if ($createdServer) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & docker.exe logs $serverContainer 2>&1 | ForEach-Object { "minio: $_" } | Add-Content -LiteralPath $logPath
    $ErrorActionPreference = $previousPreference
  }
  throw
}
finally {
  if ($createdServer) { & docker.exe rm --force $serverContainer 2>&1 | Add-Content -LiteralPath $logPath }
  if ($createdSecretWriter) { & docker.exe rm --force $secretWriterContainer 2>&1 | Add-Content -LiteralPath $logPath }
  if ($createdRuntimeSecretVolume) { & docker.exe volume rm $runtimeSecretVolume 2>&1 | Add-Content -LiteralPath $logPath }
  if ($createdServerSecretVolume) { & docker.exe volume rm $serverSecretVolume 2>&1 | Add-Content -LiteralPath $logPath }
  if ($createdSecretVolume) { & docker.exe volume rm $secretVolume 2>&1 | Add-Content -LiteralPath $logPath }
  if ($createdDataVolume) { & docker.exe volume rm $dataVolume 2>&1 | Add-Content -LiteralPath $logPath }
  if ($createdAdapterDataVolume) { & docker.exe volume rm $adapterDataVolume 2>&1 | Add-Content -LiteralPath $logPath }
  if ($createdNetwork) { & docker.exe network rm $network 2>&1 | Add-Content -LiteralPath $logPath }
  & docker.exe image rm $adapterImage $serverImage $provisionerImage $rotationCleanupImage 2>&1 | Add-Content -LiteralPath $logPath
  if (Test-Path -LiteralPath $secretDirectory) {
    $resolvedSecretDirectory = [IO.Path]::GetFullPath($secretDirectory)
    if (-not $resolvedSecretDirectory.StartsWith($temporaryRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
      throw 'Refusing to remove a test secret directory outside the Windows temporary directory.'
    }
    Remove-Item -LiteralPath $resolvedSecretDirectory -Recurse -Force
  }
}
