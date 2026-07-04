param(
    [string]$Distro = "Ubuntu",
    [int]$ListenPort = 19530,
    [int]$TargetPort = 19530,
    [string]$PythonPath = ""
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "wsl-proxy-common.ps1")
Start-WindowsToWslProxy `
    -Distro $Distro `
    -Name "milvus" `
    -ListenPort $ListenPort `
    -TargetPort $TargetPort `
    -PythonPath $PythonPath
