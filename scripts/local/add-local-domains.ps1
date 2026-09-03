# One-shot elevation: append Math Agent local domain mappings to the hosts file.
# Purpose: expose the frontend console as http://mathagent.local and the backend
# API as http://api.mathagent.local instead of raw 127.0.0.1 ports.
$lines = @"

# Math Agent local domains (frontend console + backend API)
127.0.0.1 mathagent.local
127.0.0.1 api.mathagent.local
"@
$hostsPath = "$env:SystemRoot\System32\drivers\etc\hosts"
if (-not (Select-String -Path $hostsPath -Pattern 'mathagent\.local' -Quiet)) {
    # ASCII keeps the hosts file encoding stable for the system resolver.
    Add-Content -Path $hostsPath -Value $lines -Encoding ASCII
}
Write-Output "hosts-updated"
