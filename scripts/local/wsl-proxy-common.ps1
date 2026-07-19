function Resolve-LocalPython {
    param([string]$RequestedPython)
    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($RequestedPython)) {
        $candidates += $RequestedPython
    }
    $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if ($pythonCommand) {
        $candidates += $pythonCommand.Source
    }
    $candidates += @(
        "D:\conda\envs\py_12\python.exe",
        "C:\Users\doob\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
    )
    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }
    throw "No Python executable found for TCP proxy"
}

function Resolve-WslHost {
    param([string]$Distro)
    $ip = (wsl -d $Distro -- bash -lc "hostname -I | tr ' ' '\n' | head -n1").Trim()
    if ([string]::IsNullOrWhiteSpace($ip)) {
        throw "Unable to resolve WSL IP for distro $Distro"
    }
    return $ip
}

function Test-TcpPort {
    param(
        [string]$HostName,
        [int]$Port
    )
    try {
        $client = [System.Net.Sockets.TcpClient]::new()
        $connect = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $connect.AsyncWaitHandle.WaitOne(1000)) {
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

function Start-WindowsToWslProxy {
    param(
        [string]$Distro,
        [string]$Name,
        [int]$ListenPort,
        [int]$TargetPort,
        [string]$PythonPath
    )
    if (Test-TcpPort "127.0.0.1" $ListenPort) {
        Write-Host "$Name proxy already listening on 127.0.0.1:$ListenPort"
        return
    }
    $root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
    $python = Resolve-LocalPython $PythonPath
    $targetHost = Resolve-WslHost $Distro
    $proxyScript = Join-Path $PSScriptRoot "tcp-proxy.py"
    if (-not (Test-Path $proxyScript)) {
        throw "TCP proxy script is missing: $proxyScript"
    }
    Write-Host "$Name proxy listening 127.0.0.1:$ListenPort -> ${targetHost}:$TargetPort"
    & $python $proxyScript `
        --listen-host "127.0.0.1" `
        --listen-port $ListenPort `
        --target-host $targetHost `
        --target-port $TargetPort
}
