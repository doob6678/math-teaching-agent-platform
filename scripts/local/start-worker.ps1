param(
    [int]$Port = 8091,
    [string]$PythonPath = "",
    [switch]$Background
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$Worker = Join-Path $Root "ai-worker-python"

# Desktop launchers do not always inherit freshly persisted user variables.
# Import only the project's namespaced settings before resolving Python so the
# worker does not silently fall back to CPU defaults in a new PowerShell.
$userEnvironment = [Environment]::GetEnvironmentVariables("User")
foreach ($entry in $userEnvironment.GetEnumerator()) {
    if ($entry.Key -like "MATH_AGENT_*") {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($entry.Key, "Process"))) {
            [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, "Process")
        }
    }
}

function Test-TcpPort {
    param(
        [string]$HostName,
        [int]$Port
    )
    try {
        $client = [System.Net.Sockets.TcpClient]::new()
        $connect = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $connect.AsyncWaitHandle.WaitOne(1000)) {
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

function Test-PythonDependencies {
    param(
        [string]$PythonExe,
        [string[]]$Modules
    )
    if ([string]::IsNullOrWhiteSpace($PythonExe) -or -not (Test-Path $PythonExe)) {
        return $false
    }
    $moduleLiteral = "'" + ($Modules -join "','") + "'"
    $check = "import importlib.util; missing=[name for name in [$moduleLiteral] if importlib.util.find_spec(name) is None]; raise SystemExit(1 if missing else 0)"
    & $PythonExe -c $check
    return $LASTEXITCODE -eq 0
}

function Resolve-WorkerPython {
    param([string]$RequestedPython)
    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($RequestedPython)) {
        $candidates += $RequestedPython
    }
    if (-not [string]::IsNullOrWhiteSpace($env:MATH_AGENT_WORKER_PYTHON)) {
        $candidates += $env:MATH_AGENT_WORKER_PYTHON
    }
    $candidates += @(
        (Join-Path $Worker ".venv\Scripts\python.exe"),
        # The project venv is installed with the CUDA wheel and must win over a
        # machine-wide CPU conda interpreter; otherwise the same 8091 port can
        # silently expose a CPU-only worker after a restart.
        "D:\conda\envs\py_12\python.exe",
        "C:\Users\doob\.workbuddy\binaries\python\envs\default\Scripts\python.exe"
    )
    $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if ($pythonCommand) {
        $candidates += $pythonCommand.Source
    }
    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (Test-PythonDependencies $candidate @("fastapi", "uvicorn", "pydantic", "torch", "PIL")) {
            return $candidate
        }
    }
    throw "No existing Python environment has required worker dependencies: fastapi, uvicorn, pydantic, torch, Pillow"
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

$Python = Resolve-WorkerPython $PythonPath

# On Windows, the venv redirector may create the long-lived child with the
# interpreter recorded in pyvenv.cfg.  Put the project's site-packages first so
# that child still imports the CUDA torch/model stack from this worker venv,
# instead of silently resolving the machine-wide CPU conda packages.
$projectVenvSitePackages = Join-Path $Worker ".venv\Lib\site-packages"
if ((Test-Path $projectVenvSitePackages) -and ($Python -eq (Join-Path $Worker ".venv\Scripts\python.exe"))) {
    $existingPythonPath = $env:PYTHONPATH
    $env:PYTHONPATH = if ([string]::IsNullOrWhiteSpace($existingPythonPath)) {
        $projectVenvSitePackages
    } else {
        "$projectVenvSitePackages;$existingPythonPath"
    }
}

$env:MATH_AGENT_WORKER_API_KEY = Resolve-WorkerApiKey
# The teacher sync invokes this worker for authorized page-image transcription. Keep its model and timeout explicit
# in the launcher so a restarted hidden worker cannot inherit an older short-lived process environment.
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_FORMULA_VISION_MODEL)) {
    $env:MATH_AGENT_FORMULA_VISION_MODEL = "gpt-5.6-luna"
}
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_FORMULA_VISION_TIMEOUT_SECONDS)) {
    $env:MATH_AGENT_FORMULA_VISION_TIMEOUT_SECONDS = "180"
}
if ([string]::IsNullOrWhiteSpace($env:KMP_DUPLICATE_LIB_OK)) {
    $env:KMP_DUPLICATE_LIB_OK = "TRUE"
}
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_PROCESSED_BOOKS_ROOT)) {
    $processedBookRoots = @(
        # Keep the worker's BGE and CLIP page indexes aligned with the Java
        # textbook service.  b4 remains available as an explicit rollback
        # corpus, but c2 is the default searchable small-heading corpus.
        "C:\Users\doob\Desktop\个人资料\高中数学\下载课本代码\tchMaterial-parser-main\tchMaterial-parser-main\processed_books_section_shadow_all_mini_c2",
        "C:\Users\doob\Desktop\个人资料\高中数学\下载课本代码\tchMaterial-parser-main\tchMaterial-parser-main\processed_books_section_shadow_all_mini_b4",
        "C:\Users\doob\Desktop\个人资料\高中数学\下载课本代码\tchMaterial-parser-main\tchMaterial-parser-main\processed_books",
        (Join-Path $Root "processed_books")
    )
    foreach ($candidate in $processedBookRoots) {
        if ((Test-Path $candidate) -and (Test-Path (Join-Path $candidate "_page_image_index"))) {
            $env:MATH_AGENT_PROCESSED_BOOKS_ROOT = $candidate
            break
        }
    }
}
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_EMBEDDING_DIMENSION)) {
    $env:MATH_AGENT_EMBEDDING_DIMENSION = "512"
}
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_LOCAL_CLIP_DIMENSION)) {
    $env:MATH_AGENT_LOCAL_CLIP_DIMENSION = "512"
}
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_LOCAL_CLIP_MODEL_PATH)) {
    $candidates = @(
        "D:\ModelScope\models\damo\multi-modal_clip-vit-large-patch14_zh",
        "D:\ModelScope\models\damo\multi-modal_clip-vit-large-patch14_336_zh",
        "D:\project2026\hf_cache\hub\models--OFA-Sys--chinese-clip-vit-large-patch14\snapshots\660941af70c6ff89ce658a1735404c0f3e536c38"
    )
    foreach ($candidate in $candidates) {
        if ((Test-Path (Join-Path $candidate "pytorch_model.bin")) -or
            (Test-Path (Join-Path $candidate "model.safetensors"))) {
            $env:MATH_AGENT_LOCAL_CLIP_MODEL_PATH = $candidate
            break
        }
    }
}

