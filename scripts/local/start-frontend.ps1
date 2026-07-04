$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$Frontend = Join-Path $Root "frontend"

Push-Location $Frontend
try {
    if (-not (Test-Path "node_modules")) {
        throw "frontend/node_modules is missing; startup will not run npm install automatically"
    }
    npm run dev
} finally {
    Pop-Location
}
