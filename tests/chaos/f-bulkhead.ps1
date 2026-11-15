<#
.SYNOPSIS
  F9 f) Bulkhead (C9): varf de citiri de contor (k6 din Docker, bulkhead.js) in timp ce teren incarca avarii si
  dispecerul cauta. Asteptat: 429 CATEGORY_SATURATED doar pe METER_READING; avariile 201, cautarile 200.
.DESCRIPTION
  k6 (grafana/k6:1.3.0, ca la F8) ruleaza in reteaua filegrid_storage-net: furtuna de citiri merge direct la
  http://api:8080 (ca meter-simulator), avariile si cautarile prin nginx (https://nginx), ca utilizatorii.
.EXAMPLE
  .\tests\chaos\f-bulkhead.ps1
  .\tests\chaos\f-bulkhead.ps1 -Vus 200 -Seconds 40
#>
param([int]$Vus = 0, [int]$Seconds = 30)
. "$PSScriptRoot\lib\chaos.ps1"
# Implicit 120 VU pe replica api: bulkhead-ul METER_READING are 50 de locuri pe fiecare instanta (C9), deci cu 3 replici
# (C14) e nevoie de peste 150 de cereri simultane ca sa se umple.
if ($Vus -le 0) { $Vus = 120 * (Get-ApiReplicas) }
Write-Title "f) Bulkhead: varf METER_READING ($Vus VU, $Seconds s) + avarii si cautari in paralel"

$K6Image = 'grafana/k6:1.3.0'
try {
  Write-Step 'pregatire: storage-urile sanatoase'
  foreach ($st in 'S3', 'DB') { [void](Assert-StorageState $st 'UP' 'CLOSED' 60) }

  Write-Step "stric: furtuna de citiri (k6 $K6Image)"
  $tests = (Resolve-Path (Join-Path $ChaosDir '..')).Path
  $names = @()
  foreach ($u in 'dispecer', 'teren', 'racordari', 'contor', 'admin') {
    $n = "FILEGRID_USER_$($u.ToUpperInvariant())_PASSWORD"
    if ($Passwords[$u]) { Set-Item -Path "Env:$n" -Value $Passwords[$u]; $names += $n }
  }
  $env:RUN_ID = $RunId; $env:STORM_VUS = "$Vus"; $env:STORM_SEC = "$Seconds"
  $dockerArgs = @('run', '--rm', '-i', '--network', 'filegrid_storage-net', '-v', "${tests}:/tests", '-w', '/tests/chaos',
    '-e', 'BASE_URL=https://nginx/api/v1', '-e', 'METER_BASE_URL=http://api:8080/api/v1', '-e', 'RUN_ID', '-e', 'STORM_VUS', '-e', 'STORM_SEC')
  foreach ($n in $names) { $dockerArgs += @('-e', $n) }   # doar numele; valoarea vine din mediul procesului
  $dockerArgs += @($K6Image, 'run', '--quiet', 'bulkhead.js')
  $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try { $out = & docker @dockerArgs 2>&1 | ForEach-Object { "$_" }; $k6code = $LASTEXITCODE } finally { $ErrorActionPreference = $old }
  foreach ($n in $names) { Remove-Item "Env:$n" -ErrorAction SilentlyContinue }
  $out | Where-Object { $_ -match 'level=(warning|error)' } | Select-Object -First 5 | ForEach-Object { Write-Info $_ }
  $line = $out | Where-Object { $_ -match '^CHAOS_BULKHEAD ' } | Select-Object -Last 1
  if (-not $line) { throw "k6 nu a produs rezumatul (cod $k6code): $(($out | Select-Object -Last 10) -join ' | ')" }
  $r = ($line -replace '^CHAOS_BULKHEAD ', '') | ConvertFrom-Json

  Write-Step 'verific: izolarea pe categorie'
  Write-Info "citiri: $($r.meter201) x 201, $($r.meter429) x 429, alte coduri $($r.meterOther); p95 $($r.p95.meter) ms"
  Write-Info "avarii: $($r.outageTotal) trimise, $($r.outageFail) esuate, $($r.outage429) x 429; p95 $($r.p95.outage) ms"
  Write-Info "cautari: $($r.searchTotal) trimise, $($r.searchFail) esuate, $($r.search429) x 429; p95 $($r.p95.search) ms"
  [void](Assert-Check 'METER_READING saturata: 429 primite' ($r.meter429 -gt 0) "$($r.meter429) x 429")
  [void](Assert-Check '429 la citiri au code CATEGORY_SATURATED si Retry-After' ($r.meter429WrongCode -eq 0) "$($r.meter429WrongCode) altfel")
  [void](Assert-Check 'citirile acceptate in continuare (201) in timpul varfului' ($r.meter201 -gt 0) "$($r.meter201) x 201")
  [void](Assert-Check 'avariile (OUTAGE_RECORD) trec: toate 201, niciun 429' ($r.outageTotal -gt 0 -and $r.outageFail -eq 0 -and $r.outage429 -eq 0) "$($r.outageTotal - $r.outageFail)/$($r.outageTotal)")
  [void](Assert-Check 'cautarile dispecerului trec: toate 200, niciun 429' ($r.searchTotal -gt 0 -and $r.searchFail -eq 0 -and $r.search429 -eq 0) "$($r.searchTotal - $r.searchFail)/$($r.searchTotal)")
  [void](Assert-Check 'pragurile k6 trecute' ($k6code -eq 0) "cod iesire k6 $k6code")

  Write-Step 'verific revenirea: dupa varf, un lot nou trece imediat'
  $b = New-MeterBatch "$RunId-BAN-0009999" ([datetime]::SpecifyKind([datetime]'2026-10-01', 'Utc')) 96 'Banat'
  $after = Send-MeterBatch $b
  [void](Assert-Check 'lot dupa varf -> 201' ($after.Status -eq 201) (Format-Response $after))
} catch {
  [void](Assert-Check 'scenariul f rulat fara exceptii' $false (Format-Error $_))
} finally {
  Remove-Item Env:RUN_ID, Env:STORM_VUS, Env:STORM_SEC -ErrorAction SilentlyContinue
}
exit (Complete-Scenario 'f-bulkhead')
