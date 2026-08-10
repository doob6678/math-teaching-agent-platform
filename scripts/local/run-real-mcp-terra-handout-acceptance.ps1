param(
    [string]$BackendUrl = "http://127.0.0.1:8080",
    [string]$Username = "admin",
    [string]$Password = "admin-123456",
    # Allows a timed-out local caller to resume the durable MCP workflow without submitting another model request.
    [string]$WorkflowId = "",
    # Exercises the preferred array contract or the legacy explicit-marker contract with a real provider/model route.
    [ValidateSet("array", "explicit-markers")][string]$InputMode = "array",
    [string]$ModelCode = "gpt-5.6-luna",
    [int]$ExpectedQuestionCount = 0,
    [int]$PollSeconds = 900
)

$ErrorActionPreference = "Stop"
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
Add-Type -AssemblyName System.Net.Http
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$RunId = "mcp-luna-handout-" + (Get-Date -Format "yyyyMMddTHHmmssZ")
$OutputRoot = Join-Path $Root "output\mcp-acceptance\$RunId"
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
# Keep one cookie-aware client for the whole acceptance run. The backend's login token is HttpOnly by design, so
# creating a fresh client per request would discard the authenticated session before MCP-key issuance.
$script:HttpHandler = [System.Net.Http.HttpClientHandler]::new()
$script:HttpHandler.UseCookies = $true
$script:HttpHandler.UseProxy = $false
$script:HttpClient = [System.Net.Http.HttpClient]::new($script:HttpHandler)
$script:HttpClient.Timeout = [TimeSpan]::FromSeconds(300)
$PngVisibilityRetries = 20
$PngVisibilityDelayMs = 250

function Get-Sha256Hex {
    param([byte[]]$Bytes)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return -join ($sha.ComputeHash($Bytes) | ForEach-Object { $_.ToString("x2") })
    } finally {
        $sha.Dispose()
    }
}

function Invoke-Json {
    param([string]$Method, [string]$Path, [hashtable]$Headers = @{}, [object]$Body = $null, [int]$TimeoutSec = 180)
    # Windows PowerShell 5.1 otherwise decodes an application/json response without a charset by using the active
    # ANSI code page, corrupting Chinese artifact text. Reading bytes and explicitly applying UTF-8 keeps trace and
    # artifact evidence byte-faithful to the backend response.
    $client = $script:HttpClient
    # HttpClient.Timeout is configured once on the shared client; .NET forbids changing it after the first request.
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), ($BackendUrl.TrimEnd("/") + $Path))
    try {
        foreach ($header in $Headers.GetEnumerator()) {
            if (-not [string]::IsNullOrWhiteSpace([string]$header.Value)) {
                [void]$request.Headers.TryAddWithoutValidation($header.Key, [string]$header.Value)
            }
        }
        # The backend deliberately stores the Sa-Token in an HttpOnly cookie, so a real acceptance run must
        # preserve that cookie across login, MCP-key issuance, tool calls, export, and key revocation. Sending the
        # sending a retired token header would test the wrong security path and yield a
        # misleading 403 before any handout generation starts.
        if (-not [string]::IsNullOrWhiteSpace($script:SessionCookie)) {
            [void]$request.Headers.TryAddWithoutValidation("Cookie", $script:SessionCookie)
        }
        if ($null -ne $Body) {
            $request.Content = [System.Net.Http.StringContent]::new(($Body | ConvertTo-Json -Compress -Depth 30), [System.Text.Encoding]::UTF8, "application/json")
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        if ($response.Headers.Contains("Set-Cookie")) {
            $script:SessionCookie = (($response.Headers.GetValues("Set-Cookie") | ForEach-Object { ($_ -split ";")[0] }) -join "; ")
        }
        $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $text = [System.Text.Encoding]::UTF8.GetString($bytes)
        if (-not $response.IsSuccessStatusCode) {
            throw "HTTP $([int]$response.StatusCode) for ${Path}: $text"
        }
        return $text | ConvertFrom-Json
    } finally {
        $request.Dispose()
        # The shared cookie-aware client remains alive until the script exits so every request uses the login session.
    }
}

