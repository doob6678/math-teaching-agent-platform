param(
    [string]$BackendUrl = "http://127.0.0.1:8080",
    [string]$Username = "admin",
    [string]$Password = "admin-123456",
    [string]$SourceType = "local_path",
    [string]$Title = "teacher-resource-staged",
    [string]$OriginalUrl = "",
    [string]$LocalPath = "",
    [string]$PermissionScope = "MATH_VIP",
    [ValidateSet("TEXT", "AI")][string]$ParseMode = "TEXT",
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
        # Windows PowerShell otherwise serializes a String request body with the active code page. Resource paths and
        # teacher titles legitimately contain Chinese characters, so send explicit UTF-8 bytes for every JSON request
        # instead of letting Jackson receive invalid GBK/ANSI bytes.
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

$registerBody = @{
    sourceType = $SourceType
    title = $Title
    originalUrl = $OriginalUrl
    localPath = $LocalPath
    permissionScope = $PermissionScope
    parseMode = $ParseMode
    feishuExportFormat = $FeishuExportFormat
}
$document = Invoke-Json `
    -Method "Post" `
    -Uri "$base/api/teacher/resources" `
    -Headers $headers `
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
    $job = Invoke-Json `
        -Method "Post" `
        -Uri ($base + $syncPath) `
        -Headers $headers `
        -TimeoutSec 120
    $result["jobId"] = $job.jobId
    $result["jobStatus"] = $job.status
    $result["jobPhase"] = $job.phase

    if (-not $SkipExecute) {
        $executePath = "/api/teacher/resources/$($document.documentId)/sync-jobs/$($job.jobId)/execute"
        $executed = Invoke-Json `
            -Method "Post" `
            -Uri ($base + $executePath) `
            -Headers $headers `
            -TimeoutSec 900
        $result["executeStatus"] = $executed.status
        $result["executePhase"] = $executed.phase
        $result["executeMessage"] = $executed.message
    }
}

[pscustomobject]$result | ConvertTo-Json -Depth 10
