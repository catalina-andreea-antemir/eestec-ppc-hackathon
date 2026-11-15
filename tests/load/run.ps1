<#
.SYNOPSIS
  Ruleaza un scenariu k6 FileGrid (F8) in Docker, fara instalare locala.

.EXAMPLE
  .\tests\load\run.ps1 smoke
  .\tests\load\run.ps1 meter-readings -Short
  .\tests\load\run.ps1 peak -- --vus 10          # argumentele de dupa -- ajung la k6

.NOTES
  Parolele se citesc din .env (FILEGRID_USER_*_PASSWORD) si se dau containerului ca variabile de mediu
  (doar numele variabilei apare in linia de comanda docker; valoarea nu se afiseaza si nu se scrie in fisiere).
  Implicit k6 ruleaza in reteaua Docker filegrid_storage-net si loveste nginx-ul: https://nginx/api/v1
  (la peak, citirile merg direct la http://api:8080/api/v1, ca meter-simulator).
  Cu -Localhost loveste https://host.docker.internal/api/v1 (acelasi nginx, prin portul 443 publicat).
#>
param(
  [Parameter(Mandatory = $true, Position = 0)]
  [ValidateSet('smoke', 'meter-readings', 'mixed', 'peak', 'security', 'storm', 'big-upload')]
  [string]$Scenario,
  [switch]$Short,
  [switch]$Localhost,
  [switch]$Dashboard,
  [Parameter(ValueFromRemainingArguments = $true)]
  [string[]]$K6Args
)

$ErrorActionPreference = 'Stop'
$K6Image = 'grafana/k6:1.3.0'
$here = $PSScriptRoot
$root = (Resolve-Path (Join-Path $here '..\..')).Path
$envFile = Join-Path $root '.env'
if (-not (Test-Path $envFile)) { throw ".env lipseste in $root (porneste stack-ul cu scripts\start.ps1)." }

$names = @('FILEGRID_USER_DISPECER_PASSWORD', 'FILEGRID_USER_TEREN_PASSWORD', 'FILEGRID_USER_RACORDARI_PASSWORD',
           'FILEGRID_USER_CONTOR_PASSWORD', 'FILEGRID_USER_ADMIN_PASSWORD')
foreach ($line in Get-Content $envFile) {
  if ($line -match '^\s*(FILEGRID_USER_[A-Z]+_PASSWORD)\s*=\s*(.*)$') {
    $v = $Matches[2].Trim().Trim('"').Trim("'")
    Set-Item -Path "Env:$($Matches[1])" -Value $v
  }
}
foreach ($n in $names) {
  if (-not (Get-Item -Path "Env:$n" -ErrorAction SilentlyContinue)) { throw "$n lipseste din .env" }
}

New-Item -ItemType Directory -Force (Join-Path $here 'results') | Out-Null

$dockerArgs = @('run', '--rm', '-i', '-v', "${here}:/scripts", '-w', '/scripts')
if ($Localhost) {
  $dockerArgs += @('--add-host', 'host.docker.internal:host-gateway', '-e', 'BASE_URL=https://host.docker.internal/api/v1')
} else {
  # storage-net contine si nginx si api: utilizatorii trec prin nginx, iar la peak citirile merg direct la api,
  # ca meter-simulator (vezi README).
  $dockerArgs += @('--network', 'filegrid_storage-net', '-e', 'BASE_URL=https://nginx/api/v1')
  if ($Scenario -eq 'peak' -and -not $env:METER_BASE_URL) { $dockerArgs += @('-e', 'METER_BASE_URL=http://api:8080/api/v1') }
}
if ($env:METER_BASE_URL) { $dockerArgs += @('-e', 'METER_BASE_URL') }
foreach ($n in $names) { $dockerArgs += @('-e', $n) }   # doar numele: Docker ia valoarea din mediul procesului
if ($Short) { $dockerArgs += @('-e', 'SHORT=1') }
if ($Dashboard) {
  # Dashboard-ul web al k6, live pe http://localhost:5665 cat ruleaza testul; la final, raport HTML in results/.
  $stamp = Get-Date -Format 'yyyy-MM-ddTHH-mm-ss'
  # storage-net e "internal" si Docker nu publica porturi acolo; filegrid_public (doar nginx) permite portul.
  if (-not $Localhost) { $dockerArgs += @('--network', 'filegrid_public') }
  $dockerArgs += @('-p', '127.0.0.1:5665:5665', '-e', 'K6_WEB_DASHBOARD=true', '-e', 'K6_WEB_DASHBOARD_HOST=0.0.0.0',
                   '-e', 'K6_WEB_DASHBOARD_PERIOD=2s', '-e', "K6_WEB_DASHBOARD_EXPORT=results/$Scenario-$stamp-dashboard.html")
}
foreach ($n in @('K6_DEBUG_ERRORS', 'RUN_ID', 'PEAK_RATE', 'MIXED_RATE', 'METER_RATE', 'DURATION', 'DUP_PCT', 'STORM_FACTOR', 'BIG_SIZE_MB')) {
  if (Get-Item -Path "Env:$n" -ErrorAction SilentlyContinue) { $dockerArgs += @('-e', $n) }
}
$dockerArgs += @($K6Image, 'run')
if ($K6Args) { $dockerArgs += $K6Args }
$dockerArgs += "$Scenario.js"

Write-Host "k6 $Scenario ($K6Image) -> rezultate in tests\load\results\"
if ($Dashboard) { Write-Host "Dashboard live: http://localhost:5665 (deschide-l in browser cat ruleaza testul)" }
# k6 scrie progresul pe stderr; in Windows PowerShell 5.1 cu 'Stop' asta ar opri scriptul.
$ErrorActionPreference = 'Continue'
& docker @dockerArgs 2>&1 | ForEach-Object { "$_" }
exit $LASTEXITCODE