if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_LOCAL_RERANK_MODEL_PATH)) {
    $rerankCandidates = @(
        "D:\ModelScope\models\BAAI\bge-reranker-v2-m3",
        "D:\ModelScope\models\BAAI\bge-reranker-base"
    )
    foreach ($candidate in $rerankCandidates) {
        if ((Test-Path (Join-Path $candidate "config.json")) -and
            ((Test-Path (Join-Path $candidate "model.safetensors")) -or (Test-Path (Join-Path $candidate "pytorch_model.bin")))) {
            $env:MATH_AGENT_LOCAL_RERANK_MODEL_PATH = $candidate
            break
        }
    }
    if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_LOCAL_RERANK_MODEL_PATH)) {
        $snapshotRoots = @(
            "D:\project2026\hf_cache\hub\models--BAAI--bge-reranker-v2-m3\snapshots",
            "D:\project2026\hf_cache\hub\models--BAAI--bge-reranker-base\snapshots"
        )
        foreach ($root in $snapshotRoots) {
            if (-not (Test-Path $root)) {
                continue
            }
            $snapshot = Get-ChildItem -LiteralPath $root -Directory | Sort-Object Name -Descending | Select-Object -First 1
            if ($snapshot -and (Test-Path (Join-Path $snapshot.FullName "config.json")) -and
                ((Test-Path (Join-Path $snapshot.FullName "model.safetensors")) -or (Test-Path (Join-Path $snapshot.FullName "pytorch_model.bin")))) {
                $env:MATH_AGENT_LOCAL_RERANK_MODEL_PATH = $snapshot.FullName
                break
            }
        }
    }
}

# Keep CPU rerank latency bounded by the worker's explicit token budget. This is intentionally separate from result
# limits: changing it controls model compute only and never injects a relevance score heuristic.
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_LOCAL_RERANK_MAX_TOKENS)) {
    $env:MATH_AGENT_LOCAL_RERANK_MAX_TOKENS = "128"
}

