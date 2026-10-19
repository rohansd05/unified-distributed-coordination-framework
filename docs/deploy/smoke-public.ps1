# UDCF public smoke run (step 16.1). Windows PowerShell 5.1; no modules to install.
# Run in a separate PowerShell window and close it afterwards:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\docs\deploy\smoke-public.ps1 -Api https://<service>.onrender.com
# Paste the final tables back for docs/deploy/public-deploy.md ("Measured").
param([string]$Api = 'https://udcf-backend.onrender.com')

$Api = $Api.TrimEnd('/')
$Results = New-Object System.Collections.Generic.List[object]

function Add-Result([string]$Check, [int]$Code, [double]$Seconds, [bool]$Ok, [string]$Note) {
    $Results.Add([pscustomobject]@{ Check = $Check; HTTP = $Code; Seconds = [math]::Round($Seconds, 2); OK = $Ok; Note = $Note })
    $mark = if ($Ok) { 'OK  ' } else { 'FAIL' }
    Write-Host ("{0} {1,-34} HTTP {2,3}  {3,7:N2} s  {4}" -f $mark, $Check, $Code, $Seconds, $Note)
}

# One HTTP call: status code, parsed JSON (if any), seconds, error text. Never throws.
function Invoke-Api([string]$Method, [string]$Path, $Body = $null, [int]$TimeoutSec = 120) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $code = 0; $data = $null; $err = ''
    try {
        $p = @{ Uri = "$Api$Path"; Method = $Method; TimeoutSec = $TimeoutSec; UseBasicParsing = $true }
        if ($null -ne $Body) { $p.Body = ($Body | ConvertTo-Json -Depth 6 -Compress); $p.ContentType = 'application/json' }
        $resp = Invoke-WebRequest @p
        $code = [int]$resp.StatusCode
        if ($resp.Content) { try { $data = $resp.Content | ConvertFrom-Json } catch { $data = $null } }
    } catch {
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        $err = if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $_.ErrorDetails.Message } else { $_.Exception.Message }
        try { $j = $err | ConvertFrom-Json; if ($j.detail) { $err = $j.detail } } catch { }
        if ($err.Length -gt 140) { $err = $err.Substring(0, 140) + '...' }
    }
    $sw.Stop()
    [pscustomobject]@{ Code = $code; Data = $data; Seconds = $sw.Elapsed.TotalSeconds; Error = $err }
}

# Waits until no module reports BUSY. Returns the seconds waited, or -1 on timeout.
function Wait-NotBusy([int]$MaxSec = 60) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $last = ''
    while ($sw.Elapsed.TotalSeconds -lt $MaxSec) {
        try {
            $busy = @((Invoke-RestMethod "$Api/api/modules" -TimeoutSec 30) | Where-Object { $_.status -eq 'BUSY' } | ForEach-Object { $_.id })
        } catch { $busy = @('(modules not readable)') }
        if ($busy.Count -eq 0) { return $sw.Elapsed.TotalSeconds }
        $now = $busy -join ', '
        if ($now -ne $last) { Write-Host "     waiting, busy: $now"; $last = $now }
        Start-Sleep -Milliseconds 500
    }
    return -1
}

# Clean-slate reset: wait until nothing is BUSY, then POST, retrying on 409 (20 tries, 500 ms apart).
function Reset-Cluster([string]$Label) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    [void](Wait-NotBusy 60)
    $r = $null
    for ($i = 1; $i -le 20; $i++) {
        $r = Invoke-Api 'POST' '/api/cluster/reset'
        if ($r.Code -ne 409) { break }
        Start-Sleep -Milliseconds 500
    }
    $sw.Stop()
    Add-Result $Label $r.Code $sw.Elapsed.TotalSeconds ($r.Code -eq 204) ("try $i of 20 " + $r.Error).Trim()
}

