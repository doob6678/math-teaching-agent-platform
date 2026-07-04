param(
    [string]$BackendUrl = "http://127.0.0.1:8080",
    [string]$Username = "teacher",
    [string]$Password = "teacher-123456",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Uri,
        [hashtable]$Headers = @{},
        [object]$Body = $null
    )
    $parameters = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
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

function Apply-Capability {
    param(
        [string]$Action,
        [string]$Path,
        [string]$Body,
        [string]$IdempotencyKey,
        [hashtable]$Headers
    )
    $requestHash = Get-RequestHash $Body
    $capability = Invoke-Json `
        -Method "Post" `
        -Uri ($BackendUrl.TrimEnd("/") + "/api/security/capabilities") `
        -Headers $Headers `
        -Body @{
            action = $Action
            path = $Path
            requestHash = $requestHash
            idempotencyKey = $IdempotencyKey
            maxCost = 1
        }
    return @{
        token = $capability.token
        requestHash = $requestHash
    }
}

$base = $BackendUrl.TrimEnd("/")
$login = Invoke-Json `
    -Method "Post" `
    -Uri "$base/api/auth/login" `
    -Body @{ username = $Username; password = $Password }

$headers = @{}
$headers[$login.tokenName] = $login.tokenValue

$runtime = Invoke-Json -Method "Get" -Uri "$base/api/system/runtime" -Headers $headers
if (-not $runtime.vectorIndex.enabled -or -not $runtime.vectorIndex.configured) {
    throw "Vector index is not configured. Refusing to start rebuild."
}
if ($runtime.vectorIndex.dimension -ne 512) {
    throw "Vector index dimension is $($runtime.vectorIndex.dimension), expected 512."
}

$resources = Invoke-Json -Method "Get" -Uri "$base/api/teacher/resources" -Headers $headers
$targets = @($resources | Where-Object {
        $_.parseStatus -eq "parsed" -and $_.syncStatus -eq "synced"
    })

if ($targets.Count -eq 0) {
    Write-Output "No parsed teacher resources are ready for vector rebuild."
    exit 0
}

$results = @()
foreach ($resource in $targets) {
    if ($DryRun) {
        $results += [pscustomobject]@{
            documentId = $resource.documentId
            title = $resource.title
            action = "would_rebuild"
        }
        continue
    }
    $rebuildPath = "/api/vector-index/teacher-resources/$([Uri]::EscapeDataString($resource.documentId))/rebuild"
    $capability = Apply-Capability `
        -Action "vector-index:rebuild" `
        -Path $rebuildPath `
        -Body "" `
        -IdempotencyKey ("vector-index-rebuild:" + $resource.documentId + ":" + (Get-Date -Format yyyyMMddHHmmss)) `
        -Headers $headers
    $rebuildHeaders = @{} + $headers
    $rebuildHeaders["X-Capability-Token"] = $capability.token
    $rebuildHeaders["X-Request-Hash"] = $capability.requestHash
    $response = Invoke-Json `
        -Method "Post" `
        -Uri ($base + $rebuildPath) `
        -Headers $rebuildHeaders
    $results += [pscustomobject]@{
        documentId = $resource.documentId
        title = $resource.title
        status = $response.status
        blockCount = $response.blockCount
        embeddedCount = $response.embeddedCount
        vectorCount = $response.embeddedCount
        upsertedCount = $response.upsertedCount
        embeddingModel = $response.embeddingModel
        promptTokens = $response.promptTokens
        message = $response.message
    }
}

$results | ConvertTo-Json -Depth 10
