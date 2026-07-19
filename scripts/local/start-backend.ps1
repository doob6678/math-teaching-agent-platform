param(
    [string]$DbUrl = "jdbc:mysql://127.0.0.1:3306/math_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
    [string]$DbUser = "math_agent",
    [string]$DbPassword = "123456",
    [string]$RedisAddress = "redis://127.0.0.1:6379",
    [string]$RabbitMqAddresses = "amqp://127.0.0.1:5672",
    [string]$Distro = "Ubuntu"
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$Backend = Join-Path $Root "backend-java"

function Test-TcpPort {
    param(
        [string]$HostName,
        [int]$Port
    )
    try {
        $client = [System.Net.Sockets.TcpClient]::new()
        $connect = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $connect.AsyncWaitHandle.WaitOne(3000)) {
            $client.Close()
            return $false
        }
        $client.EndConnect($connect)
        $client.Close()
        return $true
    } catch {
        return $false
    }
}

function Resolve-WslProxyHost {
    param([string]$LinuxDistro)
    $ip = (wsl -d $LinuxDistro -- bash -lc "hostname -I | tr ' ' '\n' | head -n1").Trim()
    if ([string]::IsNullOrWhiteSpace($ip)) {
        throw "Unable to resolve WSL IP for distro $LinuxDistro"
    }
    return $ip
}

function Resolve-WorkerApiKey {
    if (-not [string]::IsNullOrWhiteSpace($env:MATH_AGENT_WORKER_API_KEY)) {
        return $env:MATH_AGENT_WORKER_API_KEY
    }
    $secretDir = Join-Path $Root ".local-secrets"
    $secretFile = Join-Path $secretDir "worker-api-key.txt"
    if (Test-Path $secretFile) {
        $existing = (Get-Content -LiteralPath $secretFile -Raw).Trim()
        if (-not [string]::IsNullOrWhiteSpace($existing)) {
            return $existing
        }
    }
    New-Item -ItemType Directory -Force -Path $secretDir | Out-Null
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RNGCryptoServiceProvider]::Create()
    $rng.GetBytes($bytes)
    $generated = [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
    Set-Content -LiteralPath $secretFile -Value $generated -NoNewline
    return $generated
}

