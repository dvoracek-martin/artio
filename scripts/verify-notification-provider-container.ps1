$ErrorActionPreference = "Stop"
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 12)
$image = "artio/plugin-brevo-acceptance:$suffix"
$container = "artio-brevo-acceptance-$suffix"

function Invoke-Docker {
    param([string[]]$Arguments)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker $($Arguments -join ' ') failed with exit code $LASTEXITCODE" }
}

$smoke = @'
import json, os, time, urllib.error, urllib.request
token = os.environ['ARTIO_EXTENSION_TOKEN']
def request(path, method='GET', value=None, authorized=False):
    headers = {}
    body = None
    if value is not None:
        body = json.dumps(value).encode()
        headers['Content-Type'] = 'application/json'
    if authorized:
        headers['Authorization'] = 'Bearer ' + token
        headers['X-Artio-Extension-Api'] = '1'
    req = urllib.request.Request('http://127.0.0.1:8080' + path, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=12) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())
for _ in range(60):
    try:
        if request('/health')[0] == 200:
            break
    except urllib.error.URLError:
        time.sleep(0.25)
else:
    raise AssertionError('Brevo container did not become healthy')
status, health = request('/health')
assert status == 200 and health == {'status': 'healthy', 'provider': 'brevo', 'secrets': 'redacted'}
status, diagnostics = request('/diagnostics')
assert status == 200 and diagnostics.get('status') == 'ready'
assert token not in json.dumps(diagnostics) and 'xkeysib-' not in json.dumps(diagnostics)
assert request('/v1/invoke', 'POST', {})[0] == 401
base = {'protocolVersion': 1, 'extensionPoint': 'notification.channel.v1', 'operation': 'deliver',
        'idempotencyKey': 'brevo-container-acceptance', 'configuration': {}, 'payload': {}}
assert request('/v1/invoke', 'POST', base, True)[0] == 503
assert request('/v1/invoke', 'POST', {**base, 'extensionPoint': 'payment.provider.v1'}, True)[0] == 400
with_dummy_key = {**base, 'configuration': {'apiKey': 'xkeysib-container-only-dummy-key',
                    'senderEmail': 'orders@example.test'},
                  'payload': {'channel': 'EMAIL', 'destination': 'buyer@example.test',
                              'subject': 'Acceptance', 'body': 'No email is delivered.'}}
status, result = request('/v1/invoke', 'POST', with_dummy_key, True)
assert status == 503 and result['accepted'] is False
assert 'xkeysib-container-only-dummy-key' not in json.dumps(result)
print('Brevo container passed health, diagnostics, auth, contract, fail-closed and network-isolation checks')
'@

try {
    Push-Location $root
    try {
        Invoke-Docker -Arguments @("build", "--file", "artio-plugins/brevo-email/Dockerfile", "--tag", $image, "artio-plugins/brevo-email")
        Invoke-Docker -Arguments @("run", "--detach", "--name", $container, "--network", "none", "--read-only",
            "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true", "--pids-limit", "64", "--memory", "128m", "--cpus", "0.50",
            "--env", "ARTIO_EXTENSION_TOKEN=local-container-acceptance-token", $image)
        $deadline = [DateTime]::UtcNow.AddSeconds(30)
        $running = $false
        do {
            $state = (& docker inspect --format '{{.State.Running}}' $container).Trim()
            if ($LASTEXITCODE -ne 0) { throw "Could not inspect $container" }
            if ($state -eq "true") { $running = $true; break }
            Start-Sleep -Milliseconds 500
        } while ([DateTime]::UtcNow -lt $deadline)
        if (-not $running) { throw "Brevo container did not start." }
        & docker exec $container python -c $smoke
        if ($LASTEXITCODE -ne 0) { throw "Live Brevo container acceptance failed." }
    }
    finally { Pop-Location }
    Write-Host "Brevo notification plugin container acceptance passed without provider credentials or external network access."
}
finally {
    & docker rm --force $container | Out-Null
    & docker image rm $image | Out-Null
}
