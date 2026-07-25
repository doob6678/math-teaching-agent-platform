param(
    [string]$Distro = "Ubuntu",
    [string]$DatabaseName = "math_agent",
    [string]$DatabaseUser = "math_agent",
    [string]$DatabasePassword = "123456",
    [int]$WorkerPort = 8091,
    [switch]$SkipPrerequisites
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$LogDir = Join-Path $Root "output\local-services"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

function Start-LocalService {
    param(
        [string]$Name,
        [string]$ScriptPath,
        [string[]]$Arguments
    )
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $stdout = Join-Path $LogDir "$Name-$timestamp.out.log"
    $stderr = Join-Path $LogDir "$Name-$timestamp.err.log"
    $argumentList = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", $ScriptPath
    ) + $Arguments
    $process = Start-Process -FilePath "powershell.exe" `
        -ArgumentList $argumentList `
        -WorkingDirectory $Root `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -PassThru
    Write-Host "$Name started: pid=$($process.Id) stdout=$stdout stderr=$stderr"
}

function Ensure-LocalService {
    param(
        [string]$Name,
        [string]$ScriptPath,
        [string[]]$Arguments,
        [int[]]$Ports
    )
    foreach ($port in $Ports) {
        if (Test-TcpPort "127.0.0.1" $port) {
            Write-Host "$Name already listening on 127.0.0.1:$port; skip duplicate start."
            return
        }
    }
    Start-LocalService $Name $ScriptPath $Arguments
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

if (-not $SkipPrerequisites) {
    & (Join-Path $PSScriptRoot "start-prerequisites.ps1") `
        -Distro $Distro `
        -DatabaseName $DatabaseName `
        -DatabaseUser $DatabaseUser `
        -DatabasePassword $DatabasePassword `
        -SkipWindowsPortCheck
}

if (-not ((Test-TcpPort "127.0.0.1" 3306) -and (Test-TcpPort "127.0.0.1" 6379) -and (Test-TcpPort "127.0.0.1" 19530))) {
    & (Join-Path $PSScriptRoot "start-wsl-service-proxies.ps1") -Distro $Distro
}

Ensure-LocalService "ai-worker" (Join-Path $PSScriptRoot "start-worker.ps1") @("-Port", "$WorkerPort") @($WorkerPort)
Ensure-LocalService "backend" (Join-Path $PSScriptRoot "start-backend.ps1") @(
    "-DbUser", $DatabaseUser,
    "-DbPassword", $DatabasePassword,
    "-WorkerPort", "$WorkerPort"
) @(8080)
Ensure-LocalService "frontend" (Join-Path $PSScriptRoot "start-frontend.ps1") @() @(5173, 5174)

Write-Host "Local stack launch requested. Check /api/system/runtime after backend starts."
