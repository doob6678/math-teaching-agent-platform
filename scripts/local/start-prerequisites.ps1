param(
    [string]$Distro = "Ubuntu",
    [string]$DatabaseName = "math_agent",
    [string]$DatabaseUser = "math_agent",
    [string]$DatabasePassword = "123456",
    [switch]$SkipWindowsPortCheck
)

$ErrorActionPreference = "Stop"

function Invoke-WslChecked {
    param([string]$Command)
    wsl -d $Distro -- bash -lc $Command
    if ($LASTEXITCODE -ne 0) {
        throw "WSL command failed: $Command"
    }
}

function Test-WslCommand {
    param([string]$Command)
    wsl -d $Distro -- bash -lc $Command
    return $LASTEXITCODE -eq 0
}

function Test-TcpPort {
    param(
        [string]$HostName,
        [int]$Port
    )
    try {
        $client = [System.Net.Sockets.TcpClient]::new()
        $connect = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $connect.AsyncWaitHandle.WaitOne(3000)) {
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

Write-Host "Starting WSL distro: $Distro"
wsl -d $Distro -- true
if ($LASTEXITCODE -ne 0) {
    throw "WSL distro '$Distro' is not available"
}

Write-Host "Checking Redis on 127.0.0.1:6379"
if (-not (Test-WslCommand "redis-cli -h 127.0.0.1 -p 6379 ping | grep -q PONG")) {
    if (Test-WslCommand "sudo -n service redis-server start >/dev/null 2>&1") {
        Invoke-WslChecked "redis-cli -h 127.0.0.1 -p 6379 ping | grep -q PONG"
    } else {
        throw "Redis is not reachable and cannot be started non-interactively in WSL"
    }
}

Write-Host "Checking MySQL on 127.0.0.1:3306"
if (-not (Test-WslCommand "timeout 5 mysqladmin ping -h127.0.0.1 -P3306 -u$DatabaseUser -p'$DatabasePassword' >/dev/null 2>&1")) {
    $rootPassword = [Environment]::GetEnvironmentVariable("MATH_AGENT_DB_ROOT_PASSWORD")
    if ([string]::IsNullOrWhiteSpace($rootPassword)) {
        throw "MySQL application user is not ready. Set MATH_AGENT_DB_ROOT_PASSWORD, then rerun this script to create $DatabaseUser."
    }
    $sql = "CREATE DATABASE IF NOT EXISTS $DatabaseName CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE USER IF NOT EXISTS '$DatabaseUser'@'%' IDENTIFIED BY '$DatabasePassword'; CREATE USER IF NOT EXISTS '$DatabaseUser'@'localhost' IDENTIFIED BY '$DatabasePassword'; GRANT ALL PRIVILEGES ON $DatabaseName.* TO '$DatabaseUser'@'%'; GRANT ALL PRIVILEGES ON $DatabaseName.* TO '$DatabaseUser'@'localhost'; FLUSH PRIVILEGES;"
    Invoke-WslChecked "mysql -h127.0.0.1 -P3306 -uroot -p'$rootPassword' -e \"$sql\""
    Invoke-WslChecked "timeout 5 mysqladmin ping -h127.0.0.1 -P3306 -u$DatabaseUser -p'$DatabasePassword' >/dev/null"
}

Write-Host "Checking Milvus Docker containers"
Invoke-WslChecked "docker start milvus-etcd milvus-minio milvus-standalone >/dev/null"
Invoke-WslChecked "timeout 60 bash -lc 'until docker inspect -f ""{{.State.Health.Status}}"" milvus-standalone 2>/dev/null | grep -q healthy; do sleep 2; done'"

if (-not $SkipWindowsPortCheck) {
    Write-Host "Checking Windows-side service ports"
    $missingPorts = @()
    if (-not (Test-TcpPort "127.0.0.1" 3306)) {
        $missingPorts += "MySQL 127.0.0.1:3306"
    }
    if (-not (Test-TcpPort "127.0.0.1" 6379)) {
        $missingPorts += "Redis 127.0.0.1:6379"
    }
    if (-not (Test-TcpPort "127.0.0.1" 19530)) {
        $missingPorts += "Milvus 127.0.0.1:19530"
    }
    if ($missingPorts.Count -gt 0) {
        throw "WSL services started, but Windows cannot reach: $($missingPorts -join ', '). Fix WSL localhost forwarding or run the matching service on Windows before starting backend."
    }
}

Write-Host "Prerequisites ready: MySQL, Redis, Milvus"
