param(
    [string]$BackendUrl = "http://127.0.0.1:8080",
    [string]$Username = "admin",
    [string]$Password = "admin-123456",
    [string]$SourceType = "local_path",
    [string]$Title = "teacher-resource-staged",
    [string]$LocalPath = "",
    [string]$PermissionScope = "MATH_VIP",
    [string]$FeishuExportFormat = "md",
    [switch]$SkipSync,
    [switch]$SkipExecute
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")

function Resolve-DefaultLocalPath {
    $pdf = Get-ChildItem -LiteralPath $Root -Recurse -File -Filter *.pdf |
        Where-Object { $_.Length -ge 100KB } |
        Sort-Object Length |
        Select-Object -First 1
    if ($null -eq $pdf) {
        throw "No default local PDF candidate was found under the workspace. Pass -LocalPath explicitly."
    }
    $stagingDir = Join-Path $Root ".local-storage\seed-resources"
    New-Item -ItemType Directory -Force -Path $stagingDir | Out-Null
    $stagedPath = Join-Path $stagingDir "teacher-resource-staged$($pdf.Extension.ToLowerInvariant())"
    Copy-Item -LiteralPath $pdf.FullName -Destination $stagedPath -Force
    return $stagedPath
}

if ([string]::IsNullOrWhiteSpace($LocalPath)) {
    $LocalPath = Resolve-DefaultLocalPath
}

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

$registerBody = @{
    sourceType = $SourceType
    title = $Title
    localPath = $LocalPath
    permissionScope = $PermissionScope
    feishuExportFormat = $FeishuExportFormat
}
$registerJson = $registerBody | ConvertTo-Json -Depth 20 -Compress
$registerCapability = Apply-Capability `
    -Action "teacher-resource:register" `
    -Path "/api/teacher/resources" `
    -Body $registerJson `
    -IdempotencyKey ("teacher-resource-register:" + $Title + ":" + (Get-Date -Format yyyyMMddHHmmss)) `
    -Headers $headers

$registerHeaders = @{} + $headers
$registerHeaders["X-Capability-Token"] = $registerCapability.token
$registerHeaders["X-Request-Hash"] = $registerCapability.requestHash

$document = Invoke-Json `
    -Method "Post" `
    -Uri "$base/api/teacher/resources" `
    -Headers $registerHeaders `
    -Body $registerBody `
    -TimeoutSec 120

$result = [ordered]@{
    tenantId = $login.tenantId
    documentId = $document.documentId
    title = $document.title
    sourceType = $document.sourceType
    localPath = $document.localPath
    syncStatus = $document.syncStatus
    parseStatus = $document.parseStatus
    embeddingStatus = $document.embeddingStatus
    indexStatus = $document.indexStatus
}

if (-not $SkipSync) {
    $syncPath = "/api/teacher/resources/$($document.documentId)/sync-jobs"
    $syncCapability = Apply-Capability `
        -Action "teacher-resource:sync" `
        -Path $syncPath `
        -Body "" `
        -IdempotencyKey ("teacher-resource-sync:" + $($document.documentId) + ":" + (Get-Date -Format yyyyMMddHHmmss)) `
        -Headers $headers
    $syncHeaders = @{} + $headers
    $syncHeaders["X-Capability-Token"] = $syncCapability.token
    $syncHeaders["X-Request-Hash"] = $syncCapability.requestHash
    $job = Invoke-Json `
        -Method "Post" `
        -Uri ($base + $syncPath) `
        -Headers $syncHeaders `
        -TimeoutSec 120
    $result["jobId"] = $job.jobId
    $result["jobStatus"] = $job.status
    $result["jobPhase"] = $job.phase

    if (-not $SkipExecute) {
        $executePath = "/api/teacher/resources/$($document.documentId)/sync-jobs/$($job.jobId)/execute"
        $executeCapability = Apply-Capability `
            -Action "teacher-resource:sync-execute" `
            -Path $executePath `
            -Body "" `
            -IdempotencyKey ("teacher-resource-sync-execute:" + $($job.jobId) + ":" + (Get-Date -Format yyyyMMddHHmmss)) `
            -Headers $headers
        $executeHeaders = @{} + $headers
        $executeHeaders["X-Capability-Token"] = $executeCapability.token
        $executeHeaders["X-Request-Hash"] = $executeCapability.requestHash
        $executed = Invoke-Json `
            -Method "Post" `
            -Uri ($base + $executePath) `
            -Headers $executeHeaders `
            -TimeoutSec 900
        $result["executeStatus"] = $executed.status
        $result["executePhase"] = $executed.phase
        $result["executeMessage"] = $executed.message
    }
}

[pscustomobject]$result | ConvertTo-Json -Depth 10
