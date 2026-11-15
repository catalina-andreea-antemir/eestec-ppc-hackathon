<#
.SYNOPSIS
  F9 a) FTP oprit: video-urile noi merg pe S3 (rezerva din politica 4.2), circuit breaker-ul FTP se deschide (C11),
  iar dupa pornire upload-urile noi revin pe FTP si fisierele vechi se descarca cu hash corect.
.EXAMPLE
  .\tests\chaos\a-ftp-down.ps1
#>
. "$PSScriptRoot\lib\chaos.ps1"
Write-Title 'a) FTP oprit -> INSPECTION_VIDEO pe S3, circuit deschis, revenire pe FTP'

$stopped = $false
try {
  Write-Step 'pregatire: FTP sanatos si un video incarcat inainte de cadere'
  [void](Assert-StorageState 'FTP' 'UP' 'CLOSED' 60)
  $f1 = New-RandomFile "$RunId-inspectie-1-inainte.mp4" (256KB)
  $u1 = Send-Video $f1
  [void](Assert-Check 'video 1 (inainte) -> 201 pe FTP' ($u1.Ok -and $u1.Complete.storage -eq 'FTP') "$(Format-Response $u1.Response), storage $($u1.Complete.storage)")
  [void](Assert-Check 'video 1: sha256 din raspuns = sha256 local' ($u1.Complete.sha256 -eq (Get-Sha256 $f1)))

  Write-Step 'stric: docker compose stop ftp'
  $stopped = $true
  [void](Invoke-Compose -Arguments @('stop', 'ftp'))
  [void](Assert-StorageState 'FTP' 'DOWN' 'OPEN' 45)

  Write-Step 'verific in timpul caderii'
  $f2 = New-RandomFile "$RunId-inspectie-2-in-cadere.mp4" (256KB)
  $u2 = Send-Video $f2
  [void](Assert-Check 'video 2 (FTP cazut) -> 201 pe S3 (rezerva)' ($u2.Ok -and $u2.Complete.storage -eq 'S3') "$(Format-Response $u2.Response), storage $($u2.Complete.storage)")
  [void](Assert-Check 'video 2: sha256 din raspuns = sha256 local' ($u2.Complete.sha256 -eq (Get-Sha256 $f2)))
  $d1 = Get-Download $u1.Complete.id
  [void](Assert-Check 'video 1 (pe FTP) nu se poate descarca acum: 503 cu Retry-After' ($d1.Status -eq 503 -and $d1.Headers['Retry-After']) (Format-Response $d1))
  Write-Info "health: $(Format-StorageState (Get-StorageState 'FTP'))"

  Wait-Demo
  Write-Step 'repar: docker compose start ftp'
  [void](Invoke-Compose -Arguments @('start', 'ftp'))
  $stopped = $false
  $sec = Wait-ServiceHealthy 'ftp' 90
  [void](Assert-Check 'container ftp healthy' ($sec -ge 0) "dupa $sec s")
  [void](Assert-StorageState 'FTP' 'UP' 'CLOSED' 60)

  Write-Step 'verific revenirea'
  $f3 = New-RandomFile "$RunId-inspectie-3-dupa.mp4" (256KB)
  $u3 = Send-Video $f3
  [void](Assert-Check 'video 3 (dupa revenire) -> 201 din nou pe FTP' ($u3.Ok -and $u3.Complete.storage -eq 'FTP') "$(Format-Response $u3.Response), storage $($u3.Complete.storage)")
  [void](Test-DownloadOk $u1.Complete.id (Get-Sha256 $f1) 'video 1 (FTP, de dinainte)')
  [void](Test-DownloadOk $u2.Complete.id (Get-Sha256 $f2) 'video 2 (S3, din timpul caderii)')
  [void](Test-DownloadOk $u3.Complete.id (Get-Sha256 $f3) 'video 3 (FTP, dupa)')
  $v = Get-Verify $u1.Complete.id
  [void](Assert-Check 'GET /files/{id}/verify video 1 -> ok:true' ($v.Status -eq 200 -and $v.Json.ok -eq $true) (Format-Response $v))
} catch {
  [void](Assert-Check 'scenariul a rulat fara exceptii' $false (Format-Error $_))
} finally {
  if ($stopped -or (Get-ServiceHealth 'ftp') -notlike 'running/*') {
    Write-Step 'reparare (finally): docker compose start ftp'
    try { [void](Invoke-Compose -Arguments @('start', 'ftp')); [void](Wait-ServiceHealthy 'ftp' 90) } catch { Write-Host "  reparare esuata: $($_.Exception.Message)" -ForegroundColor Red }
  }
}
exit (Complete-Scenario 'a-ftp-down')
