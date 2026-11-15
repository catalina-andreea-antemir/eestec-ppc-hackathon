<#
.SYNOPSIS
  Ruleaza toate testele de haos F9 (a..h), fiecare in procesul lui, si afiseaza un tabel cu rezultatele.
  La final verifica stack-ul: toate containerele healthy, toate storage-urile UP, profilul chaos oprit.
.EXAMPLE
  .\tests\chaos\run-all.ps1
  .\tests\chaos\run-all.ps1 -Only a,b,e
  .\tests\chaos\run-all.ps1 -Skip f
#>
param([string[]]$Only, [string[]]$Skip)
$ErrorActionPreference = 'Stop'
# Cu -File (ex. din run-all.sh) "a,e" ajunge ca un singur sir.
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
# Acelasi interpretor ca acesta (Windows PowerShell 5.1 sau pwsh).
$shell = (Get-Process -Id $PID).Path
$results = @()
foreach ($k in $scenarios.Keys) {
  if ($Only -and $Only -notcontains $k) { continue }
  if ($Skip -and $Skip -contains $k) { continue }
  $file = Join-Path $PSScriptRoot $scenarios[$k]
  $sw = [Diagnostics.Stopwatch]::StartNew()
  & $shell -NoProfile -ExecutionPolicy Bypass -File $file
  $code = $LASTEXITCODE
  $results += [pscustomobject]@{ Scenariu = $scenarios[$k]; Rezultat = $(if ($code -eq 0) { 'TRECUT' } else { "ESUAT ($code)" }); Durata = '{0:0} s' -f $sw.Elapsed.TotalSeconds }
}

# Verificarea finala a stack-ului (aceleasi functii ca scenariile).
$env:CHAOS_RUN_ID = 'chaos-final'
& $shell -NoProfile -ExecutionPolicy Bypass -Command ". '$PSScriptRoot\lib\chaos.ps1'; Write-Title 'Stack-ul dupa teste'; Assert-StackHealthy; [void](Assert-Check 'profilul chaos oprit (fara container toxiproxy)' (-not (Get-ContainerId 'toxiproxy'))); exit (Complete-Scenario 'stack')"
$stackCode = $LASTEXITCODE
Remove-Item Env:CHAOS_RUN_ID -ErrorAction SilentlyContinue
$results += [pscustomobject]@{ Scenariu = 'stack sanatos la final'; Rezultat = $(if ($stackCode -eq 0) { 'TRECUT' } else { "ESUAT ($stackCode)" }); Durata = '' }

Write-Host ''
Write-Host '=== Rezumat F9 ===' -ForegroundColor Cyan
$results | Format-Table -AutoSize | Out-String | Write-Host
if (@($results | Where-Object { $_.Rezultat -ne 'TRECUT' }).Count -gt 0) { exit 1 }
exit 0
