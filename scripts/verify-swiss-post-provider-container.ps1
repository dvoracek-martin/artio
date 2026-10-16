$ErrorActionPreference = "Stop"
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 12)
$image = "artio/plugin-swiss-post-acceptance:$suffix"
$container = "artio-swiss-post-acceptance-$suffix"
$volume = "artio-swiss-post-acceptance-$suffix"

function Invoke-Docker {
    param([string[]]$Arguments)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker $($Arguments -join ' ') failed with exit code $LASTEXITCODE" }
}

$smoke = @'
import json, os, sys, time, urllib.error, urllib.request
token = os.environ['ARTIO_EXTENSION_TOKEN']
mode = sys.argv[1]
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
        with urllib.request.urlopen(req, timeout=20) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())
def healthy():
    for _ in range(60):
        try:
            if request('/health')[0] == 200:
                return
        except urllib.error.URLError:
            time.sleep(0.25)
    raise AssertionError('Swiss Post container did not become healthy')
healthy()
assert request('/health') == (200, {'status': 'healthy'})
status, diagnostics = request('/diagnostics')
assert status == 200 and diagnostics.get('status') == 'ready'
assert token not in json.dumps(diagnostics) and 'dummy' not in json.dumps(diagnostics)
assert request('/v1/invoke', 'POST', {})[0] == 401
base = {'protocolVersion': 1, 'extensionPoint': 'shipping.provider.v1',
        'idempotencyKey': 'container-acceptance-unused', 'configuration': {}, 'payload': {}}
def invoke(operation, payload=None, configuration=None, key=None):
    value = {**base, 'operation': operation,
             'idempotencyKey': key or 'container-acceptance-unused',
             'configuration': configuration or {}, 'payload': payload or {}}
    return request('/v1/invoke', 'POST', value, True)
status, methods = invoke('listMethods', {'destination': {'countryCode': 'CH'}, 'currency': 'CHF'})
assert status == 200 and [m['methodId'] for m in methods['result']['methods']] == ['swiss-post-eco', 'swiss-post-pri']
config = {'shippingPricesChf': {'ECO': '5.90', 'PRI': '8.50'}}
status, quote = invoke('quote', {'destination': {'countryCode': 'CH'}, 'currency': 'CHF', 'methodId': 'swiss-post-eco'}, config)
assert status == 200 and quote['result'] == {'methodId': 'swiss-post-eco', 'amount': '5.90', 'currency': 'CHF', 'estimatedDelivery': 'Merchant configured'}
assert invoke('quote', {'destination': {'countryCode': 'CH'}, 'currency': 'EUR', 'methodId': 'swiss-post-eco'}, config)[0] == 400
key = '63dbe91a-0c97-4a22-9089-376af2c4df82'
label_config = {'clientId': 'dummy-client-id', 'clientSecret': 'dummy-client-secret',
                'frankingLicense': '1234', 'senderName': 'Artio Test', 'senderStreet': 'Teststrasse 1',
                'senderPostalCode': '8000', 'senderCity': 'Zurich'}
label_payload = {'methodId': 'swiss-post-eco', 'orderId': 'container-acceptance', 'parcelWeightKg': 0.5,
                 'destination': {'countryCode': 'CH', 'postalCode': '3000', 'locality': 'Bern',
                                 'addressLine1': 'Testgasse 2', 'recipientName': 'Test Recipient'}}
if mode == 'initial':
    status, result = invoke('createShipment', label_payload, label_config, key)
    assert status == 503 and 'dummy-client-secret' not in json.dumps(result)
    print('Swiss Post initial request failed closed with dummy credentials and no network')
elif mode == 'retry':
    status, result = invoke('createShipment', label_payload, label_config, key)
    assert status == 409 and 'uncertain outcome' in result.get('error', '').lower()
    print('Swiss Post persistent ledger blocked duplicate shipment creation after restart')
else:
    raise AssertionError('unknown smoke mode')
'@

try {
    Push-Location $root
    try {
        Invoke-Docker -Arguments @("build", "--file", "artio-plugins/swiss-post/Dockerfile", "--tag", $image, "artio-plugins/swiss-post")
        Invoke-Docker -Arguments @("volume", "create", $volume)
        Invoke-Docker -Arguments @("run", "--detach", "--name", $container, "--network", "none", "--read-only",
            "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true", "--pids-limit", "64", "--memory", "256m", "--cpus", "0.50",
            "--mount", "type=volume,src=$volume,dst=/data/state", "--env", "ARTIO_EXTENSION_TOKEN=local-container-acceptance-token", $image)
        $deadline = [DateTime]::UtcNow.AddSeconds(30)
        $running = $false
        do {
            $state = (& docker inspect --format '{{.State.Running}}' $container).Trim()
            if ($LASTEXITCODE -ne 0) { throw "Could not inspect $container" }
            if ($state -eq "true") { $running = $true; break }
            Start-Sleep -Milliseconds 500
        } while ([DateTime]::UtcNow -lt $deadline)
        if (-not $running) { throw "Swiss Post container did not start." }
        & docker exec $container python -c $smoke initial
        if ($LASTEXITCODE -ne 0) { throw "Initial Swiss Post container acceptance failed." }
        Invoke-Docker -Arguments @("restart", $container)
        & docker exec $container python -c $smoke retry
        if ($LASTEXITCODE -ne 0) { throw "Swiss Post restart/idempotency acceptance failed." }
    }
    finally { Pop-Location }
    Write-Host "Swiss Post plugin container acceptance passed without credentials or external network access. Persistent state survived restart; no live provider acceptance is claimed."
}
finally {
    & docker rm --force $container 2>$null | Out-Null
    & docker volume rm $volume 2>$null | Out-Null
    & docker image rm $image 2>$null | Out-Null
}
