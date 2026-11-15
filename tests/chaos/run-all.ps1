<#
.SYNOPSIS
  Runs all F9 chaos tests (a..h), each in its own process, and prints a results table.
  Finally checks the stack: all containers healthy, all storage backends UP, chaos profile stopped.
.EXAMPLE
  .\tests\chaos\run-all.ps1
  .\tests\chaos\run-all.ps1 -Only a,b,e
  .\tests\chaos\run-all.ps1 -Skip f
#>
param([string[]]$Only, [string[]]$Skip)
$ErrorActionPreference = 'Stop'
# With -File (e.g. from run-all.sh) "a,e" arrives as a single string.
if ($Only) { $Only = @($Only | ForEach-Object { $_ -split ',' } | Where-Object { $_ }) }
if ($Skip) { $Skip = @($Skip | ForEach-Object { $_ -split ',' } | Where-Object { $_ }) }

$scenarios = [ordered]@{
  a = 'a-ftp-down.ps1'
  b = 'b-minio-down.ps1'
  c = 'c-storage-db-offline.ps1'
  d = 'd-toxiproxy.ps1'
  e = 'e-corruption.ps1'
  f = 'f-bulkhead.ps1'
  g = 'g-chunked-resume.ps1'
  h = 'h-client-network.ps1'
}
# Same interpreter as this one (Windows PowerShell 5.1 or pwsh).
$shell = (Get-Process -Id $PID).Path
$results = @()
foreach ($k in $scenarios.Keys) {
  if ($Only -and $Only -notcontains $k) { continue }
  if ($Skip -and $Skip -contains $k) { continue }
  $file = Join-Path $PSScriptRoot $scenarios[$k]
  $sw = [Diagnostics.Stopwatch]::StartNew()
  & $shell -NoProfile -ExecutionPolicy Bypass -File $file
  $code = $LASTEXITCODE
  $results += [pscustomobject]@{ Scenario = $scenarios[$k]; Result = $(if ($code -eq 0) { 'PASSED' } else { "FAILED ($code)" }); Duration = '{0:0} s' -f $sw.Elapsed.TotalSeconds }
}

# Final stack check (same helpers as the scenarios).
$env:CHAOS_RUN_ID = 'chaos-final'
& $shell -NoProfile -ExecutionPolicy Bypass -Command ". '$PSScriptRoot\lib\chaos.ps1'; Write-Title 'Stack after the tests'; Assert-StackHealthy; [void](Assert-Check 'chaos profile stopped (no toxiproxy container)' (-not (Get-ContainerId 'toxiproxy'))); exit (Complete-Scenario 'stack')"
$stackCode = $LASTEXITCODE
Remove-Item Env:CHAOS_RUN_ID -ErrorAction SilentlyContinue
$results += [pscustomobject]@{ Scenario = 'stack healthy at the end'; Result = $(if ($stackCode -eq 0) { 'PASSED' } else { "FAILED ($stackCode)" }); Duration = '' }

Write-Host ''
Write-Host '=== F9 summary ===' -ForegroundColor Cyan
$results | Format-Table -AutoSize | Out-String | Write-Host
if (@($results | Where-Object { $_.Result -ne 'PASSED' }).Count -gt 0) { exit 1 }
exit 0
