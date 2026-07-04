param(
    [string]$BackendUrl = "http://127.0.0.1:8080",
    [string]$Username = "admin",
    [string]$Password = "admin-123456",
    [Parameter(Mandatory = $true)]
    [string]$DocumentId
)

$ErrorActionPreference = "Stop"

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Uri,
        [hashtable]$Headers = @{},
        [object]$Body = $null,
        [int]$TimeoutSec = 60
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

function Get-RequestHash {
    param([string]$Body)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
        $hash = $sha.ComputeHash($bytes)
        return "sha256:" + ([System.BitConverter]::ToString($hash).Replace("-", "").ToLowerInvariant())
    } finally {
        $sha.Dispose()
    }
}

$base = $BackendUrl.TrimEnd("/")
$login = Invoke-Json `
    -Method "Post" `
    -Uri "$base/api/auth/login" `
    -Body @{ username = $Username; password = $Password }

$headers = @{}
$headers[$login.tokenName] = $login.tokenValue

$path = "/api/teacher/resources/$DocumentId"
$requestHash = Get-RequestHash ""
$capability = Invoke-Json `
    -Method "Post" `
    -Uri "$base/api/security/capabilities" `
    -Headers $headers `
    -Body @{
        action = "teacher-resource:archive"
        path = $path
        requestHash = $requestHash
        idempotencyKey = "teacher-resource-archive:${DocumentId}:$(Get-Date -Format yyyyMMddHHmmss)"
        maxCost = 1
    }

$archiveHeaders = @{} + $headers
$archiveHeaders["X-Capability-Token"] = $capability.token
$archiveHeaders["X-Request-Hash"] = $requestHash

$response = Invoke-Json `
    -Method "Delete" `
    -Uri ($base + $path) `
    -Headers $archiveHeaders `
    -TimeoutSec 120

$response | ConvertTo-Json -Depth 10
