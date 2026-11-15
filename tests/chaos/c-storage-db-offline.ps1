<#
.SYNOPSIS
  F9 c) FileGridStorage database unavailable: reading batches (METER_READING, primary DB) go to S3 (the policy
  fallback, 4.2), are confirmed, and resending them creates no duplicates. FileGridMeta stays up.
.DESCRIPTION
  Safe, reversible breakage: ALTER DATABASE FileGridStorage SET OFFLINE WITH ROLLBACK IMMEDIATE (only closes the
  FileGridStorage connections; the mssql container and FileGridMeta stay up). Repair: SET ONLINE (also in
  finally, even if the scenario fails).
.EXAMPLE
  .\tests\chaos\c-storage-db-offline.ps1
#>
. "$PSScriptRoot\lib\chaos.ps1"
Write-Title 'c) FileGridStorage OFFLINE -> METER_READING on S3, readings confirmed, no duplicates'

$meterId = "$RunId-MUN-0000001"
$day0 = [datetime]::SpecifyKind([datetime]'2026-10-01T00:00:00', 'Utc')
$offline = $false

function Get-StorageDbState { return ((Invoke-Sql "SELECT state_desc FROM sys.databases WHERE name = 'FileGridStorage'") -join '').Trim() }

function Send-AndLocate($Batch, [string]$Label, [int[]]$ExpectStatus, [string]$ExpectStorage) {
  $r = Send-MeterBatch $Batch
  $storage = $null
  if ($r.Json -and $r.Json.fileId) { $m = Get-FileMeta $r.Json.fileId; if ($m.Json) { $storage = $m.Json.storage } }
  [void](Assert-Check "$Label -> $($ExpectStatus -join '/') on $ExpectStorage" (($ExpectStatus -contains $r.Status) -and $storage -eq $ExpectStorage) "$(Format-Response $r), storage $storage, duplicate $($r.Json.duplicate)")
  return $r
}

try {
  Write-Step "setup: meter $meterId, one batch before the outage"
  [void](Assert-StorageState 'DB' 'UP' 'CLOSED' 60)
  $b1 = New-MeterBatch $meterId $day0
  $r1 = Send-AndLocate $b1 'batch 1 (before)' @(201) 'DB'

  Write-Step 'break: ALTER DATABASE FileGridStorage SET OFFLINE WITH ROLLBACK IMMEDIATE'
  $offline = $true
  [void](Invoke-Sql 'ALTER DATABASE [FileGridStorage] SET OFFLINE WITH ROLLBACK IMMEDIATE;')
  Write-Info "FileGridStorage: $(Get-StorageDbState)"
  [void](Assert-StorageState 'DB' 'DOWN' 'OPEN' 45)

  Write-Step 'checking during the outage'
  $b2 = New-MeterBatch $meterId $day0.AddDays(1)
  $r2 = Send-AndLocate $b2 'batch 2 (FileGridStorage offline)' @(201) 'S3'
  [void](Assert-Check 'batch 2: confirmed (sha256, 96 readings, duplicate:false)' ($r2.Json.readingsCount -eq 96 -and $r2.Json.duplicate -eq $false -and $r2.Json.sha256 -match '^[0-9a-f]{64}$'))
  $r2b = Send-MeterBatch $b2
  [void](Assert-Check 'batch 2 resent -> 200 duplicate:true, same fileId' ($r2b.Status -eq 200 -and $r2b.Json.duplicate -eq $true -and $r2b.Json.fileId -eq $r2.Json.fileId) (Format-Response $r2b))
  $r1b = Send-MeterBatch $b1
  [void](Assert-Check 'batch 1 resent (its file is on the down DB) -> 200 duplicate:true' ($r1b.Status -eq 200 -and $r1b.Json.duplicate -eq $true -and $r1b.Json.fileId -eq $r1.Json.fileId) (Format-Response $r1b))
  [void](Test-DownloadOk $r2.Json.fileId $r2.Json.sha256 'batch 2 (S3)')
  $d1 = Get-Download $r1.Json.fileId
  [void](Assert-Check 'batch 1 (on DB) cannot be downloaded now: 503 with Retry-After' ($d1.Status -eq 503 -and $d1.Headers['Retry-After']) (Format-Response $d1))
  $search = Invoke-Api -Path '/files?category=METER_READING&size=1' -User 'dispecer'
  [void](Assert-Check 'FileGridMeta still responds (dispatcher search 200)' ($search.Status -eq 200) (Format-Response $search))

  Wait-Demo
  Write-Step 'repair: ALTER DATABASE FileGridStorage SET ONLINE'
  [void](Invoke-Sql 'ALTER DATABASE [FileGridStorage] SET ONLINE;')
  $offline = $false
  Write-Info "FileGridStorage: $(Get-StorageDbState)"
  [void](Assert-StorageState 'DB' 'UP' 'CLOSED' 60)

  Write-Step 'checking recovery'
  $b3 = New-MeterBatch $meterId $day0.AddDays(2)
  $r3 = Send-AndLocate $b3 'batch 3 (after recovery)' @(201) 'DB'
  $r2c = Send-MeterBatch $b2
  [void](Assert-Check 'batch 2 resent after recovery -> still 200 duplicate:true (not rewritten to DB)' ($r2c.Status -eq 200 -and $r2c.Json.duplicate -eq $true -and $r2c.Json.fileId -eq $r2.Json.fileId) (Format-Response $r2c))
  [void](Test-DownloadOk $r1.Json.fileId $r1.Json.sha256 'batch 1 (DB, before)')
  [void](Test-DownloadOk $r2.Json.fileId $r2.Json.sha256 'batch 2 (S3, during the outage)')

  Write-Step "checking FileGridMeta: readings of meter $meterId"
  $row = (Invoke-Sql ("SELECT COUNT(*), COUNT(DISTINCT reading_ts), COUNT(DISTINCT file_id), SUM(CASE WHEN file_id IS NULL THEN 1 ELSE 0 END) " +
    "FROM dbo.meter_readings WHERE meter_id = '$meterId';") 'FileGridMeta' | Select-Object -Last 1).Split(',')
  Write-Info "readings=$($row[0]), distinct timestamps=$($row[1]), files=$($row[2]), unconfirmed=$($row[3])"
  [void](Assert-Check '288 readings (3 batches x 96), no duplicates, all confirmed in 3 files' ($row[0] -eq '288' -and $row[1] -eq '288' -and $row[2] -eq '3' -and $row[3] -eq '0'))
} catch {
  [void](Assert-Check 'scenario c ran without exceptions' $false (Format-Error $_))
} finally {
  try {
    if ($offline -or (Get-StorageDbState) -ne 'ONLINE') {
      Write-Step 'repair (finally): ALTER DATABASE FileGridStorage SET ONLINE'
      [void](Invoke-Sql 'ALTER DATABASE [FileGridStorage] SET ONLINE;')
      Write-Info "FileGridStorage: $(Get-StorageDbState)"
    }
  } catch { Write-Host "  repair failed: $($_.Exception.Message)" -ForegroundColor Red }
}
exit (Complete-Scenario 'c-storage-db-offline')
