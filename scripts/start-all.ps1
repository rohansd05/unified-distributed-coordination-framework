<#
.SYNOPSIS
    Starts the UDCF demo: the backend (Spring Boot, port 8080) and the frontend (Vite dev server, port 5173).
.DESCRIPTION
    Checks Java 21, Node 22 and that both ports are free, starts both servers as hidden background
    processes that log to %TEMP%\udcf-demo, saves their PIDs for stop-all.ps1, waits until both are
    ready and opens the browser. Windows PowerShell 5.1; nothing to install.
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1 -NoBrowser
#>
param([switch]$NoBrowser)

# Mirror application.yml (server.port ${PORT:8080}) and vite.config.js (server.port, strictPort).
$BackendPort = 8080
$FrontendPort = 5173
$TimeoutSec = 120
$Api = "http://localhost:$BackendPort"
$Ui = "http://localhost:$FrontendPort"
$Root = Split-Path -Parent $PSScriptRoot
$LogDir = Join-Path $env:TEMP 'udcf-demo'
$PidFile = Join-Path $LogDir 'pids.json'
$BackendLog = Join-Path $LogDir 'backend.log'
$FrontendLog = Join-Path $LogDir 'frontend.log'
$ProgressPreference = 'SilentlyContinue'

function Fail([string]$Message) {
    Write-Host "start-all: $Message" -ForegroundColor Red
    exit 1
}

function Get-Listener([int]$Port) {
    Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
}

function Show-Tail([string]$Log) {
    if (Test-Path $Log) {
        Write-Host "Last lines of ${Log}:"
        try { Get-Content $Log -Tail 15 | ForEach-Object { Write-Host "  | $_" } } catch { Write-Host '  (log not readable yet)' }
    }
}

function Wait-Ready([string]$What, [scriptblock]$Ready, $Process, [string]$Log) {
    $started = Get-Date
    Write-Host -NoNewline "Waiting for $What (up to $TimeoutSec s) "
    while (((Get-Date) - $started).TotalSeconds -lt $TimeoutSec) {
        if (& $Ready) {
            Write-Host (" ready after {0:N0} s" -f ((Get-Date) - $started).TotalSeconds) -ForegroundColor Green
            return
        }
        if ($Process.HasExited) {
            Write-Host ''
            Show-Tail $Log
            Fail "$What stopped during start-up. Full log: $Log. Run scripts\stop-all.ps1 before trying again."
        }
        Write-Host -NoNewline '.'
        Start-Sleep -Seconds 2
    }
    Write-Host ''
    Show-Tail $Log
    Fail "$What was not ready after $TimeoutSec s. Full log: $Log. Run scripts\stop-all.ps1 before trying again."
}

# 1. Prerequisites. mvnw uses JAVA_HOME when it is set, so check that java, not the one on PATH.
$java = 'java'
if ($env:JAVA_HOME) { $java = Join-Path $env:JAVA_HOME 'bin\java.exe' }
if (-not (Get-Command $java -ErrorAction SilentlyContinue)) { Fail "Java not found ($java). Install JDK 21 and set JAVA_HOME." }
$javaText = (& $java -version 2>&1 | ForEach-Object { "$_" }) -join ' '
if ($javaText -notmatch 'version "21[".]') { Fail "Java 21 is required; $java reports: $javaText" }
Write-Host "Java 21 OK ($java)"

if (-not (Get-Command node -ErrorAction SilentlyContinue)) { Fail 'Node.js not found on PATH. Install Node 22.' }
$nodeText = "$(& node --version)"
if ($nodeText -notmatch '^v22\.') { Fail "Node 22 is required; node --version reports: $nodeText" }
Write-Host "Node 22 OK ($nodeText)"

if (-not (Test-Path (Join-Path $Root 'frontend\node_modules'))) { Fail 'frontend\node_modules is missing. Run once: cd frontend; npm install' }

foreach ($port in $BackendPort, $FrontendPort) {
    $listener = Get-Listener $port
    if ($listener) {
        $owner = (Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue).ProcessName
        Fail "port $port is already in use by $owner (PID $($listener.OwningProcess)). Stop it first, for example with scripts\stop-all.ps1."
    }
}

# 2. Start both servers hidden; cmd.exe sends stdout and stderr to one log file.
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$backend = Start-Process -FilePath 'cmd.exe' -ArgumentList "/c `"mvnw.cmd spring-boot:run > `"$BackendLog`" 2>&1`"" `
    -WorkingDirectory (Join-Path $Root 'backend') -WindowStyle Hidden -PassThru
$frontend = Start-Process -FilePath 'cmd.exe' -ArgumentList "/c `"npm run dev > `"$FrontendLog`" 2>&1`"" `
    -WorkingDirectory (Join-Path $Root 'frontend') -WindowStyle Hidden -PassThru

# Saved before waiting, so stop-all.ps1 works even if start-up fails. The start time guards against PID reuse.
@{
    backend  = @{ pid = $backend.Id; started = $backend.StartTime.ToString('o') }
    frontend = @{ pid = $frontend.Id; started = $frontend.StartTime.ToString('o') }
} | ConvertTo-Json | Set-Content -Path $PidFile -Encoding UTF8
Write-Host "Started backend (PID $($backend.Id)) and frontend (PID $($frontend.Id)); PIDs saved to $PidFile"

# 3. Wait for the backend health endpoint, then for the Vite port.
Wait-Ready 'the backend' { try { (Invoke-RestMethod "$Api/api/system/health" -TimeoutSec 2).status -eq 'UP' } catch { $false } } $backend $BackendLog
Wait-Ready 'the frontend' { [bool](Get-Listener $FrontendPort) } $frontend $FrontendLog

if (-not $NoBrowser) { Start-Process $Ui }

Write-Host ''
Write-Host 'UDCF demo is running.' -ForegroundColor Green
Write-Host "  UI        $Ui"
Write-Host "  API       $Api"
Write-Host "  Logs      $BackendLog"
Write-Host "            $FrontendLog"
Write-Host '  Reset     scripts\reset-cluster.ps1'
Write-Host '  Stop      scripts\stop-all.ps1'
