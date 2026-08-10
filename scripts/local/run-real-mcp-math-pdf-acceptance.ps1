param(
    [string]$BackendUrl = "http://127.0.0.1:8080",
    [string]$Username = "admin",
    [string]$Password = "admin-123456",
    # The caller supplies this filesystem path so Windows PowerShell never has to decode a hard-coded non-ASCII path.
    [string]$PdfPath = "",
    [ValidateSet("TEXT", "MARKDOWN_ASSETS", "AI")][string]$ParseMode = "TEXT",
    [int]$PollSeconds = 300
)

$ErrorActionPreference = "Stop"
$script:WebSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
# Windows PowerShell 5.1 does not always preload this assembly, while PowerShell 7 does. Load it explicitly so the
# same real multipart upload client works on the Windows host required by the production acceptance flow.
Add-Type -AssemblyName System.Net.Http
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$RunId = "mcp-math-pdf-" + (Get-Date -Format "yyyyMMddTHHmmssZ")
$OutputRoot = Join-Path $Root "output\mcp-acceptance\$RunId"
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null

if (-not (Test-Path -LiteralPath $PdfPath -PathType Leaf)) {
    throw "Mathematics PDF was not found: $PdfPath"
}

function Get-Sha256Hex {
    param([byte[]]$Bytes)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return -join ($sha.ComputeHash($Bytes) | ForEach-Object { $_.ToString("x2") })
    } finally {
        $sha.Dispose()
    }
}

function Get-FileSha256Hex {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-SessionCookie {
    param([object]$WebSession = $script:WebSession)
    return (($WebSession.Cookies.GetCookies($BackendUrl) | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join "; ")
}

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Path,
        [hashtable]$Headers = @{},
        [object]$Body = $null,
        [int]$TimeoutSec = 120,
        [object]$WebSession = $script:WebSession
    )
    $parameters = @{ Method = $Method; Uri = ($BackendUrl.TrimEnd("/") + $Path); Headers = $Headers; TimeoutSec = $TimeoutSec; WebSession = $WebSession }
    if ($null -ne $Body) {
        $parameters["ContentType"] = "application/json; charset=utf-8"
        $parameters["Body"] = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Compress -Depth 20))
    }
    return Invoke-RestMethod @parameters
}

function Invoke-MultipartUpload {
    param([hashtable]$Headers, [object]$WebSession = $script:WebSession)
    $client = [System.Net.Http.HttpClient]::new()
    $content = [System.Net.Http.MultipartFormDataContent]::new()
    $stream = [System.IO.File]::OpenRead($PdfPath)
    try {
        foreach ($field in @{
            sourceType = "mock_exam"; permissionScope = "MATH_VIP"; parseMode = $ParseMode
        }.GetEnumerator()) {
            $content.Add([System.Net.Http.StringContent]::new($field.Value, [System.Text.Encoding]::UTF8), $field.Key)
        }
        $fileContent = [System.Net.Http.StreamContent]::new($stream)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/pdf")
        $content.Add($fileContent, "files", [System.IO.Path]::GetFileName($PdfPath))
        $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, ($BackendUrl.TrimEnd("/") + "/api/teacher/resources/upload"))
        foreach ($header in $Headers.GetEnumerator()) { [void]$request.Headers.TryAddWithoutValidation($header.Key, $header.Value) }
        $cookie = Get-SessionCookie $WebSession
        if (-not [string]::IsNullOrWhiteSpace($cookie)) { [void]$request.Headers.TryAddWithoutValidation("Cookie", $cookie) }
        $request.Content = $content
        # .NET Framework's HttpClient (Windows PowerShell 5.1) exposes SendAsync rather than the .NET 8 Send API.
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) { throw "Upload failed ($([int]$response.StatusCode)): $body" }
        return $body | ConvertFrom-Json
    } finally {
        $stream.Dispose(); $content.Dispose(); $client.Dispose()
    }
}

function Invoke-Mcp {
    param([string]$Secret, [string]$Method, [object]$Params, [string]$Id)
    return Invoke-Json "POST" "/api/mcp" @{ Authorization = "Bearer $Secret"; Accept = "application/json, text/event-stream"; "MCP-Protocol-Version" = "2025-11-25" } @{
        jsonrpc = "2.0"; id = $Id; method = $Method; params = $Params
    } 180
}

