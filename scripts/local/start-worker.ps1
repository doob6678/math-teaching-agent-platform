param(
    [int]$Port = 8091,
    [string]$PythonPath = "",
    [switch]$Background
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$Worker = Join-Path $Root "ai-worker-python"

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
    $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if ($pythonCommand) {
        $candidates += $pythonCommand.Source
    }
    $candidates += @(
        "D:\conda\envs\py_12\python.exe",
        (Join-Path $Worker ".venv\Scripts\python.exe"),
        "C:\Users\doob\.workbuddy\binaries\python\envs\default\Scripts\python.exe"
    )
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

$env:MATH_AGENT_WORKER_API_KEY = Resolve-WorkerApiKey
if ([string]::IsNullOrWhiteSpace($env:KMP_DUPLICATE_LIB_OK)) {
    $env:KMP_DUPLICATE_LIB_OK = "TRUE"
}
if ([string]::IsNullOrWhiteSpace($env:MATH_AGENT_EMBEDDING_PROVIDER_ORDER)) {
    $env:MATH_AGENT_EMBEDDING_PROVIDER_ORDER = "local_clip"
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
