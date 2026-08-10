param(
    [string]$BackendUrl = "http://127.0.0.1:8080",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin-123456",
    # The caller selects an actual mathematics PDF. Its filename must not be present in any offline batch whitelist.
    [Parameter(Mandatory = $true)][string]$PdfPath,
    [int]$PollSeconds = 300
)

$ErrorActionPreference = "Stop"
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
Add-Type -AssemblyName System.Net.Http
$script:SessionCookie = ""
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$RunId = "independent-teacher-mcp-" + (Get-Date -Format "yyyyMMddTHHmmssZ")
$OutputRoot = Join-Path $Root "output\mcp-acceptance\$RunId"
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null

if (-not (Test-Path -LiteralPath $PdfPath -PathType Leaf)) {
    throw "Mathematics PDF was not found: $PdfPath"
}

function Get-Sha256Hex {
    param([byte[]]$Bytes)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return -join ($sha.ComputeHash($Bytes) | ForEach-Object { $_.ToString("x2") }) }
    finally { $sha.Dispose() }
}

function Invoke-Json {
    param([string]$Method, [string]$Path, [hashtable]$Headers = @{}, [object]$Body = $null, [int]$TimeoutSec = 180, [string]$SessionCookie = $script:SessionCookie)
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), ($BackendUrl.TrimEnd("/") + $Path))
    try {
        foreach ($header in $Headers.GetEnumerator()) { [void]$request.Headers.TryAddWithoutValidation($header.Key, [string]$header.Value) }
        if (-not [string]::IsNullOrWhiteSpace($SessionCookie)) { [void]$request.Headers.TryAddWithoutValidation("Cookie", $SessionCookie) }
        if ($null -ne $Body) { $request.Content = [System.Net.Http.StringContent]::new(($Body | ConvertTo-Json -Compress -Depth 30), [System.Text.Encoding]::UTF8, "application/json") }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        if ($response.Headers.Contains("Set-Cookie")) {
            $script:SessionCookie = (($response.Headers.GetValues("Set-Cookie") | ForEach-Object { ($_ -split ";")[0] }) -join "; ")
        }
        $bodyText = [System.Text.Encoding]::UTF8.GetString($response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult())
        if (-not $response.IsSuccessStatusCode) { throw "HTTP $([int]$response.StatusCode) for ${Path}: $bodyText" }
        return $bodyText | ConvertFrom-Json
    } finally { $request.Dispose(); $client.Dispose() }
}

function Invoke-Mcp {
    param([string]$Secret, [string]$Method, [object]$Params, [string]$Id, [bool]$AllowToolError = $false)
    $response = Invoke-Json "POST" "/api/mcp" @{ Authorization = "Bearer $Secret"; Accept = "application/json, text/event-stream"; "MCP-Protocol-Version" = "2025-11-25" } @{ jsonrpc = "2.0"; id = $Id; method = $Method; params = $Params } 240
    if ($null -ne $response.error -or (($response.result.isError -eq $true) -and (-not $AllowToolError))) { throw "MCP $Method failed: $($response | ConvertTo-Json -Compress -Depth 20)" }
    return $response
}

function Invoke-MultipartUpload {
    param([hashtable]$Headers, [string]$SessionCookie = $script:SessionCookie)
    $client = [System.Net.Http.HttpClient]::new(); $stream = [System.IO.File]::OpenRead($PdfPath); $content = [System.Net.Http.MultipartFormDataContent]::new()
    try {
        $content.Add([System.Net.Http.StringContent]::new("gaokao", [System.Text.Encoding]::UTF8), "sourceType")
        $content.Add([System.Net.Http.StringContent]::new("TEACHER_PRIVATE", [System.Text.Encoding]::UTF8), "permissionScope")
        $content.Add([System.Net.Http.StringContent]::new("TEXT", [System.Text.Encoding]::UTF8), "parseMode")
        $file = [System.Net.Http.StreamContent]::new($stream); $file.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/pdf")
        $content.Add($file, "files", [System.IO.Path]::GetFileName($PdfPath))
        $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, ($BackendUrl.TrimEnd("/") + "/api/teacher/resources/upload"))
        foreach ($header in $Headers.GetEnumerator()) { [void]$request.Headers.TryAddWithoutValidation($header.Key, $header.Value) }
        if (-not [string]::IsNullOrWhiteSpace($SessionCookie)) { [void]$request.Headers.TryAddWithoutValidation("Cookie", $SessionCookie) }
        $request.Content = $content
        $response = $client.SendAsync($request).GetAwaiter().GetResult(); $bodyText = [System.Text.Encoding]::UTF8.GetString($response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult())
        if (-not $response.IsSuccessStatusCode) { throw "Upload failed ($([int]$response.StatusCode)): $bodyText" }
        return $bodyText | ConvertFrom-Json
    } finally { $stream.Dispose(); $content.Dispose(); $client.Dispose() }
}

