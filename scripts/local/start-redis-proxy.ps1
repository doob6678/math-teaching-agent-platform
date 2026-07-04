param(
    [string]$Distro = "Ubuntu",
    [int]$ListenPort = 6379,
    [int]$TargetPort = 6379,
    [string]$PythonPath = ""
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "wsl-proxy-common.ps1")
Start-WindowsToWslProxy `
    -Distro $Distro `
    -Name "redis" `
    -ListenPort $ListenPort `
    -TargetPort $TargetPort `
    -PythonPath $PythonPath
