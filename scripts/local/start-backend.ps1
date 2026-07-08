param(
    [string]$DbUrl = "jdbc:mysql://127.0.0.1:3306/math_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
    [string]$DbUser = "math_agent",
    [string]$DbPassword = "123456",
    [string]$RedisAddress = "redis://127.0.0.1:6379",
    [string]$MilvusUri = "http://127.0.0.1:19530",
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
    $MilvusUri = "http://${wslProxyHost}:19531"
} elseif ((Test-TcpPort "127.0.0.1" 13306) -and (Test-TcpPort "127.0.0.1" 16379) -and (Test-TcpPort "127.0.0.1" 19531)) {
    $DbUrl = "jdbc:mysql://127.0.0.1:13306/math_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
    $RedisAddress = "redis://127.0.0.1:16379"
    $MilvusUri = "http://127.0.0.1:19531"
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
$env:MATH_AGENT_REDIS_RATE_LIMIT_ENABLED = "true"
$env:MATH_AGENT_REDIS_CAPABILITY_STORE_ENABLED = "true"
$env:MATH_AGENT_REDIS_SEARCH_CACHE_ENABLED = "true"
$env:MATH_AGENT_VECTOR_INDEX_ENABLED = "true"
$env:MATH_AGENT_MILVUS_URI = $MilvusUri
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_MILVUS_TOKEN)) {
    $env:MATH_AGENT_MILVUS_TOKEN = "root:doob67"
}
$env:MATH_AGENT_TEACHER_SYNC_FEISHU_PROCESS_DOWNLOADER_ENABLED = "true"
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_EMBEDDING_BASE_URL)) {
    $env:MATH_AGENT_EMBEDDING_BASE_URL = "http://127.0.0.1:8091/v1"
}
$env:MATH_AGENT_WORKER_API_KEY = Resolve-WorkerApiKey
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_EMBEDDING_API_KEY)) {
    $env:MATH_AGENT_EMBEDDING_API_KEY = $env:MATH_AGENT_WORKER_API_KEY
}
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_EMBEDDING_MODEL)) {
    $env:MATH_AGENT_EMBEDDING_MODEL = "local-clip-vit-large-patch14-zh"
}
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_EMBEDDING_DIMENSION)) {
    $env:MATH_AGENT_EMBEDDING_DIMENSION = "512"
}
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_AI_DEFAULT_PROVIDER) -and -not [string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) {
    # Local RAG/Agent evaluation should prefer the configured low-cost DeepSeek route when available.
    # Users can still override this before startup by setting MATH_AGENT_AI_DEFAULT_PROVIDER explicitly.
    $env:MATH_AGENT_AI_DEFAULT_PROVIDER = "deepseek"
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
    $compiledMigrationDir = Join-Path $Backend "target\classes\db\migration"
    if (Test-Path $compiledMigrationDir) {
        Remove-Item -LiteralPath $compiledMigrationDir -Recurse -Force
    }
    mvn "-Dmaven.test.skip=true" spring-boot:run
} finally {
    Pop-Location
}