function Invoke-Mcp {
    param([string]$Secret, [string]$Method, [object]$Params, [string]$Id)
    $response = Invoke-Json "POST" "/api/mcp" @{
        Authorization = "Bearer $Secret"
        Accept = "application/json, text/event-stream"
        "MCP-Protocol-Version" = "2025-11-25"
    } @{ jsonrpc = "2.0"; id = $Id; method = $Method; params = $Params } 240
    if ($null -ne $response.error) {
        throw "MCP $Method failed: $($response.error | ConvertTo-Json -Compress -Depth 12)"
    }
    return $response
}

function Invoke-McpTool {
    param([string]$Secret, [string]$ToolName, [hashtable]$Arguments, [string]$Id)
    $response = Invoke-Mcp $Secret "tools/call" @{ name = $ToolName; arguments = $Arguments } $Id
    if ($response.result.isError -eq $true) {
        throw "MCP tool $ToolName failed: $($response.result | ConvertTo-Json -Compress -Depth 20)"
    }
    return $response.result.structuredContent
}

function Save-PdfExport {
    param([object]$Payload, [string]$Variant)
    # Keep provider checksum and local checksum together for every publishable variant; this prevents a successful
    # MCP response from being mistaken for a verified file when the Windows filesystem write is incomplete.
    $bytes = [Convert]::FromBase64String($Payload.base64Content)
    $path = Join-Path $OutputRoot $Payload.fileName
    [System.IO.File]::WriteAllBytes($path, $bytes)
    $localHash = Get-Sha256Hex $bytes
    if ($localHash -ne $Payload.sha256) {
        throw "MCP $Variant PDF checksum mismatch: expected $($Payload.sha256), got $localHash"
    }
    return [ordered]@{
        variant = $Variant
        fileName = $Payload.fileName
        mimeType = $Payload.mimeType
        byteSize = $Payload.byteSize
        sha256 = $Payload.sha256
        path = $path
    }
}

function Get-RenderedPageCount {
    param([string]$DirectoryPath, [string]$Pattern)
    # Use the .NET directory API and force an array before counting. Windows PowerShell can unwrap a one-element
    # provider result and expose the string's character length instead of the number of rendered files.
    $files = @([System.IO.Directory]::GetFiles($DirectoryPath, $Pattern))
    return $files.Count
}

# Authenticate through the backend only to mint an owner-bound MCP secret. All AI work below uses /api/mcp JSON-RPC.
$login = Invoke-Json "POST" "/api/auth/login" @{} @{ username = $Username; password = $Password }
# Login deliberately returns only the owner profile; the HttpOnly satoken cookie is captured by Invoke-Json above.
# The login response contains only non-sensitive identity metadata; the shared HttpClient carries the HttpOnly
# cookie automatically for this owner-bound MCP-key request.
$sessionHeaders = @{ "X-Device-Id" = "real-mcp-terra-handout-acceptance" }
$key = Invoke-Json "POST" "/api/mcp/keys" $sessionHeaders
$mcpSecret = $key.secretKey

