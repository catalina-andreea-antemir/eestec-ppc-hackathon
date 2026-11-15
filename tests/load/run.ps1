<#
.SYNOPSIS
  Runs a FileGrid k6 scenario (F8) in Docker, no local install needed.

.EXAMPLE
  .\tests\load\run.ps1 smoke
  .\tests\load\run.ps1 meter-readings -Short
  .\tests\load\run.ps1 peak -- --vus 10          # arguments after -- are passed to k6

.NOTES
  Passwords are read from .env (FILEGRID_USER_*_PASSWORD) and passed to the container as environment variables
  (only the variable name appears on the docker command line; the value is never printed or written to files).
  By default k6 runs on the filegrid_storage-net Docker network and targets nginx: https://nginx/api/v1
  (in `peak`, readings go straight to http://api:8080/api/v1, like meter-simulator).
  With -Localhost it targets https://host.docker.internal/api/v1 (same nginx, through the published port 443).
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
if (-not (Test-Path $envFile)) { throw ".env is missing in $root (start the stack with scripts\start.ps1)." }

$names = @('FILEGRID_USER_DISPECER_PASSWORD', 'FILEGRID_USER_TEREN_PASSWORD', 'FILEGRID_USER_RACORDARI_PASSWORD',
           'FILEGRID_USER_CONTOR_PASSWORD', 'FILEGRID_USER_ADMIN_PASSWORD')
foreach ($line in Get-Content $envFile) {
  if ($line -match '^\s*(FILEGRID_USER_[A-Z]+_PASSWORD)\s*=\s*(.*)$') {
    $v = $Matches[2].Trim().Trim('"').Trim("'")
    Set-Item -Path "Env:$($Matches[1])" -Value $v
  }
}
foreach ($n in $names) {
  if (-not (Get-Item -Path "Env:$n" -ErrorAction SilentlyContinue)) { throw "$n is missing from .env" }
}

New-Item -ItemType Directory -Force (Join-Path $here 'results') | Out-Null

$dockerArgs = @('run', '--rm', '-i', '-v', "${here}:/scripts", '-w', '/scripts')
if ($Localhost) {
  $dockerArgs += @('--add-host', 'host.docker.internal:host-gateway', '-e', 'BASE_URL=https://host.docker.internal/api/v1')
} else {
  # storage-net holds both nginx and api: users go through nginx, while in `peak` the readings go straight to api,
  # like meter-simulator (see README).
  $dockerArgs += @('--network', 'filegrid_storage-net', '-e', 'BASE_URL=https://nginx/api/v1')
  if ($Scenario -eq 'peak' -and -not $env:METER_BASE_URL) { $dockerArgs += @('-e', 'METER_BASE_URL=http://api:8080/api/v1') }
}
if ($env:METER_BASE_URL) { $dockerArgs += @('-e', 'METER_BASE_URL') }
foreach ($n in $names) { $dockerArgs += @('-e', $n) }   # name only: Docker takes the value from the process environment
if ($Short) { $dockerArgs += @('-e', 'SHORT=1') }
if ($Dashboard) {
  # k6 web dashboard, live on http://localhost:5665 while the test runs; an HTML report lands in results/ at the end.
  $stamp = Get-Date -Format 'yyyy-MM-ddTHH-mm-ss'
  # storage-net is "internal" and Docker cannot publish ports there; filegrid_public (nginx only) allows it.
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

Write-Host "k6 $Scenario ($K6Image) -> results in tests\load\results\"
if ($Dashboard) { Write-Host "Live dashboard: http://localhost:5665 (open it in the browser while the test runs)" }
# k6 writes progress to stderr; under Windows PowerShell 5.1 with 'Stop' that would abort the script.
$ErrorActionPreference = 'Continue'
& docker @dockerArgs 2>&1 | ForEach-Object { "$_" }
exit $LASTEXITCODE
