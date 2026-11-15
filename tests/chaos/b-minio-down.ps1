<#
.SYNOPSIS
  F9 b) MinIO oprit: inregistrarile de avarie (OUTAGE_RECORD, principal S3) merg pe DB (rezerva din politica 4.2);
  dupa pornire revin pe S3, iar fisierele de dinainte si din timpul caderii se descarca cu hash corect.
.EXAMPLE
  .\tests\chaos\b-minio-down.ps1
#>
. "$PSScriptRoot\lib\chaos.ps1"
Write-Title 'b) MinIO oprit -> OUTAGE_RECORD pe DB, revenire pe S3'

$stopped = $false
try {
  Write-Step 'pregatire: S3 sanatos si o avarie incarcata inainte de cadere'
  [void](Assert-StorageState 'S3' 'UP' 'CLOSED' 60)
  $f1 = New-OutageCsv "$RunId-avarie-1-inainte.csv"
  $o1 = Send-Outage $f1
  [void](Assert-Check 'avarie 1 (inainte) -> 201 pe S3' ($o1.Status -eq 201 -and $o1.Json.storage -eq 'S3') "$(Format-Response $o1), storage $($o1.Json.storage)")

  Write-Step 'stric: docker compose stop minio'
  $stopped = $true
  [void](Invoke-Compose -Arguments @('stop', 'minio'))
  [void](Assert-StorageState 'S3' 'DOWN' 'OPEN' 45)

  Write-Step 'verific in timpul caderii'
  $f2 = New-OutageCsv "$RunId-avarie-2-in-cadere.csv"
  $o2 = Send-Outage $f2
  [void](Assert-Check 'avarie 2 (S3 cazut) -> 201 pe DB (rezerva)' ($o2.Status -eq 201 -and $o2.Json.storage -eq 'DB') "$(Format-Response $o2), storage $($o2.Json.storage)")
  [void](Assert-Check 'avarie 2: sha256 din raspuns = sha256 local' ($o2.Json.sha256 -eq (Get-Sha256 $f2)))
  [void](Test-DownloadOk $o2.Json.id (Get-Sha256 $f2) 'avarie 2 (DB, in timpul caderii)')
  $d1 = Get-Download $o1.Json.id
  [void](Assert-Check 'avarie 1 (pe S3) nu se poate descarca acum: 503 cu Retry-After' ($d1.Status -eq 503 -and $d1.Headers['Retry-After']) (Format-Response $d1))

  Wait-Demo
  Write-Step 'repar: docker compose start minio'
  [void](Invoke-Compose -Arguments @('start', 'minio'))
  $stopped = $false
  $sec = Wait-ServiceHealthy 'minio' 90
  [void](Assert-Check 'container minio healthy' ($sec -ge 0) "dupa $sec s")
  [void](Assert-StorageState 'S3' 'UP' 'CLOSED' 60)

  Write-Step 'verific revenirea'
  $f3 = New-OutageCsv "$RunId-avarie-3-dupa.csv"
  $o3 = Send-Outage $f3
  [void](Assert-Check 'avarie 3 (dupa revenire) -> 201 din nou pe S3' ($o3.Status -eq 201 -and $o3.Json.storage -eq 'S3') "$(Format-Response $o3), storage $($o3.Json.storage)")
  [void](Test-DownloadOk $o1.Json.id (Get-Sha256 $f1) 'avarie 1 (S3, de dinainte)')
  [void](Test-DownloadOk $o2.Json.id (Get-Sha256 $f2) 'avarie 2 (DB, din timpul caderii)')
  [void](Test-DownloadOk $o3.Json.id (Get-Sha256 $f3) 'avarie 3 (S3, dupa)')
} catch {
  [void](Assert-Check 'scenariul b rulat fara exceptii' $false (Format-Error $_))
} finally {
  if ($stopped -or (Get-ServiceHealth 'minio') -notlike 'running/*') {
    Write-Step 'reparare (finally): docker compose start minio'
    try { [void](Invoke-Compose -Arguments @('start', 'minio')); [void](Wait-ServiceHealthy 'minio' 90) } catch { Write-Host "  reparare esuata: $($_.Exception.Message)" -ForegroundColor Red }
  }
}
exit (Complete-Scenario 'b-minio-down')
