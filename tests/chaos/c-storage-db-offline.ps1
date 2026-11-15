<#
.SYNOPSIS
  F9 c) Baza FileGridStorage indisponibila: loturile de citiri (METER_READING, principal DB) merg pe S3 (rezerva din
  politica 4.2), sunt confirmate, iar retrimiterea nu creeaza duplicate. FileGridMeta ramane pornita.
.DESCRIPTION
  Stricare sigura si reversibila: ALTER DATABASE FileGridStorage SET OFFLINE WITH ROLLBACK IMMEDIATE (inchide doar
  conexiunile catre FileGridStorage; containerul mssql si FileGridMeta raman pornite). Reparare: SET ONLINE (si in
  finally, chiar daca scenariul esueaza).
.EXAMPLE
  .\tests\chaos\c-storage-db-offline.ps1
#>
. "$PSScriptRoot\lib\chaos.ps1"
Write-Title 'c) FileGridStorage OFFLINE -> METER_READING pe S3, citiri confirmate, fara duplicate'

$meterId = "$RunId-MUN-0000001"
$day0 = [datetime]::SpecifyKind([datetime]'2026-10-01T00:00:00', 'Utc')
$offline = $false

function Get-StorageDbState { return ((Invoke-Sql "SELECT state_desc FROM sys.databases WHERE name = 'FileGridStorage'") -join '').Trim() }

function Send-AndLocate($Batch, [string]$Label, [int[]]$ExpectStatus, [string]$ExpectStorage) {
  $r = Send-MeterBatch $Batch
  $storage = $null
  if ($r.Json -and $r.Json.fileId) { $m = Get-FileMeta $r.Json.fileId; if ($m.Json) { $storage = $m.Json.storage } }
  [void](Assert-Check "$Label -> $($ExpectStatus -join '/') pe $ExpectStorage" (($ExpectStatus -contains $r.Status) -and $storage -eq $ExpectStorage) "$(Format-Response $r), storage $storage, duplicate $($r.Json.duplicate)")
  return $r
}

try {
  Write-Step "pregatire: contor $meterId, un lot inainte de cadere"
  [void](Assert-StorageState 'DB' 'UP' 'CLOSED' 60)
  $b1 = New-MeterBatch $meterId $day0
  $r1 = Send-AndLocate $b1 'lot 1 (inainte)' @(201) 'DB'

  Write-Step 'stric: ALTER DATABASE FileGridStorage SET OFFLINE WITH ROLLBACK IMMEDIATE'
  $offline = $true
  [void](Invoke-Sql 'ALTER DATABASE [FileGridStorage] SET OFFLINE WITH ROLLBACK IMMEDIATE;')
  Write-Info "FileGridStorage: $(Get-StorageDbState)"
  [void](Assert-StorageState 'DB' 'DOWN' 'OPEN' 45)

  Write-Step 'verific in timpul caderii'
  $b2 = New-MeterBatch $meterId $day0.AddDays(1)
  $r2 = Send-AndLocate $b2 'lot 2 (FileGridStorage offline)' @(201) 'S3'
  [void](Assert-Check 'lot 2: confirmat (sha256, 96 citiri, duplicate:false)' ($r2.Json.readingsCount -eq 96 -and $r2.Json.duplicate -eq $false -and $r2.Json.sha256 -match '^[0-9a-f]{64}$'))
  $r2b = Send-MeterBatch $b2
  [void](Assert-Check 'lot 2 retrimis -> 200 duplicate:true, acelasi fileId' ($r2b.Status -eq 200 -and $r2b.Json.duplicate -eq $true -and $r2b.Json.fileId -eq $r2.Json.fileId) (Format-Response $r2b))
  $r1b = Send-MeterBatch $b1
  [void](Assert-Check 'lot 1 retrimis (fisierul lui e pe DB, cazut) -> 200 duplicate:true' ($r1b.Status -eq 200 -and $r1b.Json.duplicate -eq $true -and $r1b.Json.fileId -eq $r1.Json.fileId) (Format-Response $r1b))
  [void](Test-DownloadOk $r2.Json.fileId $r2.Json.sha256 'lot 2 (S3)')
  $d1 = Get-Download $r1.Json.fileId
  [void](Assert-Check 'lot 1 (pe DB) nu se poate descarca acum: 503 cu Retry-After' ($d1.Status -eq 503 -and $d1.Headers['Retry-After']) (Format-Response $d1))
  $search = Invoke-Api -Path '/files?category=METER_READING&size=1' -User 'dispecer'
  [void](Assert-Check 'FileGridMeta raspunde in continuare (cautare dispecer 200)' ($search.Status -eq 200) (Format-Response $search))

  Wait-Demo
  Write-Step 'repar: ALTER DATABASE FileGridStorage SET ONLINE'
  [void](Invoke-Sql 'ALTER DATABASE [FileGridStorage] SET ONLINE;')
  $offline = $false
  Write-Info "FileGridStorage: $(Get-StorageDbState)"
  [void](Assert-StorageState 'DB' 'UP' 'CLOSED' 60)

  Write-Step 'verific revenirea'
  $b3 = New-MeterBatch $meterId $day0.AddDays(2)
  $r3 = Send-AndLocate $b3 'lot 3 (dupa revenire)' @(201) 'DB'
  $r2c = Send-MeterBatch $b2
  [void](Assert-Check 'lot 2 retrimis dupa revenire -> tot 200 duplicate:true (nu se rescrie pe DB)' ($r2c.Status -eq 200 -and $r2c.Json.duplicate -eq $true -and $r2c.Json.fileId -eq $r2.Json.fileId) (Format-Response $r2c))
  [void](Test-DownloadOk $r1.Json.fileId $r1.Json.sha256 'lot 1 (DB, de dinainte)')
  [void](Test-DownloadOk $r2.Json.fileId $r2.Json.sha256 'lot 2 (S3, din timpul caderii)')

  Write-Step "verific in FileGridMeta: citirile contorului $meterId"
  $row = (Invoke-Sql ("SELECT COUNT(*), COUNT(DISTINCT reading_ts), COUNT(DISTINCT file_id), SUM(CASE WHEN file_id IS NULL THEN 1 ELSE 0 END) " +
    "FROM dbo.meter_readings WHERE meter_id = '$meterId';") 'FileGridMeta' | Select-Object -Last 1).Split(',')
  Write-Info "citiri=$($row[0]), momente distincte=$($row[1]), fisiere=$($row[2]), neconfirmate=$($row[3])"
  [void](Assert-Check '288 citiri (3 loturi x 96), fara duplicate, toate confirmate in 3 fisiere' ($row[0] -eq '288' -and $row[1] -eq '288' -and $row[2] -eq '3' -and $row[3] -eq '0'))
} catch {
  [void](Assert-Check 'scenariul c rulat fara exceptii' $false (Format-Error $_))
} finally {
  try {
    if ($offline -or (Get-StorageDbState) -ne 'ONLINE') {
      Write-Step 'reparare (finally): ALTER DATABASE FileGridStorage SET ONLINE'
      [void](Invoke-Sql 'ALTER DATABASE [FileGridStorage] SET ONLINE;')
      Write-Info "FileGridStorage: $(Get-StorageDbState)"
    }
  } catch { Write-Host "  reparare esuata: $($_.Exception.Message)" -ForegroundColor Red }
}
exit (Complete-Scenario 'c-storage-db-offline')
