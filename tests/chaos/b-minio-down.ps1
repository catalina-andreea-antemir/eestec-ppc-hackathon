<#
.SYNOPSIS
  F9 b) MinIO down: outage records (OUTAGE_RECORD, primary S3) go to DB (the policy fallback, 4.2);
  after restart they return to S3, and files from before and during the outage download with the correct hash.
.EXAMPLE
  .\tests\chaos\b-minio-down.ps1
#>
. "$PSScriptRoot\lib\chaos.ps1"
Write-Title 'b) MinIO down -> OUTAGE_RECORD on DB, back on S3'

$stopped = $false
try {
  Write-Step 'setup: S3 healthy and one outage uploaded before the outage'
  [void](Assert-StorageState 'S3' 'UP' 'CLOSED' 60)
  $f1 = New-OutageCsv "$RunId-avarie-1-inainte.csv"
  $o1 = Send-Outage $f1
  [void](Assert-Check 'outage 1 (before) -> 201 on S3' ($o1.Status -eq 201 -and $o1.Json.storage -eq 'S3') "$(Format-Response $o1), storage $($o1.Json.storage)")

  Write-Step 'break: docker compose stop minio'
  $stopped = $true
  [void](Invoke-Compose -Arguments @('stop', 'minio'))
  [void](Assert-StorageState 'S3' 'DOWN' 'OPEN' 45)

  Write-Step 'checking during the outage'
  $f2 = New-OutageCsv "$RunId-avarie-2-in-cadere.csv"
  $o2 = Send-Outage $f2
  [void](Assert-Check 'outage 2 (S3 down) -> 201 on DB (fallback)' ($o2.Status -eq 201 -and $o2.Json.storage -eq 'DB') "$(Format-Response $o2), storage $($o2.Json.storage)")
  [void](Assert-Check 'outage 2: response sha256 = local sha256' ($o2.Json.sha256 -eq (Get-Sha256 $f2)))
  [void](Test-DownloadOk $o2.Json.id (Get-Sha256 $f2) 'outage 2 (DB, during the outage)')
  $d1 = Get-Download $o1.Json.id
  [void](Assert-Check 'outage 1 (on S3) cannot be downloaded now: 503 with Retry-After' ($d1.Status -eq 503 -and $d1.Headers['Retry-After']) (Format-Response $d1))

  Wait-Demo
  Write-Step 'repair: docker compose start minio'
  [void](Invoke-Compose -Arguments @('start', 'minio'))
  $stopped = $false
  $sec = Wait-ServiceHealthy 'minio' 90
  [void](Assert-Check 'container minio healthy' ($sec -ge 0) "after $sec s")
  [void](Assert-StorageState 'S3' 'UP' 'CLOSED' 60)

  Write-Step 'checking recovery'
  $f3 = New-OutageCsv "$RunId-avarie-3-dupa.csv"
  $o3 = Send-Outage $f3
  [void](Assert-Check 'outage 3 (after recovery) -> 201 on S3 again' ($o3.Status -eq 201 -and $o3.Json.storage -eq 'S3') "$(Format-Response $o3), storage $($o3.Json.storage)")
  [void](Test-DownloadOk $o1.Json.id (Get-Sha256 $f1) 'outage 1 (S3, before)')
  [void](Test-DownloadOk $o2.Json.id (Get-Sha256 $f2) 'outage 2 (DB, during the outage)')
  [void](Test-DownloadOk $o3.Json.id (Get-Sha256 $f3) 'outage 3 (S3, after)')
} catch {
  [void](Assert-Check 'scenario b ran without exceptions' $false (Format-Error $_))
} finally {
  if ($stopped -or (Get-ServiceHealth 'minio') -notlike 'running/*') {
    Write-Step 'repair (finally): docker compose start minio'
    try { [void](Invoke-Compose -Arguments @('start', 'minio')); [void](Wait-ServiceHealthy 'minio' 90) } catch { Write-Host "  repair failed: $($_.Exception.Message)" -ForegroundColor Red }
  }
}
exit (Complete-Scenario 'b-minio-down')
