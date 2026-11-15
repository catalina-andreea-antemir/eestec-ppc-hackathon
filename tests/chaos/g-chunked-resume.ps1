<#
.SYNOPSIS
  F9 g) Upload video in bucati intrerupt: clientul (curl) e omorat in mijlocul bucatii 2, apoi upload-ul se reia de
  la nextChunk (GET /uploads/{id}) si se termina cu hash final corect (C7, contract /uploads).
.EXAMPLE
  .\tests\chaos\g-chunked-resume.ps1
  .\tests\chaos\g-chunked-resume.ps1 -SizeMiB 80
#>
param([int]$SizeMiB = 40, [string]$LimitRate = '2M', [int]$KillAfterSec = 3)
. "$PSScriptRoot\lib\chaos.ps1"
Write-Title 'g) Upload video in bucati: client omorat la mijloc, reluare, hash final corect'

$uploadId = $null
$completed = $false
$proc = $null

# PUT cu reincercare la 409 UPLOAD_BUSY / 503 (Retry-After), ca interfata: pana la 2 minute.
function Send-ChunkWithRetry([string]$Id, [int]$N, [string]$Part) {
  $deadline = (Get-Date).AddMinutes(2)
  while ($true) {
    $r = Invoke-Api -Method PUT -Path "/uploads/$Id/chunks/$N" -User 'teren' -InFile $Part
    $busy = ($r.Status -eq 409 -and $r.Json.code -eq 'UPLOAD_BUSY') -or $r.Status -eq 503 -or $r.Status -eq 429
    if (-not $busy -or (Get-Date) -gt $deadline) { return $r }
    $wait = 1; if ($r.Headers['Retry-After']) { [void][int]::TryParse($r.Headers['Retry-After'], [ref]$wait) }
    Write-Info "bucata ${N}: $(Format-Response $r), reincerc dupa $wait s"
    Start-Sleep -Seconds ([math]::Max(1, $wait))
  }
}

try {
  Write-Step "pregatire: video de $SizeMiB MiB, sesiune de upload"
  [void](Assert-StorageState 'FTP' 'UP' 'CLOSED' 60)
  $f = New-RandomFile "$RunId-inspectie-reluare.mp4" ($SizeMiB * 1MB)
  $sha = Get-Sha256 $f
  $size = (Get-Item $f).Length
  $init = Invoke-Api -Method POST -Path '/uploads' -User 'teren' -Json (@{ category = 'INSPECTION_VIDEO'; fileName = (Split-Path $f -Leaf); totalSize = $size } | ConvertTo-Json -Compress)
  [void](Assert-Check 'POST /uploads -> 201' ($init.Status -eq 201) "$(Format-Response $init), storage $($init.Json.storage), $($init.Json.totalChunks) bucati x $($init.Json.chunkSize) B")
  if ($init.Status -ne 201) { throw 'init esuat' }
  $s = $init.Json; $uploadId = $s.uploadId; $cs = [int64]$s.chunkSize
  if ($s.totalChunks -lt 3) { throw "Fisierul trebuie sa aiba cel putin 3 bucati (are $($s.totalChunks)); mareste -SizeMiB." }

  $p1 = Get-FileSlice $f 0 $cs
  $r1 = Send-ChunkWithRetry $uploadId 1 $p1
  [void](Assert-Check 'bucata 1 -> 200' ($r1.Status -eq 200 -and $r1.Json.nextChunk -eq 2) (Format-Response $r1))

  Write-Step "stric: trimit bucata 2 cu $LimitRate/s si omor clientul dupa $KillAfterSec s"
  $p2 = Get-FileSlice $f $cs $cs
  $out = New-TempPath '.out'
  $argLine = "-sS -k -X PUT -H `"Authorization: Bearer $(Get-Token 'teren')`" -H `"Content-Type: application/octet-stream`" -H `"Expect:`" " +
             "--limit-rate $LimitRate --data-binary `"@$p2`" -o `"$out`" `"$BaseUrl/uploads/$uploadId/chunks/2`""
  $proc = Start-Process -FilePath $Curl -ArgumentList $argLine -PassThru -NoNewWindow
  Start-Sleep -Seconds $KillAfterSec
  $alive = -not $proc.HasExited
  if ($alive) { Stop-Process -Id $proc.Id -Force }
  [void](Assert-Check 'clientul a fost omorat in mijlocul bucatii 2' $alive)
  Start-Sleep -Seconds 1

  Write-Step 'verific: starea sesiunii dupa intrerupere'
  $st = Invoke-Api -Path "/uploads/$uploadId" -User 'teren'
  [void](Assert-Check 'GET /uploads/{id}: IN_PROGRESS, nextChunk = 2 (bucata partiala nu e confirmata)' ($st.Status -eq 200 -and $st.Json.nextChunk -eq 2 -and $st.Json.status -eq 'IN_PROGRESS') "$(Format-Response $st), nextChunk $($st.Json.nextChunk), receivedBytes $($st.Json.receivedBytes), status $($st.Json.status)")

  Write-Step 'repar: reiau de la nextChunk'
  $r2 = Send-ChunkWithRetry $uploadId 2 $p2
  [void](Assert-Check 'bucata 2 retrimisa -> 200' ($r2.Status -eq 200 -and $r2.Json.nextChunk -eq 3) "$(Format-Response $r2), duplicate $($r2.Json.duplicate)")
  [void](Assert-Check 'bucata 2: sha256 din raspuns = sha256 local al bucatii' ($r2.Json.sha256 -eq (Get-Sha256 $p2)))
  for ($n = 3; $n -le $s.totalChunks; $n++) {
    $off = ($n - 1) * $cs
    $pn = Get-FileSlice $f $off ([math]::Min($cs, $size - $off))
    $rn = Send-ChunkWithRetry $uploadId $n $pn
    [void](Assert-Check "bucata $n -> 200" ($rn.Status -eq 200) (Format-Response $rn))
  }
  $done = Invoke-Api -Method POST -Path "/uploads/$uploadId/complete" -User 'teren' -TimeoutSec 300
  $completed = ($done.Status -eq 201 -or $done.Status -eq 200)
  [void](Assert-Check 'complete -> 201 CONFIRMED' ($done.Status -eq 201 -and $done.Json.status -eq 'CONFIRMED') "$(Format-Response $done), storage $($done.Json.storage)")

  Write-Step 'verific: hash final'
  [void](Assert-Check 'sha256 final (server) = sha256 local al fisierului intreg' ($done.Json.sha256 -eq $sha) "$($sha.Substring(0, 12))...")
  [void](Test-DownloadOk $done.Json.id $sha 'video reluat')
  $v = Get-Verify $done.Json.id
  [void](Assert-Check 'verify -> ok:true' ($v.Status -eq 200 -and $v.Json.ok -eq $true) (Format-Response $v))
} catch {
  [void](Assert-Check 'scenariul g rulat fara exceptii' $false (Format-Error $_))
} finally {
  if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
  if ($uploadId -and -not $completed) {
    Write-Step 'curatare (finally): abort pe sesiunea neterminata'
    try { $a = Invoke-Api -Method POST -Path "/uploads/$uploadId/abort" -User 'teren'; Write-Info (Format-Response $a) } catch { }
  }
}
exit (Complete-Scenario 'g-chunked-resume')
