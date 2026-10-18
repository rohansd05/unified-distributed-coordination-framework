<#
.SYNOPSIS
    Stops the UDCF demo started by start-all.ps1. Safe to run more than once.
.DESCRIPTION
    Stops the saved backend and frontend PIDs as process trees (taskkill /T /F), then anything still
    listening on ports 8080 and 5173. Windows PowerShell 5.1; nothing to install.
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1
#>
param()

# Mirror application.yml (server.port ${PORT:8080}) and vite.config.js (server.port).
$Ports = 8080, 5173
$PidFile = Join-Path $env:TEMP 'udcf-demo\pids.json'
$script:stopped = 0

function Stop-Tree([int]$Id, [string]$Label) {
    $null = & taskkill.exe /PID $Id /T /F 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Stopped $Label (PID $Id) with its child processes."
        $script:stopped++
    } else {
        Write-Host "Could not stop $Label (PID $Id): taskkill exit code $LASTEXITCODE." -ForegroundColor Yellow
    }
}

# 1. The process trees start-all.ps1 saved.
if (Test-Path $PidFile) {
    $saved = $null
    try { $saved = Get-Content $PidFile -Raw | ConvertFrom-Json } catch { Write-Host "Ignoring unreadable $PidFile." -ForegroundColor Yellow }
    foreach ($name in 'backend', 'frontend') {
        $entry = $saved.$name
        if (-not $entry) { continue }
        $process = Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
        if (-not $process) { Write-Host "The $name (PID $($entry.pid)) was not running."; continue }
        $started = try { $process.StartTime.ToString('o') } catch { '' }
        # A reused PID belongs to another program: leave it alone.
        if ($started -ne $entry.started) { Write-Host "PID $($entry.pid) is now $($process.ProcessName), not the demo $name; left alone."; continue }
        Stop-Tree $entry.pid $name
    }
    Remove-Item $PidFile -ErrorAction SilentlyContinue
}

# 2. Anything still listening on the demo ports (for example a server started by hand).
foreach ($port in $Ports) {
    $owners = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique)
    foreach ($id in $owners) {
        $process = Get-Process -Id $id -ErrorAction SilentlyContinue
        # PIDs 0 and 4 are Windows itself; a gone process just has a stale socket entry.
        if ($id -le 4 -or -not $process) { continue }
        Stop-Tree $id "$($process.ProcessName) listening on port $port"
    }
}

if ($script:stopped -eq 0) { Write-Host 'Nothing to stop: the UDCF demo is not running.' }
else { Write-Host "Done: stopped $($script:stopped) process tree(s)." -ForegroundColor Green }
