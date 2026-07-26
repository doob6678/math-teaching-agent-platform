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
        # Keep JSON payloads UTF-8 on Windows so non-ASCII resource titles and paths parse identically.
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
    $response = Invoke-Json `
        -Method "Post" `
        -Uri ($base + $rebuildPath) `
        -Headers $headers
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