# BGE text embeddings serve semantic document/page recall. Do not point this at a partial download: the worker checks
# for both config and model weights before changing the default CLIP embedding provider.
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_LOCAL_TEXT_EMBEDDING_MODEL_PATH)) {
    $textEmbeddingCandidates = @(
        "D:\ModelScope\models\BAAI\bge-small-zh-v1.5",
        "D:\ModelScope\models\BAAI\bge-m3"
    )
    foreach ($candidate in $textEmbeddingCandidates) {
        if ((Test-Path (Join-Path $candidate "config.json")) -and
            ((Test-Path (Join-Path $candidate "model.safetensors")) -or (Test-Path (Join-Path $candidate "pytorch_model.bin")))) {
            $env:MATH_AGENT_LOCAL_TEXT_EMBEDDING_MODEL_PATH = $candidate
            break
        }
    }
}
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_EMBEDDING_PROVIDER_ORDER)) {
    # Prefer BGE for the text-only teacher vector collection; CLIP remains available for image/page routes.
    $env:MATH_AGENT_EMBEDDING_PROVIDER_ORDER = if (-not [string]::IsNullOrWhiteSpace($env:MATH_AGENT_LOCAL_TEXT_EMBEDDING_MODEL_PATH)) {
        "local_bge_embedding,local_clip"
    } else {
        "local_clip"
    }
}

if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_LOCAL_CLIP_MODEL_PATH)) {
    throw "MATH_AGENT_LOCAL_CLIP_MODEL_PATH is required; no local CLIP model with weights was detected"
}

if (Test-TcpPort "127.0.0.1" $Port) {
    Write-Host "ai-worker already listening on 127.0.0.1:$Port; skip duplicate start."
    return
}

if (Test-Path (Join-Path $env:MATH_AGENT_LOCAL_CLIP_MODEL_PATH "configuration.json")) {
    & $Python -c "import importlib.util; missing=[name for name in ['transformers'] if importlib.util.find_spec(name) is None]; print(','.join(missing)); raise SystemExit(1 if missing else 0)"
    if ($LASTEXITCODE -ne 0) {
        throw "ModelScope local CLIP model detected, but transformers is missing; direct local text embedding cannot start"
    }
} else {
    & $Python -c "import importlib.util; raise SystemExit(0 if importlib.util.find_spec('transformers') else 'Missing Python worker dependency: transformers')"
    if ($LASTEXITCODE -ne 0) {
        throw "HuggingFace local CLIP model detected, but transformers is not installed in ai-worker-python\\.venv"
    }
}

Push-Location $Worker
try {
    Write-Host "Using worker Python: $Python"
    Write-Host "Using local CLIP model: $env:MATH_AGENT_LOCAL_CLIP_MODEL_PATH"
    if (-not [string]::IsNullOrWhiteSpace($env:MATH_AGENT_LOCAL_RERANK_MODEL_PATH)) {
        Write-Host "Using local rerank model: $env:MATH_AGENT_LOCAL_RERANK_MODEL_PATH"
    }
    if (-not [string]::IsNullOrWhiteSpace($env:MATH_AGENT_LOCAL_TEXT_EMBEDDING_MODEL_PATH)) {
        Write-Host "Using local BGE text embedding model: $env:MATH_AGENT_LOCAL_TEXT_EMBEDDING_MODEL_PATH"
    }
    if ($Background) {
        $logDir = Join-Path $Root "output\local-services"
        New-Item -ItemType Directory -Force -Path $logDir | Out-Null
        $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
        $stdout = Join-Path $logDir "ai-worker-$timestamp.out.log"
        $stderr = Join-Path $logDir "ai-worker-$timestamp.err.log"
        $process = Start-Process -FilePath $Python `
            -ArgumentList @("-m", "uvicorn", "app.server:app", "--host", "127.0.0.1", "--port", "$Port") `
            -WorkingDirectory $Worker `
            -WindowStyle Hidden `
            -RedirectStandardOutput $stdout `
            -RedirectStandardError $stderr `
            -PassThru
        Write-Host "ai-worker started: pid=$($process.Id) stdout=$stdout stderr=$stderr"
        return
    }
    & $Python -m uvicorn app.server:app --host 127.0.0.1 --port $Port
} finally {
    Pop-Location
}
