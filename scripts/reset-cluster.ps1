<#
.SYNOPSIS
    Resets the UDCF cluster to a clean slate, safely during a demo.
.DESCRIPTION
    POST /api/cluster/reset answers 409 while any module is BUSY (for example the recovery election
    right after a node recovers). This script waits until no module is BUSY, then posts the reset and
    retries on 409. Windows PowerShell 5.1; nothing to install.
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\reset-cluster.ps1
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\reset-cluster.ps1 -Api http://localhost:8080
#>
param([string]$Api = 'http://localhost:8080')

$BusyWaitSec = 60
$MaxTries = 40
$DelayMs = 500
$ProgressPreference = 'SilentlyContinue'

function Fail([string]$Message) {
    Write-Host "reset-cluster: $Message" -ForegroundColor Red
    exit 1
}

function Get-Busy {
    # Keep the parentheses: without them PS 5.1 pipes the JSON array as one object and lists every id.
    $busy = @((Invoke-RestMethod "$Api/api/modules" -TimeoutSec 5) | Where-Object { $_.status -eq 'BUSY' } | ForEach-Object { $_.id })
    , $busy
}

try { $null = Invoke-RestMethod "$Api/api/system/health" -TimeoutSec 5 }
catch { Fail "the backend at $Api is not reachable. Start it with scripts\start-all.ps1." }

# 1. Wait until no module is BUSY.
$started = Get-Date
$lastBusy = ''
while ($true) {
    try { $busy = Get-Busy } catch { Fail "GET $Api/api/modules failed: $($_.Exception.Message)" }
    if ($busy.Count -eq 0) { break }
    $now = $busy -join ', '
    if ($now -ne $lastBusy) { Write-Host "Waiting: busy module(s): $now"; $lastBusy = $now }
    if (((Get-Date) - $started).TotalSeconds -ge $BusyWaitSec) { Fail "still busy after $BusyWaitSec s: $now. Let the action finish, then run this again." }
    Start-Sleep -Milliseconds $DelayMs
}

# 2. Post the reset; 409 means a module became BUSY again in between.
for ($try = 1; $try -le $MaxTries; $try++) {
    try {
        $response = Invoke-WebRequest "$Api/api/cluster/reset" -Method Post -UseBasicParsing -TimeoutSec 15
        Write-Host "Cluster reset: HTTP $([int]$response.StatusCode) on try $try of $MaxTries." -ForegroundColor Green
        exit 0
    } catch {
        $code = 0
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        if ($code -ne 409) { Fail "POST $Api/api/cluster/reset failed (HTTP $code): $($_.Exception.Message)" }
        Write-Host "Try $try of ${MaxTries}: HTTP 409 (a module is busy); retrying in $DelayMs ms."
        Start-Sleep -Milliseconds $DelayMs
    }
}
Fail "the reset still answered 409 after $MaxTries tries. Wait for the running action to finish, then run this again."
