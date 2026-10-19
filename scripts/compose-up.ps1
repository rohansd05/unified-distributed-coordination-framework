<#
.SYNOPSIS
    Starts the full UDCF system in Docker: backend, frontend (nginx), Prometheus and Grafana.
.DESCRIPTION
    Checks that Docker is running and that ports 8080, 5173, 9090 and 3000 are free, runs
    docker compose -f infra/docker-compose.yml up -d --build, then waits until the backend is
    healthy (directly and through the frontend's proxy), Prometheus scrapes it and Grafana is
    ready, and prints the URLs. The first run builds both images and can take several minutes.
    Windows PowerShell 5.1; needs Docker Desktop.
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\compose-up.ps1
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\compose-up.ps1 -NoBrowser
#>
param([switch]$NoBrowser)

# Mirror the host ports in infra/docker-compose.yml.
$Ports = [ordered]@{ backend = 8080; frontend = 5173; prometheus = 9090; grafana = 3000 }
$TimeoutSec = 180
$Root = Split-Path -Parent $PSScriptRoot
$Compose = Join-Path $Root 'infra\docker-compose.yml'
$Api = "http://localhost:$($Ports.backend)"
$Ui = "http://localhost:$($Ports.frontend)"
$Prometheus = "http://localhost:$($Ports.prometheus)"
$Grafana = "http://localhost:$($Ports.grafana)"
$Dashboard = "$Grafana/d/udcf-cluster/udcf-cluster"
$ProgressPreference = 'SilentlyContinue'

function Fail([string]$Message) {
    Write-Host "compose-up: $Message" -ForegroundColor Red
    exit 1
}

function Wait-Ready([string]$What, [string]$Service, [scriptblock]$Ready) {
    $started = Get-Date
    Write-Host -NoNewline "Waiting for $What (up to $TimeoutSec s) "
    while (((Get-Date) - $started).TotalSeconds -lt $TimeoutSec) {
        if (& $Ready) {
            Write-Host (" ready after {0:N0} s" -f ((Get-Date) - $started).TotalSeconds) -ForegroundColor Green
            return
        }
        Write-Host -NoNewline '.'
        Start-Sleep -Seconds 2
    }
    Write-Host ''
    Write-Host "Last log lines of the $Service container:"
    & docker compose -f $Compose logs --tail 20 $Service
    Fail "$What was not ready after $TimeoutSec s. Stop everything with scripts\compose-down.ps1."
}

# 1. Docker must be installed and its engine running.
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail 'docker not found on PATH. Install Docker Desktop.' }
$null = & docker info --format '{{.ServerVersion}}' 2>&1
if ($LASTEXITCODE -ne 0) { Fail 'the Docker engine is not running. Start Docker Desktop, wait until it says "Engine running", then try again.' }
Write-Host 'Docker engine OK'

# 2. Ports must be free, unless this compose project already holds them (then up -d just updates it).
$running = @(& docker compose -f $Compose ps -q --status running 2>$null)
if ($running.Count -gt 0) {
    Write-Host "The UDCF compose project is already running ($($running.Count) containers); updating it."
} else {
    foreach ($name in $Ports.Keys) {
        $port = $Ports[$name]
        $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($listener) {
            $owner = (Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue).ProcessName
            Fail "port $port ($name) is already in use by $owner (PID $($listener.OwningProcess)). If the dev-mode demo is running, stop it with scripts\stop-all.ps1."
        }
    }
    Write-Host 'Ports 8080, 5173, 9090 and 3000 are free'
}

# 3. Build and start everything in the background.
& docker compose -f $Compose up -d --build
if ($LASTEXITCODE -ne 0) { Fail "docker compose up failed (exit code $LASTEXITCODE). See the output above." }

# 4. Wait for each service.
Wait-Ready 'the backend' 'backend' { try { (Invoke-RestMethod "$Api/api/system/health" -TimeoutSec 2).status -eq 'UP' } catch { $false } }
Wait-Ready 'the frontend and its API proxy' 'frontend' { try { (Invoke-RestMethod "$Ui/api/system/health" -TimeoutSec 2).status -eq 'UP' } catch { $false } }
Wait-Ready 'Prometheus scraping the backend' 'prometheus' {
    try { @((Invoke-RestMethod "$Prometheus/api/v1/targets" -TimeoutSec 2).data.activeTargets | Where-Object { $_.health -eq 'up' }).Count -gt 0 } catch { $false }
}
Wait-Ready 'Grafana' 'grafana' { try { (Invoke-RestMethod "$Grafana/api/health" -TimeoutSec 2).database -eq 'ok' } catch { $false } }

if (-not $NoBrowser) {
    Start-Process $Ui
    Start-Process $Dashboard
}

Write-Host ''
Write-Host 'UDCF is running in Docker.' -ForegroundColor Green
Write-Host "  UI          $Ui"
Write-Host "  API         $Api"
Write-Host "  Grafana     $Dashboard  (anonymous viewer)"
Write-Host "  Prometheus  $Prometheus"
Write-Host "  Logs        docker compose -f infra\docker-compose.yml logs -f backend"
Write-Host "  Reset       scripts\reset-cluster.ps1"
Write-Host "  Stop        scripts\compose-down.ps1"
