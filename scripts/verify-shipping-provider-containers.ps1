$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$image = "artio/swiss-post:$suffix"
$container = "artio-shipping-swiss-post-$suffix"
$imageBuilt = $false
$containerCreated = $false

$smoke = @'
import json, os, time, urllib.error, urllib.request, uuid
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
    raise AssertionError('shipping plugin did not become healthy')
assert request('/v1/invoke', 'POST', {}, False)[0] == 401
def invoke(operation, payload):
    envelope = {'protocolVersion': 1, 'extensionPoint': 'shipping.provider.v1', 'operation': operation,
                'idempotencyKey': str(uuid.uuid4()), 'configuration': {'shippingPricesChf': {'ECO': '5.90', 'PRI': '8.50'}}, 'payload': payload}
    return request('/v1/invoke', 'POST', envelope, True)
status, methods = invoke('listMethods', {'destination': {'countryCode': 'CH'}, 'currency': 'CHF'})
assert status == 200
assert {entry['methodId'] for entry in methods['result']['methods']} == {'swiss-post-eco', 'swiss-post-pri'}
status, methods = invoke('listMethods', {'destination': {'countryCode': 'DE'}, 'currency': 'EUR'})
assert status == 200 and methods['result']['methods'] == []
status, quote = invoke('quote', {'methodId': 'swiss-post-pri', 'destination': {'countryCode': 'CH'}, 'currency': 'CHF'})
assert status == 200 and quote['result'] == {'methodId': 'swiss-post-pri', 'amount': '8.50', 'currency': 'CHF', 'estimatedDelivery': 'Merchant configured'}
print('Swiss Post container passed health, auth, method discovery and bounded quote contract without credentials or external network access')
'@

try {
    Push-Location $root
    try {
        python -m unittest discover -s 'artio-plugins/swiss-post' -p 'test_*.py' -v
        if ($LASTEXITCODE -ne 0) { throw 'Swiss Post package tests failed.' }
        docker build --file 'artio-plugins/swiss-post/Dockerfile' --tag $image 'artio-plugins/swiss-post'
        if ($LASTEXITCODE -ne 0) { throw 'Could not build the Swiss Post test image.' }
        $imageBuilt = $true
        docker run --detach --name $container --network none --read-only --cap-drop ALL --security-opt 'no-new-privileges:true' `
            --pids-limit 64 --memory 256m --cpus 0.50 --env 'ARTIO_EXTENSION_TOKEN=local-container-acceptance-token' $image | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not start the Swiss Post test container.' }
        $containerCreated = $true
        docker exec $container python -c $smoke
        if ($LASTEXITCODE -ne 0) { throw 'Live Swiss Post container acceptance failed.' }
    }
    finally { Pop-Location }
    Write-Host 'Swiss Post container acceptance passed. No provider credentials or external network access were used.'
}
finally {
    if ($containerCreated) {
        docker rm --force $container | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove acceptance container $container" }
    }
    if ($imageBuilt) {
        docker image rm $image | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove acceptance image $image" }
    }
}