# One action: the HTTP call, then the time until no module is BUSY (async actions finish there).
function Test-Action([string]$Label, [string]$Method, [string]$Path, $Body, [int[]]$Expect) {
    $r = Invoke-Api $Method $Path $Body
    $wait = Wait-NotBusy 90
    $note = if ($r.Error) { $r.Error } else { 'settled after {0:N1} s' -f $wait }
    if ($wait -lt 0) { $note = "still BUSY after 90 s. $note" }
    Add-Result $Label $r.Code $r.Seconds (($Expect -contains $r.Code) -and $wait -ge 0) $note
}

Write-Host "UDCF public smoke run against $Api  ($(Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))"

# 1. Health from cold: retry until UP (a free Render service takes about a minute to spin up).
$sw = [Diagnostics.Stopwatch]::StartNew()
$h = $null; $tries = 0
while ($sw.Elapsed.TotalSeconds -lt 240) {
    $tries++
    $h = Invoke-Api 'GET' '/api/system/health' $null 120
    if ($h.Code -eq 200 -and $h.Data.status -eq 'UP') { break }
    Start-Sleep -Seconds 2
}
$sw.Stop()
$healthOk = ($h.Code -eq 200 -and $h.Data.status -eq 'UP')
Add-Result 'Health from cold' $h.Code $sw.Elapsed.TotalSeconds $healthOk "$tries tries $($h.Error)".Trim()
if (-not $healthOk) { Write-Host 'Backend not healthy; stopping here.' -ForegroundColor Red; $Results | Format-Table -AutoSize; return }
$h2 = Invoke-Api 'GET' '/api/system/health'
Add-Result 'Health warm' $h2.Code $h2.Seconds ($h2.Code -eq 200) ''
$ah = Invoke-Api 'GET' '/actuator/health'
Add-Result 'Actuator health (Render check)' $ah.Code $ah.Seconds ($ah.Code -eq 200) ''
$info = Invoke-Api 'GET' '/api/system/info'
Add-Result 'System info' $info.Code $info.Seconds ($info.Code -eq 200) ''
$cl = Invoke-Api 'GET' '/api/cluster'
$nodeCount = @($cl.Data.nodes).Count
Add-Result 'Cluster' $cl.Code $cl.Seconds ($cl.Code -eq 200) "$nodeCount nodes"

# 2. Modules.
$m = Invoke-Api 'GET' '/api/modules'
Add-Result 'Modules' $m.Code $m.Seconds ($m.Code -eq 200 -and @($m.Data).Count -eq 10) "$(@($m.Data).Count) modules"
foreach ($mod in @($m.Data)) { Write-Host ("     lab {0,2}  {1,-15} {2}" -f $mod.labNumber, $mod.id, $mod.status) }

