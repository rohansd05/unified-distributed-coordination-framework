<#
.SYNOPSIS
    Stops the UDCF Docker system started by compose-up.ps1. Safe to run more than once.
.DESCRIPTION
    Runs docker compose -f infra/docker-compose.yml down -v --remove-orphans, which removes the
    containers, their network and their anonymous volumes (Prometheus data only; nothing else is
    stored, R3). Built images are kept, so the next compose-up starts faster. Windows PowerShell 5.1.
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\compose-down.ps1
#>
param()

$Root = Split-Path -Parent $PSScriptRoot
$Compose = Join-Path $Root 'infra\docker-compose.yml'

function Fail([string]$Message) {
    Write-Host "compose-down: $Message" -ForegroundColor Red
    exit 1
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail 'docker not found on PATH.' }
$null = & docker info --format '{{.ServerVersion}}' 2>&1
if ($LASTEXITCODE -ne 0) { Fail 'the Docker engine is not running, so there is nothing to stop.' }

& docker compose -f $Compose down -v --remove-orphans
if ($LASTEXITCODE -ne 0) { Fail "docker compose down failed (exit code $LASTEXITCODE). See the output above." }

$left = @(& docker compose -f $Compose ps -a -q 2>$null)
if ($left.Count -gt 0) { Fail "$($left.Count) UDCF container(s) are still present. Check with: docker ps -a" }
Write-Host 'Done: no UDCF containers are left.' -ForegroundColor Green