function New-UrlSafeSecret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RNGCryptoServiceProvider]::Create()
    $rng.GetBytes($bytes)
    return [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function Get-Sha256SecretHash {
    param([string]$Secret)
    $sha = [Security.Cryptography.SHA256]::Create()
    $normalized = if ($null -eq $Secret) { "" } else { $Secret.Trim() }
    $bytes = [Text.Encoding]::UTF8.GetBytes($normalized)
    $digest = $sha.ComputeHash($bytes)
    return "sha256:" + (($digest | ForEach-Object { $_.ToString("x2") }) -join "")
}

function Resolve-McpSecret {
    if (-not [string]::IsNullOrWhiteSpace($env:MATH_AGENT_MCP_SECRET)) {
        return $env:MATH_AGENT_MCP_SECRET
    }
    $secretDir = Join-Path $Root ".local-secrets"
    $secretFile = Join-Path $secretDir "mcp-secret.txt"
    if (Test-Path $secretFile) {
        $existing = (Get-Content -LiteralPath $secretFile -Raw).Trim()
        if (-not [string]::IsNullOrWhiteSpace($existing)) {
            return $existing
        }
    }
    New-Item -ItemType Directory -Force -Path $secretDir | Out-Null
    $generated = "mcp_secret_" + (New-UrlSafeSecret)
    Set-Content -LiteralPath $secretFile -Value $generated -NoNewline
    return $generated
}

$wslProxyHost = ""
try {
    $wslProxyHost = Resolve-WslProxyHost $Distro
} catch {
    Write-Host "WSL proxy host could not be resolved: $($_.Exception.Message)"
}

if (-not [string]::IsNullOrWhiteSpace($wslProxyHost) `
    -and (Test-TcpPort $wslProxyHost 13306) `
    -and (Test-TcpPort $wslProxyHost 16379) `
    -and (Test-TcpPort $wslProxyHost 19531)) {
    $DbUrl = "jdbc:mysql://${wslProxyHost}:13306/math_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
    $RedisAddress = "redis://${wslProxyHost}:16379"
    $RabbitMqAddresses = "amqp://${wslProxyHost}:5672"
} elseif (-not [string]::IsNullOrWhiteSpace($wslProxyHost) `
    -and (Test-TcpPort $wslProxyHost 3306) `
    -and (Test-TcpPort $wslProxyHost 6379) `
    -and (Test-TcpPort $wslProxyHost 19530)) {
    # Developer machines may run MySQL/Redis directly inside WSL instead of behind the Docker host mappings above.
    # Use the same WSL address for all three services so the backend continues to use the existing real database and
    # never falls back to creating an empty container volume after a Docker restart.
    $DbUrl = "jdbc:mysql://${wslProxyHost}:3306/math_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
    $RedisAddress = "redis://${wslProxyHost}:6379"
    $RabbitMqAddresses = "amqp://${wslProxyHost}:5672"
} elseif ((Test-TcpPort "127.0.0.1" 13306) -and (Test-TcpPort "127.0.0.1" 16379) -and (Test-TcpPort "127.0.0.1" 19531)) {
    $DbUrl = "jdbc:mysql://127.0.0.1:13306/math_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
    $RedisAddress = "redis://127.0.0.1:16379"
} elseif (-not ((Test-TcpPort "127.0.0.1" 3306) -and (Test-TcpPort "127.0.0.1" 6379) -and (Test-TcpPort "127.0.0.1" 19530))) {
    throw "Neither WSL proxy ports nor localhost services are reachable. Start MySQL/Redis/Milvus first."
}

$env:MATH_AGENT_DB_ENABLED = "true"
$env:MATH_AGENT_DB_URL = $DbUrl
$env:MATH_AGENT_DB_USERNAME = $DbUser
$env:MATH_AGENT_DB_PASSWORD = $DbPassword
$env:MATH_AGENT_REDIS_REDISSON_ENABLED = "true"
$env:MATH_AGENT_REDIS_REDISSON_ADDRESS = $RedisAddress
$env:SPRING_DATA_REDIS_URL = $RedisAddress
$env:SPRING_REDIS_URL = $RedisAddress
# The broker command contains only backend-resolved identity and durable job IDs; API keys, capability tokens and
# request hashes stay at the HTTP boundary. Keeping this setting here ensures the Windows JVM uses the WSL broker.
$env:SPRING_RABBITMQ_ADDRESSES = $RabbitMqAddresses
$env:MATH_AGENT_REDIS_RATE_LIMIT_ENABLED = "true"
$env:MATH_AGENT_REDIS_CAPABILITY_STORE_ENABLED = "true"
# Source re-sync and single-document imports must be visible to the next teacher retrieval.  Caching may be enabled
# only by an explicit operator setting after an audited corpus build; it is never the local launch default.
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_REDIS_SEARCH_CACHE_ENABLED)) {
    $env:MATH_AGENT_REDIS_SEARCH_CACHE_ENABLED = "false"
}
$env:MATH_AGENT_VECTOR_INDEX_ENABLED = "true"
# The CUDA worker serializes first-load/model batches across the parallel textbook and teacher retrieval branches.
# Keep the vector request budget bounded but above the real cold-start queue so one branch does not fall back to a
# second embedding pass merely because the first cross-encoder request waited behind a sibling request.
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_VECTOR_INDEX_TIMEOUT_MS)) {
    $env:MATH_AGENT_VECTOR_INDEX_TIMEOUT_MS = "120000"
}
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_MILVUS_TOKEN)) {
    throw "MATH_AGENT_MILVUS_TOKEN must be provided by the environment; credentials are not stored in scripts or application.yml."
}
$env:MATH_AGENT_TEACHER_SYNC_FEISHU_PROCESS_DOWNLOADER_ENABLED = "true"
# Scanned teacher PDFs are rendered page-by-page during sync. Resolve the native renderer from this machine's PATH
# once and pass its absolute executable path to Java, so hidden backend processes retain the same reliable renderer.
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_PDF_RENDERER_EXECUTABLE)) {
    $pdfRenderer = Get-Command "pdftocairo.exe" -ErrorAction SilentlyContinue
    if ($null -ne $pdfRenderer -and -not [string]::IsNullOrWhiteSpace($pdfRenderer.Source)) {
        $env:MATH_AGENT_PDF_RENDERER_EXECUTABLE = $pdfRenderer.Source
    }
}
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_HANDOUT_TEMPLATE_DIRS)) {
    # Keep the local rendered source available to the curated template shelf without embedding a non-ASCII path in
    # this PowerShell script. The actual Zhao template is defined by the UTF-8 skill JSON loaded by Java.
    $stagedTemplateRoot = Join-Path $Root '.local-storage\teacher-source-imports\zhaolixian-2025'
    if (Test-Path -LiteralPath $stagedTemplateRoot) {
        $env:MATH_AGENT_HANDOUT_TEMPLATE_DIRS = $stagedTemplateRoot
    }
}
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_EMBEDDING_BASE_URL)) {
    $env:MATH_AGENT_EMBEDDING_BASE_URL = "http://127.0.0.1:8091/v1"
}
$env:MATH_AGENT_WORKER_API_KEY = Resolve-WorkerApiKey
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_EMBEDDING_API_KEY)) {
    $env:MATH_AGENT_EMBEDDING_API_KEY = $env:MATH_AGENT_WORKER_API_KEY
}
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_EMBEDDING_MODEL)) {
    # This model identifies the dedicated teacher text collection; image retrieval continues through CLIP worker APIs.
    $env:MATH_AGENT_EMBEDDING_MODEL = "local-bge-small-zh-v1.5"
}
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_AI_DEFAULT_PROVIDER) -and -not [string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) {
    # Student explanation requires a verified multimodal route. Prefer the configured OpenAI-compatible provider so
    # text generation and uploaded-question vision use the same gpt-5.6-luna capability unless an operator overrides it.
    $env:MATH_AGENT_AI_DEFAULT_PROVIDER = "openai"
}
if ([string]::IsNullOrWhiteSpace($env:OPENAI_CHAT_MODEL) -and -not [string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) {
    # The model was confirmed by the live /models capability check before making it the local default.
    $env:OPENAI_CHAT_MODEL = "gpt-5.6-luna"
}
if ([string]::IsNullOrWhiteSpace($env:OPENAI_VISION_MODEL) -and -not [string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) {
    $env:OPENAI_VISION_MODEL = $env:OPENAI_CHAT_MODEL
}
# A full three-version handout is materially larger than a chat turn. The default 30-second
# provider timeout aborts valid real responses and forces the generic fallback handout, so keep
# a bounded but production-appropriate three-minute budget unless an operator explicitly sets one.
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_AI_CHAT_REQUEST_TIMEOUT_MS)) {
    # A ten-question source-grounded handout requires the relay to finish a large structured response. The previous
    # three-minute default was exceeded by a verified 199-second gpt-5.6 call, which then produced no usable draft.
    # Keep the value configurable but give the local real-worker path a seven-minute upper bound.
    $env:MATH_AGENT_AI_CHAT_REQUEST_TIMEOUT_MS = "420000"
}
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_STUDENT_EXPLANATION_VISION_TIMEOUT_MS)) {
    # Page-level teacher OCR uses the same permission-checked vision client as student uploads; keep its relay
    # budget aligned with the configured gpt-5.6-luna worker budget.
    $env:MATH_AGENT_STUDENT_EXPLANATION_VISION_TIMEOUT_MS = "420000"
}
# A real high-school exam DOCX can legitimately include one image/equation package entry per formula. Keep a bounded
# POI package-entry ceiling above the verified 1,489-entry source while leaving its compression-ratio safeguards on.
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_TEACHER_SYNC_DOCX_MAX_ZIP_ENTRIES)) {
    $env:MATH_AGENT_TEACHER_SYNC_DOCX_MAX_ZIP_ENTRIES = "5000"
}
$docxZipOption = "-Dmath.agent.teacher.sync.docx-max-zip-entries=$($env:MATH_AGENT_TEACHER_SYNC_DOCX_MAX_ZIP_ENTRIES)"
if ([string]::IsNullOrWhiteSpace($env:JAVA_TOOL_OPTIONS)) {
    $env:JAVA_TOOL_OPTIONS = $docxZipOption
} elseif ($env:JAVA_TOOL_OPTIONS -notmatch [regex]::Escape("-Dmath.agent.teacher.sync.docx-max-zip-entries=")) {
    $env:JAVA_TOOL_OPTIONS = "$($env:JAVA_TOOL_OPTIONS) $docxZipOption"
}