# 3. One cheap action per experiment, from a clean cluster.
Reset-Cluster 'Reset before actions'
Test-Action 'Exp 1 RMI start'        'POST' '/api/modules/rmi/start' $null @(200)
Test-Action 'Exp 1 RMI ping'         'POST' '/api/modules/rmi/calls' @{ fromNode = 1; targetNode = 2; method = 'PING' } @(200)
Test-Action 'Exp 2 batch on node 1'  'POST' '/api/modules/multithreading/nodes/1/batches' @{ count = 10; type = 'CPU_HASH'; payloadSize = 200 } @(202)
Test-Action 'Exp 4 Bully election'   'POST' '/api/modules/election/elections' @{ algorithm = 'BULLY'; nodeId = 1 } @(202)
Test-Action 'Exp 3 Berkeley round'   'POST' '/api/modules/clocksync/berkeley-rounds' @{} @(202)
Test-Action 'Exp 5 synchronous write' 'POST' '/api/modules/replication/writes' @{ key = 'smoke'; value = '1'; model = 'SYNCHRONOUS' } @(200)
Test-Action 'Exp 6 round-robin run'  'POST' '/api/modules/loadbalancing/runs' @{ strategy = 'ROUND_ROBIN'; requestCount = 20; workUnits = 50; concurrency = 4 } @(202)
Test-Action 'Exp 7 word count'       'POST' '/api/modules/mapreduce/runs' @{ jobId = 'word-count'; inputType = 'SAMPLE' } @(202)
Test-Action 'Exp 8 start'            'POST' '/api/modules/faulttolerance/start' $null @(200, 202)
Test-Action 'Exp 9 MPI start'        'POST' '/api/modules/mpi/start' $null @(200)
Test-Action 'Exp 9 broadcast'        'POST' '/api/modules/mpi/collectives' @{ operation = 'BROADCAST'; count = 10 } @(200)
Test-Action 'Exp 10 matrix start'    'POST' '/api/modules/matrix/start' $null @(200)
Test-Action 'Exp 10 matrix 25x25'    'POST' '/api/modules/matrix/runs' @{ size = 25 } @(200)
$latest = Invoke-Api 'GET' '/api/modules/mapreduce/runs/latest'
Add-Result 'Exp 7 latest run state' $latest.Code $latest.Seconds ($latest.Code -eq 200) "$($latest.Data.state)"
$ld = Invoke-Api 'GET' '/api/cluster'
$leader = @($ld.Data.nodes | Where-Object { @($_.roles) -contains 'LEADER' } | ForEach-Object { $_.id }) -join ','
Add-Result 'Leader after actions' $ld.Code $ld.Seconds ($leader -ne '') "leader: node $leader (expected node $nodeCount)"

# 4. Every scenario, one after another, polled to the end.
Reset-Cluster 'Reset before scenarios'
$sc = Invoke-Api 'GET' '/api/scenarios'
Add-Result 'Scenario list' $sc.Code $sc.Seconds ($sc.Code -eq 200) "$(@($sc.Data).Count) scenarios"
$Verdicts = New-Object System.Collections.Generic.List[object]
foreach ($s in @($sc.Data)) {
    [void](Wait-NotBusy 60)
    $start = $null
    for ($i = 1; $i -le 10; $i++) {
        $start = Invoke-Api 'POST' "/api/scenarios/$($s.id)/runs"
        if ($start.Code -ne 409) { break }
        Start-Sleep -Seconds 1
    }
    if ($start.Code -ne 202) { Add-Result "Scenario $($s.id)" $start.Code $start.Seconds $false $start.Error; continue }
    $runId = $start.Data.runId
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $run = $null
    while ($sw.Elapsed.TotalSeconds -lt 420) {
        Start-Sleep -Seconds 2
        $run = Invoke-Api 'GET' "/api/scenarios/runs/$runId"
        if ($run.Code -eq 200 -and $run.Data.state -ne 'RUNNING') { break }
    }
    $sw.Stop()
    $state = if ($run) { $run.Data.state } else { '' }
    $serverSec = ''
    if ($run.Data.startedAt -and $run.Data.finishedAt) {
        $serverSec = '{0:N1}' -f (([datetime]$run.Data.finishedAt) - ([datetime]$run.Data.startedAt)).TotalSeconds
    }
    $steps = @($run.Data.steps)
    $passedSteps = @($steps | Where-Object { $_.state -eq 'PASSED' }).Count
    Add-Result "Scenario $($s.id)" $run.Code $sw.Elapsed.TotalSeconds ($state -eq 'PASSED') "$state, $passedSteps/$($steps.Count) steps, server $serverSec s"
    $Verdicts.Add([pscustomobject]@{ Scenario = $s.id; State = $state; ServerSeconds = $serverSec; Verdict = $run.Data.verdict })
}

# 5. Summary.
Write-Host ''
Write-Host 'Scenario verdicts:'
$Verdicts | Format-List
Write-Host 'All checks:'
$Results | Format-Table Check, HTTP, Seconds, OK, Note -AutoSize -Wrap | Out-String -Width 220 | Write-Host
$okCount = @($Results | Where-Object { $_.OK }).Count
Write-Host "$okCount of $($Results.Count) checks OK."