$pdfName = [System.IO.Path]::GetFileName($PdfPath)
$pdfSize = (Get-Item -LiteralPath $PdfPath).Length
$pdfHash = Get-FileSha256Hex $PdfPath

$login = Invoke-Json "POST" "/api/auth/login" @{} @{ username = $Username; password = $Password }
$sessionHeaders = @{ "X-Device-Id" = "real-mcp-pdf-acceptance" }
$document = Invoke-MultipartUpload $sessionHeaders

$syncPath = "/api/teacher/resources/$($document.documentId)/sync-jobs"
$job = Invoke-Json "POST" $syncPath $sessionHeaders

$executePath = "$syncPath/$($job.jobId)/execute"
[void](Invoke-Json "POST" $executePath $sessionHeaders $null 180)

$deadline = (Get-Date).AddSeconds($PollSeconds)
do {
    Start-Sleep -Seconds 3
    $jobs = Invoke-Json "GET" $syncPath $sessionHeaders
    $latestJob = $jobs | Where-Object { $_.jobId -eq $job.jobId } | Select-Object -First 1
    if ($latestJob.status -in @("completed", "failed", "paused")) { break }
} while ((Get-Date) -lt $deadline)
if ($null -eq $latestJob -or $latestJob.status -ne "completed") {
    throw "Real sync did not complete successfully: $($latestJob | ConvertTo-Json -Compress -Depth 8)"
}

$key = Invoke-Json "POST" "/api/mcp/keys" $sessionHeaders
$mcpSecret = $key.secretKey
$initialize = Invoke-Mcp $mcpSecret "initialize" @{ protocolVersion = "2025-11-25"; capabilities = @{}; clientInfo = @{ name = "real-math-pdf-acceptance"; version = "1.0" } } "initialize"
$tools = Invoke-Mcp $mcpSecret "tools/list" @{} "tools-list"
$resourceList = Invoke-Mcp $mcpSecret "tools/call" @{ name = "list_teacher_resources"; arguments = @{} } "list-resources"
$resourceBlocks = Invoke-Mcp $mcpSecret "tools/call" @{ name = "read_teacher_resource_blocks"; arguments = @{ documentId = $document.documentId } } "read-blocks"
$search = Invoke-Mcp $mcpSecret "tools/call" @{ name = "search_teacher_resource_evidence"; arguments = @{ query = "数学 函数 几何"; limit = 5; libraries = @("teacher_resource"); sourceTypes = @("mock_exam"); documentIds = @($document.documentId) } } "search-resource"

$audit = Invoke-Json "GET" ("/api/teacher/resources/search/audit/" + $search.result.structuredContent.queryId) $sessionHeaders
$keys = Invoke-Json "GET" "/api/mcp/keys" $sessionHeaders
$accepted = [ordered]@{
    runId = $RunId
    source = @{ fileName = $pdfName; sizeBytes = $pdfSize; sha256 = $pdfHash; sourceType = "mock_exam"; parseMode = $ParseMode }
    session = @{ tenantId = $login.tenantId; userId = $login.userId; role = $login.role }
    resource = @{ documentId = $document.documentId; title = $document.title; syncStatus = $latestJob.status; parseMode = $document.parseMode }
    mcp = @{
        keyId = $key.keyId; ownerUserId = $key.ownerUserId; keyProfile = $key.keyProfile
        initialized = ($initialize.result.protocolVersion -eq "2025-11-25")
        advertisedTools = @($tools.result.tools | ForEach-Object { $_.name })
        visibleResourceCount = @($resourceList.result.structuredContent).Count
        parsedBlockCount = @($resourceBlocks.result.structuredContent).Count
        searchQueryId = $search.result.structuredContent.queryId
        searchHitCount = $search.result.structuredContent.hitCount
        keyLastUsedAt = ($keys | Where-Object { $_.keyId -eq $key.keyId } | Select-Object -First 1).lastUsedAt
    }
    audit = @{ queryId = $audit.queryId; subjectId = $audit.subjectId; tenantId = $audit.tenantId; endpoint = $audit.endpoint }
}
$accepted | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $OutputRoot "acceptance-summary.json") -Encoding utf8
$tools.result | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $OutputRoot "mcp-tools.json") -Encoding utf8
$search.result.structuredContent | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $OutputRoot "resource-search.json") -Encoding utf8
Write-Output ($accepted | ConvertTo-Json -Depth 20)
