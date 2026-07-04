param(
    [string]$Distro = "Ubuntu",
    [int]$ListenPort = 3306,
    [int]$TargetPort = 3306,
    [string]$PythonPath = ""
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "wsl-proxy-common.ps1")
Start-WindowsToWslProxy `
    -Distro $Distro `
    -Name "mysql" `
    -ListenPort $ListenPort `
    -TargetPort $TargetPort `
    -PythonPath $PythonPath
