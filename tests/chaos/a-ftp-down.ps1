<#
.SYNOPSIS
  F9 a) FTP down: new videos go to S3 (the policy fallback, 4.2), the FTP circuit breaker opens (C11),
  and after restart new uploads return to FTP and older files download with the correct hash.
.EXAMPLE
  .\tests\chaos\a-ftp-down.ps1
#>
. "$PSScriptRoot\lib\chaos.ps1"
Write-Title 'a) FTP down -> INSPECTION_VIDEO on S3, circuit open, back on FTP'

$stopped = $false
try {
  Write-Step 'setup: FTP healthy and one video uploaded before the outage'
  [void](Assert-StorageState 'FTP' 'UP' 'CLOSED' 60)
  $f1 = New-RandomFile "$RunId-inspectie-1-inainte.mp4" (256KB)
  $u1 = Send-Video $f1
  [void](Assert-Check 'video 1 (before) -> 201 on FTP' ($u1.Ok -and $u1.Complete.storage -eq 'FTP') "$(Format-Response $u1.Response), storage $($u1.Complete.storage)")
  [void](Assert-Check 'video 1: response sha256 = local sha256' ($u1.Complete.sha256 -eq (Get-Sha256 $f1)))

  Write-Step 'break: docker compose stop ftp'
  $stopped = $true
  [void](Invoke-Compose -Arguments @('stop', 'ftp'))
  [void](Assert-StorageState 'FTP' 'DOWN' 'OPEN' 45)

  Write-Step 'checking during the outage'
  $f2 = New-RandomFile "$RunId-inspectie-2-in-cadere.mp4" (256KB)
  $u2 = Send-Video $f2
  [void](Assert-Check 'video 2 (FTP down) -> 201 on S3 (fallback)' ($u2.Ok -and $u2.Complete.storage -eq 'S3') "$(Format-Response $u2.Response), storage $($u2.Complete.storage)")
  [void](Assert-Check 'video 2: response sha256 = local sha256' ($u2.Complete.sha256 -eq (Get-Sha256 $f2)))
  $d1 = Get-Download $u1.Complete.id
  [void](Assert-Check 'video 1 (on FTP) cannot be downloaded now: 503 with Retry-After' ($d1.Status -eq 503 -and $d1.Headers['Retry-After']) (Format-Response $d1))
  Write-Info "health: $(Format-StorageState (Get-StorageState 'FTP'))"

  Wait-Demo
  Write-Step 'repair: docker compose start ftp'
  [void](Invoke-Compose -Arguments @('start', 'ftp'))
  $stopped = $false
  $sec = Wait-ServiceHealthy 'ftp' 90
  [void](Assert-Check 'container ftp healthy' ($sec -ge 0) "after $sec s")
  [void](Assert-StorageState 'FTP' 'UP' 'CLOSED' 60)

  Write-Step 'checking recovery'
  $f3 = New-RandomFile "$RunId-inspectie-3-dupa.mp4" (256KB)
  $u3 = Send-Video $f3
  [void](Assert-Check 'video 3 (after recovery) -> 201 on FTP again' ($u3.Ok -and $u3.Complete.storage -eq 'FTP') "$(Format-Response $u3.Response), storage $($u3.Complete.storage)")
  [void](Test-DownloadOk $u1.Complete.id (Get-Sha256 $f1) 'video 1 (FTP, before)')
  [void](Test-DownloadOk $u2.Complete.id (Get-Sha256 $f2) 'video 2 (S3, during the outage)')
  [void](Test-DownloadOk $u3.Complete.id (Get-Sha256 $f3) 'video 3 (FTP, after)')
  $v = Get-Verify $u1.Complete.id
  [void](Assert-Check 'GET /files/{id}/verify video 1 -> ok:true' ($v.Status -eq 200 -and $v.Json.ok -eq $true) (Format-Response $v))
} catch {
  [void](Assert-Check 'scenario a ran without exceptions' $false (Format-Error $_))
} finally {
  if ($stopped -or (Get-ServiceHealth 'ftp') -notlike 'running/*') {
    Write-Step 'repair (finally): docker compose start ftp'
    try { [void](Invoke-Compose -Arguments @('start', 'ftp')); [void](Wait-ServiceHealthy 'ftp' 90) } catch { Write-Host "  repair failed: $($_.Exception.Message)" -ForegroundColor Red }
  }
}
exit (Complete-Scenario 'a-ftp-down')
