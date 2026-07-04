param(
    [string]$Distro = "Ubuntu",
    [int]$MysqlListenPort = 13306,
    [int]$RedisListenPort = 16379,
    [int]$MilvusListenPort = 19531
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$ProxyScriptWindows = Join-Path $Root "scripts\local\tcp-proxy.py"
$ProxyScriptForWslPath = $ProxyScriptWindows -replace "\\", "/"
$ProxyScriptWsl = (wsl -d $Distro -- wslpath -a "$ProxyScriptForWslPath").Trim()

function Start-WslProxy {
    param(
        [string]$Name,
        [int]$ListenPort,
        [int]$TargetPort
    )
    $command = "set -e; if ss -ltn | grep -q ':$ListenPort '; then echo '$Name proxy already listening on $ListenPort'; else nohup python3 '$ProxyScriptWsl' --listen-host 0.0.0.0 --listen-port $ListenPort --target-host 127.0.0.1 --target-port $TargetPort > /tmp/math-agent-$Name-proxy.log 2>&1 & sleep 1; ss -ltn | grep ':$ListenPort'; fi"
    wsl -d $Distro -- bash -lc $command
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to start $Name WSL proxy"
    }
}

Start-WslProxy "mysql" $MysqlListenPort 3306
Start-WslProxy "redis" $RedisListenPort 6379
Start-WslProxy "milvus" $MilvusListenPort 19530

$WslIp = (wsl -d $Distro -- bash -lc "hostname -I | tr ' ' '\n' | head -n1").Trim()
Write-Host "WSL proxy host: $WslIp"
Write-Host "MySQL:  $WslIp`:$MysqlListenPort -> 127.0.0.1:3306"
Write-Host "Redis:  $WslIp`:$RedisListenPort -> 127.0.0.1:6379"
Write-Host "Milvus: $WslIp`:$MilvusListenPort -> 127.0.0.1:19530"
