param(
    [string]$MysqlHost = "127.0.0.1",
    [int]$MysqlPort = 13306,
    [string]$MysqlUser = "math_agent",
    [string]$MysqlPassword = "123456",
    [string]$Database = "math_agent",
    [switch]$RestartBackend
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$BackendScript = Join-Path $Root "scripts\local\start-backend.ps1"
$SeedTag = "display_spine_v0.1%"

function Invoke-MysqlSql {
    param([string]$Sql)
    $arguments = @(
        "-h", $MysqlHost,
        "-P", "$MysqlPort",
        "-u", $MysqlUser,
        "-p$MysqlPassword",
        "-D", $Database
    )
    $Sql | & mysql @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL command failed"
    }
}

function Restart-BackendProcess {
    $connections = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
    foreach ($conn in $connections) {
        Stop-Process -Id $conn.OwningProcess -Force
    }
    $logDir = Join-Path $Root ".local-logs"
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $out = Join-Path $logDir "backend.out.log"
    $err = Join-Path $logDir "backend.err.log"
    Start-Process -FilePath "powershell.exe" `
        -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $BackendScript) `
        -WorkingDirectory $Root `
        -WindowStyle Hidden `
        -RedirectStandardOutput $out `
        -RedirectStandardError $err | Out-Null
}

function Wait-BackendReady {
    $deadline = (Get-Date).AddMinutes(3)
    while ((Get-Date) -lt $deadline) {
        try {
            $loginBody = @{ username = "admin"; password = "admin-123456" } | ConvertTo-Json -Compress
            $login = Invoke-RestMethod -Method Post `
                -Uri "http://127.0.0.1:8080/api/auth/login" `
                -ContentType "application/json" `
                -Body $loginBody `
                -TimeoutSec 5
            $headers = @{}
            $headers[$login.tokenName] = $login.tokenValue
            $spine = Invoke-RestMethod -Method Get `
                -Uri "http://127.0.0.1:8080/api/knowledge/graph/spine" `
                -Headers $headers `
                -TimeoutSec 15
            $nodeCount = @($spine.nodes).Count
            $edgeCount = @($spine.edges).Count
            if ($nodeCount -gt 0 -and $edgeCount -gt 0) {
                return [pscustomobject]@{
                    tenantId = $login.tenantId
                    nodeCount = $nodeCount
                    edgeCount = $edgeCount
                }
            }
        } catch {
        }
        Start-Sleep -Seconds 3
    }
    throw "Backend did not become ready after reseed"
}

$sql = @"
DELETE FROM knowledge_relation WHERE evidence_summary LIKE '$SeedTag';
DELETE FROM knowledge_point WHERE source_summary LIKE '$SeedTag';
SELECT
  (SELECT COUNT(*) FROM knowledge_point WHERE source_summary LIKE '$SeedTag') AS point_count,
  (SELECT COUNT(*) FROM knowledge_relation WHERE evidence_summary LIKE '$SeedTag') AS relation_count;
"@
Invoke-MysqlSql $sql

if ($RestartBackend) {
    Restart-BackendProcess
    $status = Wait-BackendReady
    $status | ConvertTo-Json -Depth 5
}