$suffix = [guid]::NewGuid().ToString("N").Substring(0, 12)
$ownerUsername = "teacher-owner-$suffix"; $otherUsername = "teacher-other-$suffix"; $teacherPassword = "Teacher-$suffix!A"
$pdfName = [System.IO.Path]::GetFileName($PdfPath); $pdfSize = (Get-Item -LiteralPath $PdfPath).Length; $pdfHash = (Get-FileHash -LiteralPath $PdfPath -Algorithm SHA256).Hash.ToLowerInvariant()
$offlineConfigPaths = @(
    (Join-Path $Root "config\gaokao-ingestion-2024.json"),
    (Join-Path $Root "config\math-paper-ingestion-liaoning-2026-05.json")
) | Where-Object { Test-Path -LiteralPath $_ }
$offlineSelectedFileNames = @($offlineConfigPaths | ForEach-Object {
    @((Get-Content -LiteralPath $_ -Raw -Encoding utf8 | ConvertFrom-Json).selectedFileNames)
})
$isSelectedByOfflineWhitelist = $offlineSelectedFileNames -contains $pdfName
if ($isSelectedByOfflineWhitelist) {
    throw "Independent upload acceptance requires a PDF absent from offline selectedFileNames: $pdfName"
}
$manifest = @("teacher-resource-upload-v1", "sourceType=gaokao", "title=", "permissionScope=teacher_private", "parseMode=text", "file=$($pdfName.ToLowerInvariant())`tapplication/pdf`t$pdfSize`t$pdfHash", "") -join "`n"

$adminLogin = Invoke-Json "POST" "/api/auth/login" @{} @{ username = $AdminUsername; password = $AdminPassword }
$adminCookie = $script:SessionCookie
$adminHeaders = @{ "X-Device-Id" = "independent-teacher-admin-$suffix" }
$ownerAccount = Invoke-Json "POST" "/api/auth/teachers" $adminHeaders @{ username = $ownerUsername; password = $teacherPassword; tenantId = "forged-tenant" }
$otherAccount = Invoke-Json "POST" "/api/auth/teachers" $adminHeaders @{ username = $otherUsername; password = $teacherPassword; tenantId = "forged-tenant" }
if ($ownerAccount.role -ne "teacher" -or $ownerAccount.tenantId -ne $adminLogin.tenantId -or ($ownerAccount.PSObject.Properties.Name -contains "password")) { throw "Teacher provisioning did not enforce safe role, tenant, and response fields." }

$ownerLogin = Invoke-Json "POST" "/api/auth/login" @{} @{ username = $ownerUsername; password = $teacherPassword } 180 $adminCookie
$ownerCookie = $script:SessionCookie
$ownerHeaders = @{ "X-Device-Id" = "independent-teacher-owner-$suffix" }
$document = Invoke-MultipartUpload $ownerHeaders $ownerCookie
$syncPath = "/api/teacher/resources/$($document.documentId)/sync-jobs"
$syncHeaders = $ownerHeaders
$job = Invoke-Json "POST" $syncPath $syncHeaders
$executePath = "$syncPath/$($job.jobId)/execute"
$executeHeaders = $ownerHeaders
[void](Invoke-Json "POST" $executePath $executeHeaders $null 180)
$deadline = (Get-Date).AddSeconds($PollSeconds)
do { Start-Sleep -Seconds 3; $latest = (Invoke-Json "GET" $syncPath $ownerHeaders | Where-Object { $_.jobId -eq $job.jobId } | Select-Object -First 1) } while ($latest.status -notin @("completed", "failed", "paused") -and (Get-Date) -lt $deadline)
if ($latest.status -ne "completed") { throw "Teacher upload sync failed: $($latest | ConvertTo-Json -Compress -Depth 12)" }

