param(
    [string]$BackendUrl = "http://127.0.0.1:8080",
    [string]$Username = "admin",
    [string]$Password = "admin-123456",
    [Parameter(Mandatory = $true)]
    [string]$DocumentId
)

$ErrorActionPreference = "Stop"
$script:WebSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Uri,
        [hashtable]$Headers = @{},
        [object]$Body = $null,
        [int]$TimeoutSec = 60,
        [object]$WebSession = $script:WebSession
    )
    $parameters = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
        TimeoutSec = $TimeoutSec
        WebSession = $WebSession
    }
    if ($null -ne $Body) {
        # Preserve UTF-8 for resource metadata on Windows PowerShell.
        $json = $Body | ConvertTo-Json -Depth 20 -Compress
        $parameters["ContentType"] = "application/json; charset=utf-8"
        $parameters["Body"] = [System.Text.Encoding]::UTF8.GetBytes($json)
    }
    Invoke-RestMethod @parameters
}

$base = $BackendUrl.TrimEnd("/")
$login = Invoke-Json `
    -Method "Post" `
    -Uri "$base/api/auth/login" `
    -Body @{ username = $Username; password = $Password }

$path = "/api/teacher/resources/$DocumentId"
$response = Invoke-Json `
    -Method "Delete" `
    -Uri ($base + $path) `
    -TimeoutSec 120

$response | ConvertTo-Json -Depth 10