$mcpSecret = Resolve-McpSecret
$env:MATH_AGENT_MCP_SECRET = $mcpSecret
$env:MATH_AGENT_PROTOCOL_MCP_REGISTRY_CLIENTS_0_CLIENT_ID = "workbuddy-local-admin"
$env:MATH_AGENT_PROTOCOL_MCP_REGISTRY_CLIENTS_0_PROFILE = "admin"
$env:MATH_AGENT_PROTOCOL_MCP_REGISTRY_CLIENTS_0_TENANT_ID = "school-a"
$env:MATH_AGENT_PROTOCOL_MCP_REGISTRY_CLIENTS_0_SUBJECT_ID = "local-admin"
$env:MATH_AGENT_PROTOCOL_MCP_REGISTRY_CLIENTS_0_SECRET_HASH = Get-Sha256SecretHash $mcpSecret
$env:MATH_AGENT_PROTOCOL_MCP_REGISTRY_CLIENTS_0_ENABLED = "true"
$env:MATH_AGENT_PROTOCOL_MCP_REGISTRY_CLIENTS_0_ALLOWED_TOOLS = @(
    "search_multi_source_evidence",
    "search_textbook_evidence",
    "search_teacher_resource_evidence",
    "get_teaching_ai_trace",
    "get_ai_diagnostic_summary",
    "get_multi_agent_writing_trace",
    "plan_agent_run",
    "start_multi_agent_writing",
    "get_multi_agent_writing_status",
    "get_multi_agent_writing_artifact",
    "export_multi_agent_writing_artifact",
    "resume_multi_agent_writing",
    "discover_feishu_resources",
    "download_feishu_resource"
) -join ","
$env:MATH_AGENT_PROTOCOL_MCP_REGISTRY_CLIENTS_0_ALLOWED_SCOPES = @(
    "PUBLIC_TEXTBOOK",
    "teacher-resource:read",
    "teacher-resource:sync-execute",
    "agent-trace:read",
    "agent:plan",
    "agent-writing:execute",
    "agent-writing:read",
    "agent-writing:export"
) -join ","

if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_EMBEDDING_API_KEY)) {
    throw "MATH_AGENT_EMBEDDING_API_KEY or MATH_AGENT_WORKER_API_KEY is required"
}

Push-Location $Backend
try {
    # This repository contains more than one executable class, so Maven cannot infer the
    # Spring Boot entry point reliably after a clean compile. Pin the production application
    # explicitly; the same environment variables above still wire the real MySQL/Redis/Milvus
    # services and CUDA worker into this process.
    mvn "-Dmaven.test.skip=true" "-Dspring-boot.run.main-class=com.doob.mathagent.MathAgentApplication" spring-boot:run
} finally {
    Pop-Location
}