$ownerKey = Invoke-Json "POST" "/api/mcp/keys" $ownerHeaders; $ownerSecret = $ownerKey.secretKey
try {
    [void](Invoke-Mcp $ownerSecret "initialize" @{ protocolVersion = "2025-11-25"; capabilities = @{}; clientInfo = @{ name = "independent-teacher-acceptance"; version = "1.0" } } "owner-init")
    $ownerResources = (Invoke-Mcp $ownerSecret "tools/call" @{ name = "list_teacher_resources"; arguments = @{} } "owner-list").result.structuredContent
    $ownerBlocks = (Invoke-Mcp $ownerSecret "tools/call" @{ name = "read_teacher_resource_blocks"; arguments = @{ documentId = $document.documentId } } "owner-blocks").result.structuredContent
    $ownerSearch = (Invoke-Mcp $ownerSecret "tools/call" @{ name = "search_teacher_resource_evidence"; arguments = @{ query = "数学 函数 几何"; limit = 3; libraries = @("teacher_resource"); documentIds = @($document.documentId) } } "owner-search").result.structuredContent
    $ownerAudit = Invoke-Json "GET" ("/api/teacher/resources/search/audit/" + $ownerSearch.queryId) $ownerHeaders
    $otherLogin = Invoke-Json "POST" "/api/auth/login" @{} @{ username = $otherUsername; password = $teacherPassword }
    $otherCookie = $script:SessionCookie
    $otherHeaders = @{ "X-Device-Id" = "independent-teacher-other-$suffix" }
    $otherKey = Invoke-Json "POST" "/api/mcp/keys" $otherHeaders $null 180 $otherCookie; $otherSecret = $otherKey.secretKey
    try {
        [void](Invoke-Mcp $otherSecret "initialize" @{ protocolVersion = "2025-11-25"; capabilities = @{}; clientInfo = @{ name = "independent-teacher-isolation"; version = "1.0" } } "other-init")
        $otherResources = (Invoke-Mcp $otherSecret "tools/call" @{ name = "list_teacher_resources"; arguments = @{} } "other-list").result.structuredContent
        $otherRead = Invoke-Mcp $otherSecret "tools/call" @{ name = "read_teacher_resource_blocks"; arguments = @{ documentId = $document.documentId } } "other-read" $true
        $otherDenied = ($otherRead.result.isError -eq $true) -or ($otherRead.error.code -ne $null)
        if (-not $otherDenied) { throw "A different teacher MCP key unexpectedly read the private document." }
    } finally { try { [void](Invoke-Json "POST" "/api/mcp/keys/$($otherKey.keyId)/revoke" $otherHeaders $null 180 $otherCookie) } catch {} }
    $script:SessionCookie = $ownerCookie
    $ownerKeyState = Invoke-Json "GET" "/api/mcp/keys" $ownerHeaders | Where-Object { $_.keyId -eq $ownerKey.keyId } | Select-Object -First 1
    if ($ownerKey.ownerUserId -ne $ownerLogin.userId -or $ownerAudit.subjectId -ne $ownerLogin.userId -or [string]::IsNullOrWhiteSpace($ownerKeyState.lastUsedAt)) { throw "MCP owner binding or retrieval audit association failed." }
    $result = [ordered]@{
        runId = $RunId
        nonWhitelistUpload = @{ fileName = $pdfName; sha256 = $pdfHash; checkedOfflineConfigCount = $offlineConfigPaths.Count; selectedByOfflineWhitelist = $isSelectedByOfflineWhitelist; documentId = $document.documentId; syncStatus = $latest.status }
        provisioning = @{ owner = @{ userId = $ownerAccount.userId; role = $ownerAccount.role; tenantId = $ownerAccount.tenantId }; other = @{ userId = $otherAccount.userId; role = $otherAccount.role; tenantId = $otherAccount.tenantId }; responseExposesPassword = ($ownerAccount.PSObject.Properties.Name -contains "password") }
        ownerMcp = @{ keyId = $ownerKey.keyId; ownerUserId = $ownerKey.ownerUserId; visibleResourceCount = @($ownerResources).Count; parsedBlockCount = @($ownerBlocks).Count; searchQueryId = $ownerSearch.queryId; searchHitCount = $ownerSearch.hitCount; auditSubjectId = $ownerAudit.subjectId; keyLastUsedAt = $ownerKeyState.lastUsedAt }
        isolation = @{ otherTeacherUserId = $otherAccount.userId; privateDocumentReadDenied = $otherDenied; otherVisibleResourceCount = @($otherResources).Count }
    }
    $result | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $OutputRoot "acceptance-summary.json") -Encoding utf8
    $ownerSearch | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $OutputRoot "owner-resource-search.json") -Encoding utf8
    $result | ConvertTo-Json -Depth 20
} finally { try { [void](Invoke-Json "POST" "/api/mcp/keys/$($ownerKey.keyId)/revoke" $ownerHeaders) } catch {} }
