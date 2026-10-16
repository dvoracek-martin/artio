$ErrorActionPreference = "Stop"
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 12)
$containers = [System.Collections.Generic.List[string]]::new()
$images = [System.Collections.Generic.List[string]]::new()
$temporaryDirectories = [System.Collections.Generic.List[string]]::new()

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
status, methods = invoke('listMethods', {'country': 'CH', 'currency': 'CHF'})
assert status == 200 and methods.get('methods') and methods['methods'][0].get('hostedCheckout') is True
if provider == 'stripe':
    assert capabilities.get('refunds') is True and capabilities.get('capture') is False
    assert methods['methods'][0]['methodId'] == 'card'
    assert invoke('createCheckout', {'amount': '10.00', 'currency': 'CHF', 'orderId': 'acceptance', 'paymentMethodId': 'card'})[0] == 503
    assert invoke('cancel', {'providerReference': 'invalid'})[0] == 400
    assert invoke('capture', {})[0] == 409
elif provider == 'paypal':
    assert capabilities.get('capture') is True and capabilities.get('cancellation') is False
    assert methods['methods'][0]['methodId'] == 'paypal'
    assert invoke('createCheckout', {'amount': '10.00', 'currency': 'CHF', 'orderId': 'acceptance', 'paymentMethodId': 'paypal'})[0] == 503
    assert invoke('capture', {'providerReference': 'invalid'})[0] == 400
    assert invoke('cancel', {})[0] == 409
elif provider == 'postfinance':
    assert capabilities.get('refunds') is True and capabilities.get('cancellation') is True
    assert capabilities.get('capture') is False and capabilities.get('webhooks') is True
    assert methods['methods'][0]['methodId'] == 'postfinance_checkout'
    assert invoke('createCheckout', {'amount': '10.00', 'currency': 'CHF', 'orderId': 'acceptance', 'paymentMethodId': 'postfinance_checkout', 'successUrl': 'https://shop.example.test/paid', 'cancelUrl': 'https://shop.example.test/cancel'})[0] == 503
    assert invoke('cancel', {'providerReference': 'invalid'})[0] == 400
    assert invoke('capture', {})[0] == 409
else:
    raise AssertionError('unexpected provider')
print(provider + ' container passed health, diagnostics, contract, auth, capability, validation, and no-credentials checks')
'@

try {
    Push-Location $root
    try {
        foreach ($provider in @("stripe-payments", "paypal-payments", "postfinance-payments")) {
            $providerId = switch ($provider) {
                "stripe-payments" { "stripe" }
                "paypal-payments" { "paypal" }
                "postfinance-payments" { "postfinance" }
            }
            $testPython = "python"
            if ($providerId -eq "postfinance") {
                $testEnvironment = Join-Path ([System.IO.Path]::GetTempPath()) "artio-postfinance-tests-$suffix"
                $temporaryDirectories.Add($testEnvironment)
                & python -m venv $testEnvironment
                if ($LASTEXITCODE -ne 0) { throw "Could not create temporary PostFinance test environment" }
                $testPython = Join-Path $testEnvironment "Scripts/python.exe"
                & $testPython -m pip install --disable-pip-version-check -r "artio-plugins/$provider/requirements.txt"
                if ($LASTEXITCODE -ne 0) { throw "Could not install pinned PostFinance test dependencies" }
            }
            & $testPython -m unittest discover -s "artio-plugins/$provider" -p "test_*.py" -v
            if ($LASTEXITCODE -ne 0) { throw "Python package tests failed for $provider" }

            $image = "artio/$provider`:$suffix"
            $images.Add($image)
            $container = "artio-provider-$providerId-$suffix"
            $containers.Add($container)
            Invoke-Docker -Arguments @("build", "--file", "artio-plugins/$provider/Dockerfile", "--tag", $image, "artio-plugins/$provider")
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

    Write-Host "Payment provider container acceptance passed for Stripe, PayPal, and PostFinance. No provider credentials or external network access were used."
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
    foreach ($directory in $temporaryDirectories) {
        if (Test-Path -LiteralPath $directory) { Remove-Item -LiteralPath $directory -Recurse -Force }
    }
}
