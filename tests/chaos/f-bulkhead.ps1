<#
.SYNOPSIS
  F9 f) Bulkhead (C9): meter reading spike (k6 in Docker, bulkhead.js) while the field user uploads outages and the
  dispatcher searches. Expected: 429 CATEGORY_SATURATED only on METER_READING; outages 201, searches 200.
.DESCRIPTION
  k6 (grafana/k6:1.3.0, as in F8) runs on the filegrid_storage-net network: the reading storm goes straight to
  http://api:8080 (like meter-simulator), outages and searches go through nginx (https://nginx), like real users.
.EXAMPLE
  .\tests\chaos\f-bulkhead.ps1
  .\tests\chaos\f-bulkhead.ps1 -Vus 200 -Seconds 40
#>
param([int]$Vus = 0, [int]$Seconds = 30)
. "$PSScriptRoot\lib\chaos.ps1"
# Default 120 VUs per api replica: the METER_READING bulkhead has 50 slots per instance (C9), so with 3 replicas
# (C14) it takes more than 150 concurrent requests to fill it.
if ($Vus -le 0) { $Vus = 120 * (Get-ApiReplicas) }
Write-Title "f) Bulkhead: METER_READING spike ($Vus VU, $Seconds s) + outages and searches in parallel"

$K6Image = 'grafana/k6:1.3.0'
try {
  Write-Step 'setup: storage backends healthy'
  foreach ($st in 'S3', 'DB') { [void](Assert-StorageState $st 'UP' 'CLOSED' 60) }

  Write-Step "break: reading storm (k6 $K6Image)"
  $tests = (Resolve-Path (Join-Path $ChaosDir '..')).Path
  $names = @()
  foreach ($u in 'dispecer', 'teren', 'racordari', 'contor', 'admin') {
    $n = "FILEGRID_USER_$($u.ToUpperInvariant())_PASSWORD"
    if ($Passwords[$u]) { Set-Item -Path "Env:$n" -Value $Passwords[$u]; $names += $n }
  }
  $env:RUN_ID = $RunId; $env:STORM_VUS = "$Vus"; $env:STORM_SEC = "$Seconds"
  $dockerArgs = @('run', '--rm', '-i', '--network', 'filegrid_storage-net', '-v', "${tests}:/tests", '-w', '/tests/chaos',
    '-e', 'BASE_URL=https://nginx/api/v1', '-e', 'METER_BASE_URL=http://api:8080/api/v1', '-e', 'RUN_ID', '-e', 'STORM_VUS', '-e', 'STORM_SEC')
  foreach ($n in $names) { $dockerArgs += @('-e', $n) }   # name only; the value comes from the process environment
  $dockerArgs += @($K6Image, 'run', '--quiet', 'bulkhead.js')
  $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try { $out = & docker @dockerArgs 2>&1 | ForEach-Object { "$_" }; $k6code = $LASTEXITCODE } finally { $ErrorActionPreference = $old }
  foreach ($n in $names) { Remove-Item "Env:$n" -ErrorAction SilentlyContinue }
  $out | Where-Object { $_ -match 'level=(warning|error)' } | Select-Object -First 5 | ForEach-Object { Write-Info $_ }
  $line = $out | Where-Object { $_ -match '^CHAOS_BULKHEAD ' } | Select-Object -Last 1
  if (-not $line) { throw "k6 produced no summary (exit $k6code): $(($out | Select-Object -Last 10) -join ' | ')" }
  $r = ($line -replace '^CHAOS_BULKHEAD ', '') | ConvertFrom-Json

  Write-Step 'checking per-category isolation'
  Write-Info "readings: $($r.meter201) x 201, $($r.meter429) x 429, other codes $($r.meterOther); p95 $($r.p95.meter) ms"
  Write-Info "outages: $($r.outageTotal) sent, $($r.outageFail) failed, $($r.outage429) x 429; p95 $($r.p95.outage) ms"
  Write-Info "searches: $($r.searchTotal) sent, $($r.searchFail) failed, $($r.search429) x 429; p95 $($r.p95.search) ms"
  [void](Assert-Check 'METER_READING saturated: 429s received' ($r.meter429 -gt 0) "$($r.meter429) x 429")
  [void](Assert-Check 'reading 429s carry code CATEGORY_SATURATED and Retry-After' ($r.meter429WrongCode -eq 0) "$($r.meter429WrongCode) otherwise")
  [void](Assert-Check 'readings still accepted (201) during the spike' ($r.meter201 -gt 0) "$($r.meter201) x 201")
  [void](Assert-Check 'outages (OUTAGE_RECORD) pass: all 201, no 429' ($r.outageTotal -gt 0 -and $r.outageFail -eq 0 -and $r.outage429 -eq 0) "$($r.outageTotal - $r.outageFail)/$($r.outageTotal)")
  [void](Assert-Check 'dispatcher searches pass: all 200, no 429' ($r.searchTotal -gt 0 -and $r.searchFail -eq 0 -and $r.search429 -eq 0) "$($r.searchTotal - $r.searchFail)/$($r.searchTotal)")
  [void](Assert-Check 'k6 thresholds passed' ($k6code -eq 0) "k6 exit code $k6code")

  Write-Step 'checking recovery: after the spike a new batch goes through right away'
  $b = New-MeterBatch "$RunId-BAN-0009999" ([datetime]::SpecifyKind([datetime]'2026-10-01', 'Utc')) 96 'Banat'
  $after = Send-MeterBatch $b
  [void](Assert-Check 'batch after the spike -> 201' ($after.Status -eq 201) (Format-Response $after))
} catch {
  [void](Assert-Check 'scenario f ran without exceptions' $false (Format-Error $_))
} finally {
  Remove-Item Env:RUN_ID, Env:STORM_VUS, Env:STORM_SEC -ErrorAction SilentlyContinue
}
exit (Complete-Scenario 'f-bulkhead')