try {
    $initialize = Invoke-Mcp $mcpSecret "initialize" @{ protocolVersion = "2025-11-25"; capabilities = @{}; clientInfo = @{ name = "real-luna-handout-acceptance"; version = "1.0" } } "initialize"
    $tools = Invoke-Mcp $mcpSecret "tools/list" @{} "tools-list"
    $questions = @()
    $batchInput = $null
    if ([string]::IsNullOrWhiteSpace($WorkflowId)) {
        # The batch is intentionally broad: retrieval must search the whole function domain and the projection must
        # retain every submitted question instead of collapsing to a single example.
        $questions = @(
            '已知函数 $f(x)=\frac{\sqrt{x+1}}{x-2}$，求定义域。',
            '已知函数 $g(x)=x+\frac{1}{x}$（$x>0$），求其最小值。',
            '已知函数 $h(x)=x^2-2ax+1$ 在区间 $[0,2]$ 上的最小值为 $-3$，求实数 $a$。',
            '在正方体 $ABCD-A_1B_1C_1D_1$ 中，求直线 $AC_1$ 与平面 $A_1BD$ 所成角的正弦值。'
        )
        $arguments = @{
            writingGoal = '高三函数综合复习与空间向量应用讲义：围绕全部提交题目组织统一知识主线，逐题给出审题、方法、完整教师解答与易错点；学生版和16:10版不泄露答案，不输出过程日志，不把题目拆成互不关联的短文。'
            evidenceRefs = @()
            preferredProviderName = "openai"
            preferredModelCode = $ModelCode
        }
        if ($InputMode -eq "explicit-markers") {
            # The separator occupies its own line; spaces and blank lines inside each formula-rich question remain data.
            $arguments.questionText = $questions[0] + "`n`n---`n`n" + $questions[1]
        } else {
            $arguments.questions = $questions
        }
        $started = Invoke-McpTool $mcpSecret "start_multi_agent_writing" $arguments "start-writing"
        $batchInput = $started.batchInput
        $expectedSplitMode = if ($InputMode -eq "explicit-markers") { "question_text_explicit_markers" } else { "questions_array" }
        if ($batchInput.questionCount -ne $questions.Count -or $batchInput.splitMode -ne $expectedSplitMode -or $batchInput.whitespaceSplitsQuestions -ne $false) {
            throw "Backend multi-question parsing contract failed: $($batchInput | ConvertTo-Json -Compress)"
        }
        $workflowId = $started.workflowId
    } else {
        $workflowId = $WorkflowId.Trim()
    }
    $deadline = (Get-Date).AddSeconds($PollSeconds)
    do {
        Start-Sleep -Seconds 5
        $status = Invoke-McpTool $mcpSecret "get_multi_agent_writing_status" @{ workflowId = $workflowId } "status-$([guid]::NewGuid())"
        if ($status.status -in @("COMPLETED", "FAILED")) { break }
    } while ((Get-Date) -lt $deadline)
    if ($status.status -ne "COMPLETED") {
        throw "$ModelCode MCP workflow did not complete: $($status | ConvertTo-Json -Compress -Depth 20)"
    }

    $artifact = Invoke-McpTool $mcpSecret "get_multi_agent_writing_artifact" @{ workflowId = $workflowId } "artifact"
    $trace = Invoke-McpTool $mcpSecret "get_multi_agent_writing_trace" @{ workflowId = $workflowId } "trace"
    $exportPayloads = [ordered]@{}
    $exports = [ordered]@{}
    foreach ($spec in @(
        @{ key = "teacher"; format = "pdf-teacher" },
        @{ key = "student"; format = "pdf-student" },
        @{ key = "lecture"; format = "pdf-lecture" }
    )) {
        $payload = Invoke-McpTool $mcpSecret "export_multi_agent_writing_artifact" @{ workflowId = $workflowId; format = $spec.format } "export-$($spec.key)"
        $exportPayloads[$spec.key] = $payload
        $exports[$spec.key] = Save-PdfExport $payload $spec.key
    }
    $export = $exportPayloads["teacher"]
    $pdfPath = $exports["teacher"].path

    $poppler = Get-Command pdftoppm -ErrorAction SilentlyContinue
    $popplerPath = if ($null -eq $poppler) { $null } else { $poppler.Source }
    # The bundled override wrapper may target a non-existent `bin` path; prefer the verified native executable.
    $nativePoppler = "C:\Users\doob\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\poppler\Library\bin\pdftoppm.exe"
    if (Test-Path -LiteralPath $nativePoppler) {
        $popplerPath = $nativePoppler
    }
    if ([string]::IsNullOrWhiteSpace($popplerPath)) {
        throw "pdftoppm is required for the Windows PDF rendering audit but was not found."
    }
    $renderedPages = @{}
    foreach ($variant in @("teacher", "student", "lecture")) {
        $variantPdf = $exports[$variant].path
        $renderPrefix = Join-Path $OutputRoot "$variant-handout"
        & $popplerPath -png -r 144 $variantPdf $renderPrefix
        if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "Windows PDF rendering audit failed for $variant." }
        # Bracing the variable is required in PowerShell: without it, `$variant-handout` is parsed as one
        # variable name and the successful pdftoppm output is falsely reported as missing.
        # Use the .NET directory API for the final assertion so PowerShell provider-specific `-Filter` behavior
        # cannot hide files that pdftoppm has already written successfully.
        $pagePattern = "$($variant)-handout-*.png"
        $pageCount = Get-RenderedPageCount $OutputRoot $pagePattern
        # Windows Poppler can return just before the filesystem directory enumeration sees the final PNG. A bounded
        # visibility retry keeps the acceptance result about rendered pages rather than a transient file-index race.
        for ($attempt = 0; $pageCount -lt 1 -and $attempt -lt $PngVisibilityRetries; $attempt++) {
            Start-Sleep -Milliseconds $PngVisibilityDelayMs
            $pageCount = Get-RenderedPageCount $OutputRoot $pagePattern
        }
        if ($pageCount -lt 1) {
            $visibleFiles = @([System.IO.Directory]::GetFiles($OutputRoot) | ForEach-Object { [System.IO.Path]::GetFileName($_) })
            throw "Windows PDF rendering produced no $variant page PNG. root=$OutputRoot pattern=$pagePattern files=$($visibleFiles -join ',')"
        }
        $renderedPages[$variant] = $pageCount
        $exports[$variant].renderedPageCount = $pageCount
    }

    $stageProviders = @($trace.stages | ForEach-Object {
        # Trace uses workflowId:stageCode as the durable plan id; derive the display code without trusting client input.
        $stageCode = if ($_.planId -match '^[^:]+:(.+)$') { $Matches[1] } else { "" }
        @{ stageCode = $stageCode; providerName = $_.providerName; modelCode = $_.modelCode; status = $_.status }
    })
    $selectedStages = @($stageProviders | Where-Object { $_.providerName -eq "openai" -and $_.modelCode -eq $ModelCode })
    if ($selectedStages.Count -lt 1) { throw "Trace contains no successful $ModelCode-backed stage." }
    if ($artifact.subjectId -ne $login.userId -or $trace.subjectId -ne $login.userId) {
        throw "Workflow owner was not bound to the authenticated MCP key owner."
    }
    $result = [ordered]@{
        runId = $RunId
        session = @{ tenantId = $login.tenantId; userId = $login.userId; role = $login.role }
        mcp = @{ keyId = $key.keyId; ownerUserId = $key.ownerUserId; protocolVersion = $initialize.result.protocolVersion; advertisedTools = @($tools.result.tools | ForEach-Object { $_.name }) }
        batchInput = @{ inputMode = $InputMode; questionCount = $(if ($questions.Count -gt 0) { $questions.Count } elseif ($ExpectedQuestionCount -gt 0) { $ExpectedQuestionCount } else { "persisted workflow" }); serverParser = $batchInput; separatorPolicy = "questions array or explicit standalone marker; no whitespace or blank-line split" }
        workflow = @{ workflowId = $workflowId; status = $status.status; subjectId = $artifact.subjectId; stageCount = $status.stageCount; totalUsage = $status.totalUsage; stageProviders = $stageProviders }
        pdf = @{ variants = $exports; teacherRenderedPageCount = $renderedPages["teacher"] }
    }
    $result | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $OutputRoot "acceptance-summary.json") -Encoding utf8
    $artifact | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $OutputRoot "artifact.json") -Encoding utf8
    $trace | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $OutputRoot "trace.json") -Encoding utf8
    $exports | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $OutputRoot "pdf-exports.json") -Encoding utf8
    $result | ConvertTo-Json -Depth 30
} finally {
    # A one-time secret is revoked even on model or rendering failure; JSON evidence never contains the raw secret.
    if (-not [string]::IsNullOrWhiteSpace($key.keyId)) {
        try { [void](Invoke-Json "POST" "/api/mcp/keys/$($key.keyId)/revoke" $sessionHeaders) } catch { Write-Warning "Could not revoke temporary MCP key $($key.keyId): $($_.Exception.Message)" }
    }
}
