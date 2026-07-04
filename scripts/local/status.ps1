param(
    [string]$BackendUrl = "http://127.0.0.1:8080",
    [string]$WorkerUrl = "http://127.0.0.1:8091",
    [string]$Username = "admin",
    [string]$Password = "admin-123456",
    [string]$Distro = "Ubuntu"
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")

function Test-TcpPort {
    param(
        [string]$HostName,
        [int]$Port
    )
    try {
        $client = [System.Net.Sockets.TcpClient]::new()
        $connect = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $connect.AsyncWaitHandle.WaitOne(1500)) {
            $client.Close()
            return $false
        }
        $client.EndConnect($connect)
        $client.Close()
        return $true
    } catch {
        return $false
    }
}

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Uri,
        [hashtable]$Headers = @{},
        [object]$Body = $null,
        [int]$TimeoutSec = 10
    )
    $parameters = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
        TimeoutSec = $TimeoutSec
    }
    if ($null -ne $Body) {
        $parameters["ContentType"] = "application/json"
        $parameters["Body"] = ($Body | ConvertTo-Json -Depth 20 -Compress)
    }
    Invoke-RestMethod @parameters
}

function Worker-Key {
    if (-not [string]::IsNullOrWhiteSpace($env:MATH_AGENT_WORKER_API_KEY)) {
        return $env:MATH_AGENT_WORKER_API_KEY
    }
    $keyPath = Join-Path $Root ".local-secrets\worker-api-key.txt"
    if (Test-Path $keyPath) {
        return (Get-Content -LiteralPath $keyPath -Raw).Trim()
    }
    return ""
}

$appPorts = [ordered]@{
    frontend = 5173
    backend = 8080
    worker = 8091
}

$proxyPorts = [ordered]@{
    mysql_proxy = 13306
    redis_proxy = 16379
    milvus_proxy = 19531
}

function Resolve-WslHost {
    param([string]$LinuxDistro)
    try {
        $ip = (wsl -d $LinuxDistro -- bash -lc "hostname -I | tr ' ' '\n' | head -n1").Trim()
        if ([string]::IsNullOrWhiteSpace($ip)) {
            return $null
        }
        return $ip
    } catch {
        return $null
    }
}

$portStatus = [ordered]@{}
foreach ($entry in $appPorts.GetEnumerator()) {
    $portStatus[$entry.Key] = Test-TcpPort "127.0.0.1" $entry.Value
}

$wslHost = Resolve-WslHost $Distro
$proxyRoutes = [ordered]@{}
foreach ($entry in $proxyPorts.GetEnumerator()) {
    $localhostReachable = Test-TcpPort "127.0.0.1" $entry.Value
    $wslReachable = $false
    if (-not [string]::IsNullOrWhiteSpace($wslHost)) {
        $wslReachable = Test-TcpPort $wslHost $entry.Value
    }
    $portStatus[$entry.Key] = $localhostReachable -or $wslReachable
    $proxyRoutes[$entry.Key] = [pscustomobject]@{
        port = $entry.Value
        localhostReachable = $localhostReachable
        wslHost = $wslHost
        wslReachable = $wslReachable
        effectiveReachable = $portStatus[$entry.Key]
    }
}

$workerStatus = $null
$workerKey = Worker-Key
if (-not [string]::IsNullOrWhiteSpace($workerKey) -and $portStatus.worker) {
    try {
        $workerStatus = Invoke-Json `
            -Method "Get" `
            -Uri ($WorkerUrl.TrimEnd("/") + "/v1/capabilities") `
            -Headers @{ "x-worker-api-key" = $workerKey }
    } catch {
        $workerStatus = @{ status = "error"; message = $_.Exception.Message }
    }
}

$runtimeStatus = $null
if ($portStatus.backend -and -not [string]::IsNullOrWhiteSpace($Username) -and -not [string]::IsNullOrWhiteSpace($Password)) {
    try {
        $base = $BackendUrl.TrimEnd("/")
        $login = Invoke-Json `
            -Method "Post" `
            -Uri "$base/api/auth/login" `
            -Body @{ username = $Username; password = $Password }
        $headers = @{}
        $headers[$login.tokenName] = $login.tokenValue
        $runtimeStatus = Invoke-Json -Method "Get" -Uri "$base/api/system/runtime" -Headers $headers
    } catch {
        $runtimeStatus = @{ status = "error"; message = $_.Exception.Message }
    }
}

[pscustomobject]@{
    ports = $portStatus
    proxyRoutes = $proxyRoutes
    worker = $workerStatus
    runtime = $runtimeStatus
} | ConvertTo-Json -Depth 12
