$ErrorActionPreference = "Stop"
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 12)
$containers = [System.Collections.Generic.List[string]]::new()
$images = [System.Collections.Generic.List[string]]::new()

function Invoke-Docker {
    param([string[]]$Arguments)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker $($Arguments -join ' ') failed with exit code $LASTEXITCODE" }
}

$smoke = @'
import json, os, time, urllib.error, urllib.request, uuid
provider = os.environ['ARTIO_ACCEPTANCE_PROVIDER']
token = os.environ['ARTIO_EXTENSION_TOKEN']
def request(path, method='GET', value=None, authorized=False):
    headers = {}
    body = None
    if value is not None:
        headers['Content-Type'] = 'application/json'
        body = json.dumps(value).encode()
    if authorized:
        headers['Authorization'] = 'Bearer ' + token
        headers['X-Artio-Extension-Api'] = '1'
    req = urllib.request.Request('http://127.0.0.1:8080' + path, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=3) as response:
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
    raise AssertionError('provider container did not become healthy')
status, diagnostics = request('/diagnostics')
assert status == 200 and diagnostics.get('status') == 'ready'
assert token not in json.dumps(diagnostics) and 'sk_test_' not in json.dumps(diagnostics)
status, _ = request('/v1/invoke', 'POST', {}, False)
assert status == 401
def invoke(operation, payload=None):
    value = {'protocolVersion': 1, 'extensionPoint': 'payment.provider.v1',
             'operation': operation, 'idempotencyKey': str(uuid.uuid4()),
             'configuration': {}, 'payload': payload or {}}
    return request('/v1/invoke', 'POST', value, True)
status, capabilities = invoke('capabilities')
assert status == 200 and capabilities.get('hostedCheckout') is True
if provider == 'stripe':
    assert capabilities.get('refunds') is True and capabilities.get('capture') is False
    assert invoke('createCheckout', {'amount': '10.00', 'currency': 'CHF', 'orderId': 'acceptance'})[0] == 503
    assert invoke('cancel', {'providerReference': 'invalid'})[0] == 400
    assert invoke('capture', {})[0] == 409
elif provider == 'paypal':
    assert capabilities.get('capture') is True and capabilities.get('cancellation') is False
    assert invoke('createCheckout', {'amount': '10.00', 'currency': 'CHF', 'orderId': 'acceptance'})[0] == 503
    assert invoke('capture', {'providerReference': 'invalid'})[0] == 400
    assert invoke('cancel', {})[0] == 409
else:
    raise AssertionError('unexpected provider')
print(provider + ' container passed health, diagnostics, contract, auth, capability, validation, and no-credentials checks')
'@

try {
    Push-Location $root
    try {
        foreach ($provider in @("stripe-payments", "paypal-payments")) {
            $providerId = if ($provider -eq "stripe-payments") { "stripe" } else { "paypal" }
            python -m unittest discover -s "artio-plugins/$provider" -p "test_*.py" -v
            if ($LASTEXITCODE -ne 0) { throw "Python package tests failed for $provider" }

            $image = "artio/$provider`:$suffix"
            $container = "artio-provider-$($provider.Replace('-', '-'))-$suffix"
            Invoke-Docker -Arguments @("build", "--file", "artio-plugins/$provider/Dockerfile", "--tag", $image, "artio-plugins/$provider")
            $images.Add($image)
            Invoke-Docker -Arguments @("run", "--detach", "--name", $container, "--network", "none", "--read-only",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true", "--pids-limit", "64", "--memory", "256m", "--cpus", "0.50",
                "--env", "ARTIO_EXTENSION_TOKEN=local-container-acceptance-token", "--env", "ARTIO_ACCEPTANCE_PROVIDER=$providerId", $image)
            $containers.Add($container)

            $deadline = [DateTime]::UtcNow.AddSeconds(30)
            $running = $false
            do {
                $state = (& docker inspect --format '{{.State.Running}}' $container).Trim()
                if ($LASTEXITCODE -ne 0) { throw "Could not inspect $container" }
                if ($state -eq "true") { $running = $true; break }
                Start-Sleep -Milliseconds 500
            } while ([DateTime]::UtcNow -lt $deadline)
            if (-not $running) { throw "$provider container did not start." }

            & docker exec $container python -c $smoke
            if ($LASTEXITCODE -ne 0) { throw "Live $provider container acceptance failed." }
        }
    }
    finally { Pop-Location }

    Write-Host "Payment provider container acceptance passed for Stripe and PayPal. No provider credentials or external network access were used."
}
finally {
    foreach ($container in $containers) {
        & docker rm --force $container | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove acceptance container $container" }
    }
    foreach ($image in $images) {
        & docker image rm $image | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove acceptance image $image" }
    }
}
